package com.daview.server.media

import com.daview.server.db.Repository
import com.daview.server.library.MkvProbe
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlayerKind
import com.daview.shared.model.SessionStateDto
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Tracks what is playing where.
 *
 * In-app players report their own position, so those sessions are exact.
 * External players (PotPlayer, VLC, mpv) never report anything, so their
 * position is *derived*:
 *
 *  - the stream endpoint sees every `Range` request the player makes, which
 *    pins playback to a byte offset. With a Matroska cue index that maps to an
 *    exact timestamp; without one it falls back to a linear byte ratio.
 *  - between those requests the position advances with the wall clock, which is
 *    correct while the player runs at 1x and drifts only while paused.
 *
 * The result is good enough to resume from, and it is always labelled as an
 * estimate so the UI can say so.
 */
class PlaybackService(
    private val repository: Repository,
    private val streams: StreamService,
    private val idleTimeoutSecProvider: () -> Int
) {
    private val log = LoggerFactory.getLogger(PlaybackService::class.java)
    private val counter = AtomicLong(0)

    class Session(
        val id: String,
        val itemId: String,
        val itemName: String,
        val player: PlayerKind,
        val deviceName: String,
        val path: String,
        val fileSize: Long?,
        @Volatile var runtimeMs: Long?,
        @Volatile var audioStreamIndex: Int?,
        @Volatile var subtitleStreamIndex: Int?
    ) {
        val startedAt: Long = System.currentTimeMillis()

        @Volatile var anchorPositionMs: Long = 0
        @Volatile var anchorWallClock: Long = System.currentTimeMillis()
        @Volatile var reportedPositionMs: Long? = null
        @Volatile var paused: Boolean = false
        @Volatile var lastActivity: Long = System.currentTimeMillis()
        @Volatile var maxByteOffset: Long = 0
        @Volatile var cueIndex: MkvProbe.CueIndex? = null
        @Volatile var stopped: Boolean = false

        val isExternal: Boolean get() = player != PlayerKind.INTERNAL

        /** Current position: reported when the player tells us, estimated otherwise. */
        fun positionMs(): Long {
            reportedPositionMs?.let { return it }
            val runtime = runtimeMs
            val elapsed = if (paused) 0 else System.currentTimeMillis() - anchorWallClock
            val estimated = anchorPositionMs + elapsed
            return if (runtime != null && runtime > 0) estimated.coerceIn(0, runtime) else maxOf(0, estimated)
        }

        fun positionSource(): String = when {
            reportedPositionMs != null -> "client"
            cueIndex != null -> "cue+clock"
            else -> "clock"
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    fun start(
        item: MediaItemDto,
        player: PlayerKind,
        deviceName: String,
        startPositionMs: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?
    ): Session {
        val id = "s" + counter.incrementAndGet() + "-" + System.currentTimeMillis().toString(36)
        val session = Session(
            id = id,
            itemId = item.id,
            itemName = item.name,
            player = player,
            deviceName = deviceName,
            path = item.path.orEmpty(),
            fileSize = item.sizeBytes,
            runtimeMs = item.runtimeMs,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex
        ).apply {
            anchorPositionMs = startPositionMs
            anchorWallClock = System.currentTimeMillis()
            // External players get no progress callbacks, so the position has to
            // be derived; pause the clock until the first byte request arrives.
            paused = player != PlayerKind.INTERNAL
        }
        sessions[id] = session

        if (session.isExternal) {
            Thread.ofVirtual().start {
                runCatching { session.cueIndex = streams.cueIndex(item) }
                    .onFailure { log.debug("cue index unavailable for {}", item.name) }
            }
        }
        sweep()
        return session
    }

    fun session(id: String): Session? = sessions[id]

    /** Called by in-app players that know their own position. */
    fun report(sessionId: String, positionMs: Long, paused: Boolean, audio: Int?, subtitle: Int?): Session? {
        val session = sessions[sessionId] ?: return null
        session.reportedPositionMs = positionMs
        session.anchorPositionMs = positionMs
        session.anchorWallClock = System.currentTimeMillis()
        session.paused = paused
        session.lastActivity = System.currentTimeMillis()
        audio?.let { session.audioStreamIndex = it }
        subtitle?.let { session.subtitleStreamIndex = it }
        persist(session, finished = false)
        return session
    }

    /**
     * Called by the stream endpoint for every byte range an external player asks
     * for. [rangeStart] is the first byte requested.
     */
    fun onRangeRequest(sessionId: String, rangeStart: Long) {
        val session = sessions[sessionId] ?: return
        session.lastActivity = System.currentTimeMillis()
        if (!session.isExternal) return

        val derived = timeAtByte(session, rangeStart)
        val now = System.currentTimeMillis()
        if (derived != null) {
            val projected = session.positionMs()
            val seeked = session.paused || abs(derived - projected) > SEEK_TOLERANCE_MS
            if (seeked) {
                session.anchorPositionMs = derived
                session.anchorWallClock = now
            }
        }
        if (session.paused) {
            session.paused = false
            session.anchorWallClock = now
        }
        if (rangeStart > session.maxByteOffset) session.maxByteOffset = rangeStart
        persist(session, finished = false)
    }

    /** Continuous progress for proxied streams: how far the player has read. */
    fun onBytesRead(sessionId: String, absoluteOffset: Long) {
        val session = sessions[sessionId] ?: return
        session.lastActivity = System.currentTimeMillis()
        if (!session.isExternal) return
        if (absoluteOffset <= session.maxByteOffset) return
        session.maxByteOffset = absoluteOffset

        val derived = timeAtByte(session, absoluteOffset) ?: return
        val projected = session.positionMs()
        // The reader always runs ahead of the picture; only correct when the gap
        // is large enough that the wall clock must have drifted (a seek, or a
        // long pause that the clock kept counting through).
        if (abs(derived - projected) > SEEK_TOLERANCE_MS) {
            session.anchorPositionMs = (derived - READ_AHEAD_ALLOWANCE_MS).coerceAtLeast(0)
            session.anchorWallClock = System.currentTimeMillis()
        }
    }

    private fun timeAtByte(session: Session, byteOffset: Long): Long? {
        session.cueIndex?.timeAtOrBefore(byteOffset)?.let { return it }
        val size = session.fileSize ?: return null
        val runtime = session.runtimeMs ?: return null
        if (size <= 0 || runtime <= 0) return null
        return (byteOffset.toDouble() / size.toDouble() * runtime.toDouble()).toLong()
    }

    fun stop(sessionId: String, positionMs: Long?): Session? {
        val session = sessions.remove(sessionId) ?: return null
        if (positionMs != null && positionMs >= 0) {
            session.reportedPositionMs = positionMs
            session.anchorPositionMs = positionMs
        }
        session.stopped = true
        persist(session, finished = true)
        return session
    }

    fun activeSessions(): List<SessionStateDto> {
        sweep()
        return sessions.values.map { it.toDto() }
    }

    private fun Session.toDto() = SessionStateDto(
        sessionId = id,
        itemId = itemId,
        itemName = itemName,
        player = player,
        deviceName = deviceName,
        positionMs = positionMs(),
        runtimeMs = runtimeMs,
        paused = paused,
        startedAt = startedAt,
        updatedAt = lastActivity,
        positionSource = positionSource()
    )

    /** Closes sessions whose player stopped asking for bytes. */
    fun sweep() {
        val timeout = idleTimeoutSecProvider() * 1000L
        val now = System.currentTimeMillis()
        sessions.values
            .filter { it.isExternal && now - it.lastActivity > timeout }
            .forEach {
                log.info("外部播放会话 {} 空闲超时，按 {} ms 记录进度", it.id, it.positionMs())
                sessions.remove(it.id)
                persist(it, finished = true)
            }
    }

    private fun persist(session: Session, finished: Boolean) {
        val position = session.positionMs()
        if (!finished && position < MIN_PERSIST_POSITION_MS) return
        repository.saveProgress(
            itemId = session.itemId,
            positionMs = position,
            runtimeMs = session.runtimeMs,
            audioStreamIndex = session.audioStreamIndex,
            subtitleStreamIndex = session.subtitleStreamIndex
        )
    }

    private companion object {
        const val SEEK_TOLERANCE_MS = 30_000L
        const val READ_AHEAD_ALLOWANCE_MS = 20_000L
        const val MIN_PERSIST_POSITION_MS = 5_000L
    }
}
