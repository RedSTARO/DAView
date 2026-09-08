package com.daview.server.media

import com.daview.server.db.Repository
import com.daview.server.library.MkvProbe
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlayerKind
import com.daview.shared.model.SessionStateDto
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min

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
 *    correct while the player runs at 1x.
 *  - when the bytes flow through the server, how far the player has downloaded
 *    is an upper bound on where the picture can be, which stops the clock from
 *    running away while playback is paused.
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
        @Volatile var cueIndex: MkvProbe.CueIndex? = null

        /** Range offset waiting to be confirmed as a real playback position. */
        @Volatile var pendingAnchorOffset: Long? = null
        @Volatile var pendingAnchorAt: Long = 0

        /** Highest byte actually streamed to the player through this server. */
        @Volatile var streamFrontier: Long = 0

        /**
         * True once bytes have flowed through the proxy since the last anchor.
         * Redirect-mode sessions never set it, so the download ceiling — which
         * would otherwise freeze the clock — simply does not apply to them.
         */
        @Volatile var frontierValid: Boolean = false

        val isExternal: Boolean get() = player != PlayerKind.INTERNAL

        /** Byte offset to timestamp, exact with a cue index and linear without one. */
        fun timeAtByte(byteOffset: Long): Long? {
            cueIndex?.timeAtOrBefore(byteOffset)?.let { return it }
            val size = fileSize ?: return null
            val runtime = runtimeMs ?: return null
            if (size <= 0 || runtime <= 0) return null
            return (byteOffset.toDouble() / size.toDouble() * runtime.toDouble()).toLong()
        }

        /**
         * A byte offset near the very end of the file is a container index read
         * (Matroska `Cues`, a tail `moov`), not playback. Anchoring on it would
         * jump the position to the end of the film and mark it watched.
         */
        fun isMetadataRead(byteOffset: Long): Boolean {
            val size = fileSize ?: return false
            return byteOffset > size - TAIL_GUARD_BYTES
        }

        /** Current position: reported when the player tells us, estimated otherwise. */
        fun positionMs(): Long {
            reportedPositionMs?.let { return it }
            // An in-app player reports its own position. Until it has, nothing is
            // known to be playing, and running the clock anyway is how an episode
            // nobody watched ends up marked watched: the session sits open, the
            // estimate walks past 90% of the runtime, and the resume point is
            // cleared. Estimation is only for players that cannot report.
            if (!isExternal) return 0
            val elapsed = if (paused) 0 else System.currentTimeMillis() - anchorWallClock
            var estimated = anchorPositionMs + elapsed
            // Playback cannot be past what the player has actually downloaded.
            if (frontierValid && streamFrontier > 0) {
                timeAtByte(streamFrontier)?.let { ceiling ->
                    estimated = min(estimated, maxOf(ceiling, anchorPositionMs))
                }
            }
            val runtime = runtimeMs
            return if (runtime != null && runtime > 0) estimated.coerceIn(0, runtime) else maxOf(0, estimated)
        }

        fun positionSource(): String = when {
            reportedPositionMs != null -> "client"
            cueIndex != null -> "cue+clock"
            else -> "clock"
        }

        companion object {
            const val TAIL_GUARD_BYTES = 8L * 1024 * 1024
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val endListeners = CopyOnWriteArrayList<(String) -> Unit>()

    private val ticker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "daview-playback-tick").apply { isDaemon = true }
    }

    init {
        ticker.scheduleWithFixedDelay({ runCatching { tick() } }, 2, 2, TimeUnit.SECONDS)
    }

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
            // be derived; hold the clock until the first byte request arrives.
            paused = player != PlayerKind.INTERNAL
        }
        sessions[id] = session

        if (session.isExternal) {
            // A platform thread, not a virtual one: Android has none, and this is
            // a single short-lived read of the container index.
            Thread({
                runCatching { session.cueIndex = streams.cueIndex(item) }
                    .onFailure { log.debug("cue index unavailable for {}", item.name) }
            }, "daview-cue-index").apply { isDaemon = true }.start()
        }
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
     *
     * The offset is not applied straight away: opening a file produces a burst
     * of probe reads (headers, then the index at the tail, then the real start),
     * and only the last one in that burst is where playback actually begins.
     */
    fun onRangeRequest(sessionId: String, rangeStart: Long) {
        val session = sessions[sessionId] ?: return
        session.lastActivity = System.currentTimeMillis()
        if (!session.isExternal) return
        if (session.isMetadataRead(rangeStart)) return
        session.pendingAnchorOffset = rangeStart
        session.pendingAnchorAt = System.currentTimeMillis()
    }

    /**
     * How far the player has read, for proxied streams. This is the *download*
     * frontier, which runs ahead of the picture by the player's buffer, so it is
     * only ever used as an upper bound — never to move the position forward.
     */
    fun onBytesRead(sessionId: String, absoluteOffset: Long) {
        val session = sessions[sessionId] ?: return
        session.lastActivity = System.currentTimeMillis()
        if (!session.isExternal) return
        if (session.isMetadataRead(absoluteOffset)) return
        if (absoluteOffset > session.streamFrontier) session.streamFrontier = absoluteOffset
        session.frontierValid = true
    }

    fun stop(sessionId: String, positionMs: Long?): Session? {
        val session = sessions.remove(sessionId) ?: return null
        if (positionMs != null && positionMs >= 0) {
            session.reportedPositionMs = positionMs
            session.anchorPositionMs = positionMs
        }
        persist(session, finished = true)
        notifyEnded(session.id)
        return session
    }

    /**
     * Told whenever a session ends, however it ended. The byte pipe listens here.
     *
     * Not every ending comes through [stop]: an external player usually cannot be
     * observed from this process at all, so the way its session normally ends is
     * the idle timeout in [tick]. Hanging the pipe off [stop] alone left the
     * socket listening for the rest of the process's life in exactly the common
     * case.
     */
    fun onSessionEnded(listener: (String) -> Unit) {
        endListeners += listener
    }

    private fun notifyEnded(sessionId: String) {
        // A listener that throws must not take the ticker thread down with it.
        endListeners.forEach { listener -> runCatching { listener(sessionId) } }
    }

    fun activeSessions(): List<SessionStateDto> = sessions.values.map { it.toDto() }

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

    /** Applies settled anchors, saves progress and retires idle sessions. */
    private fun tick() {
        val now = System.currentTimeMillis()
        val timeout = idleTimeoutSecProvider() * 1000L
        sessions.values.forEach { session ->
            if (session.isExternal) applyPendingAnchor(session, now)
            if (session.isExternal && now - session.lastActivity > timeout) {
                log.info("外部播放会话 {} 空闲超时，按 {} ms 记录进度", session.id, session.positionMs())
                sessions.remove(session.id)
                persist(session, finished = true)
                notifyEnded(session.id)
            } else if (session.isExternal) {
                persist(session, finished = false)
            }
        }
    }

    private fun applyPendingAnchor(session: Session, now: Long) {
        val offset = session.pendingAnchorOffset ?: return
        if (now - session.pendingAnchorAt < ANCHOR_SETTLE_MS) return
        session.pendingAnchorOffset = null

        val derived = session.timeAtByte(offset)
        if (derived != null) {
            val projected = session.positionMs()
            if (session.paused || abs(derived - projected) > SEEK_TOLERANCE_MS) {
                session.anchorPositionMs = derived
                session.anchorWallClock = now
                // A seek invalidates everything downloaded before it.
                session.streamFrontier = offset
                session.frontierValid = false
            }
        }
        if (session.paused) {
            session.paused = false
            session.anchorWallClock = now
        }
    }

    private fun persist(session: Session, finished: Boolean) {
        // An in-app session that never reported has no progress to write, not
        // even on the way out.
        if (!session.isExternal && session.reportedPositionMs == null) return
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
        const val MIN_PERSIST_POSITION_MS = 5_000L

        /**
         * How long a range offset has to stand unchallenged before it counts as
         * the playback position. Opening a file fires several probe reads within
         * a second or two; the last of them is the real one.
         */
        const val ANCHOR_SETTLE_MS = 3_000L
    }
}
