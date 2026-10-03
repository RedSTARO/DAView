package com.daview.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.daview.app.platform.ExternalPlayRequest
import com.daview.app.platform.ExternalPlaybackHandle
import com.daview.app.platform.ExternalPlayerInfo
import com.daview.app.platform.PlatformInfo
import com.daview.app.platform.availableExternalPlayers
import com.daview.app.platform.defaultDeviceName
import com.daview.app.platform.launchExternalPlayer
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlaybackInfoDto
import com.daview.shared.model.PlaybackProgressRequest
import com.daview.shared.model.PlaybackStartRequest
import com.daview.shared.model.PlaybackStopRequest
import com.daview.shared.model.PlayerKind
import com.daview.shared.model.SUBTITLE_OFF
import com.daview.shared.model.SessionStateDto
import com.daview.shared.model.StreamType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/** Only playback I/O and platform launch are replaceable; the controller owns state. */
internal interface PlaybackBackend {
    val externalPlayers: List<ExternalPlayerInfo>
    val hasInternalPlayer: Boolean
    val deviceName: String
    suspend fun item(id: String): MediaItemDto
    suspend fun nextEpisode(id: String): MediaItemDto?
    suspend fun start(request: PlaybackStartRequest): PlaybackInfoDto
    suspend fun stop(request: PlaybackStopRequest)
    suspend fun report(request: PlaybackProgressRequest)
    suspend fun keepAlive(sessionId: String)
    suspend fun sessions(): List<SessionStateDto>
    suspend fun sync()
    fun launch(request: ExternalPlayRequest): ExternalPlaybackHandle?
}

private class LibraryPlaybackBackend(private val state: AppState) : PlaybackBackend {
    override val externalPlayers = availableExternalPlayers()
    override val hasInternalPlayer get() = PlatformInfo.hasInternalPlayer
    override val deviceName get() = defaultDeviceName()
    override suspend fun item(id: String) = state.library.item(id, state.links)
    override suspend fun nextEpisode(id: String) = state.library.nextEpisode(id, state.links)
    override suspend fun start(request: PlaybackStartRequest) = state.library.startPlayback(request, state.links)
    override suspend fun stop(request: PlaybackStopRequest) = state.library.stopPlayback(request)
    override suspend fun report(request: PlaybackProgressRequest) { state.library.reportProgress(request) }
    override suspend fun keepAlive(sessionId: String) = state.library.keepSessionAlive(sessionId)
    override suspend fun sessions() = state.library.sessions()
    override suspend fun sync() = state.library.syncAfterPlayback()
    override fun launch(request: ExternalPlayRequest) = launchExternalPlayer(request)
}

/** The episode waiting to roll in once the one on screen has finished. */
data class UpNext(
    val itemId: String,
    val name: String,
    /** When it starts by itself; null when it waits to be asked. */
    val startsAt: Long?,
    /** Several episodes have rolled in untouched: ask before carrying on. */
    val stillWatching: Boolean
)

/**
 * Turns "play this" into a session plus whatever the platform can actually run:
 * the in-app player on Android, or an external player wherever PotPlayer, VLC,
 * mpv or an Android chooser exists.
 */
class PlaybackController internal constructor(
    private val state: AppState,
    private val scope: CoroutineScope,
    private val backend: PlaybackBackend,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cleanupTimeoutMs: Long = 10_000L
) {
    constructor(state: AppState, scope: CoroutineScope) : this(state, scope, LibraryPlaybackBackend(state))

    var info by mutableStateOf<PlaybackInfoDto?>(null)
    var externalSession by mutableStateOf<SessionStateDto?>(null)
    var externalPlayerLabel by mutableStateOf<String?>(null)
    var starting by mutableStateOf(false)
        private set
    var startingName by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<Pair<String, String>?>(null)
        private set
    fun errorFor(itemId: String): String? = error?.takeIf { it.first == itemId }?.second
    var resumePrompt by mutableStateOf<MediaItemDto?>(null)
        private set
    var upNext by mutableStateOf<UpNext?>(null)
        private set

    private var handingOver = false
    private var unattended = 0
    val externalPlayers = backend.externalPlayers
    val canUseInternalPlayer: Boolean get() = backend.hasInternalPlayer
    private var startJob: Job? = null
    private var startGeneration = 0L
    private var externalJob: Job? = null

    private class ExternalSession(val playback: PlaybackInfoDto, val handle: ExternalPlaybackHandle?) {
        val released = AtomicBoolean(false)
    }
    private var externalOwner: ExternalSession? = null
    // closePlayer can also arrive after the composition's scope was cancelled.
    private val internalSessions = ConcurrentHashMap<String, AtomicBoolean>()
    private val log = Logger.getLogger(PlaybackController::class.java.name)

    init {
        state.leavingPlayer = { stopWithoutLeaving() }
    }

    /** Cleanup has its own finite lifetime, independent of a disposed UI scope. */
    private fun cleanup(sessionId: String, positionMs: Long, handle: ExternalPlaybackHandle? = null): Job =
        CoroutineScope(cleanupDispatcher).launch {
            try {
                val stopped = withTimeoutOrNull(cleanupTimeoutMs) {
                    if (handle != null) {
                        catchingOperation { handle.stop() }.onFailure {
                            log.log(Level.WARNING, "Could not close external player for $sessionId", it)
                        }
                    }
                    backend.stop(PlaybackStopRequest(sessionId, positionMs))
                    true
                }
                if (stopped != true) {
                    log.warning("Timed out stopping playback session $sessionId")
                    return@launch
                }
                // Read the shelves after the final progress write, not before
                // disposal. This UI refresh is optional when the UI has gone.
                scope.launch { refreshAfterStop() }
                if (withTimeoutOrNull(cleanupTimeoutMs) { backend.sync(); true } != true) {
                    log.warning("Timed out syncing playback session $sessionId")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "Could not finish playback session $sessionId", e)
            }
        }

    private fun finishInternal(sessionId: String, positionMs: Long) {
        val closed = internalSessions[sessionId] ?: return
        if (!closed.compareAndSet(false, true)) return
        cleanup(sessionId, positionMs.coerceAtLeast(-1)).invokeOnCompletion {
            internalSessions.remove(sessionId, closed)
        }
    }

    private fun releaseExternal(owner: ExternalSession) {
        if (owner.released.compareAndSet(false, true)) cleanup(owner.playback.sessionId, -1, owner.handle)
    }

    private fun refreshAfterStop() {
        if (!scope.isActive || !state.ready) return
        state.refreshHome()
        (state.current as? Screen.Detail)?.let { state.loadDetail(it.itemId, quiet = true) }
    }

    private fun stopWithoutLeaving() {
        cancelStart()
        if (externalOwner != null) return
        // Removing the engine makes both platforms call closePlayer with its
        // final position. Stopping here with -1 would delete that session first.
        info = null
        upNext = null
        handingOver = false
    }

    fun play(item: MediaItemDto, startPositionMs: Long? = null) {
        if (starting || !scope.isActive) return
        unattended = 0
        if (startPositionMs == null && state.resumeBehavior == ResumeBehavior.ASK) {
            if (!item.isPlayable) {
                launchStart(item.id, item.name) { generation ->
                    val episode = backend.nextEpisode(item.id)
                    ensureRequest(generation)
                    if (episode != null && episode.userData.positionMs > 0) resumePrompt = episode
                    else startChosen(item, null, generation)
                }
                return
            }
            if (item.userData.positionMs > 0) {
                resumePrompt = item
                return
            }
        }
        launchStart(item.id, item.name) { startChosen(item, startPositionMs, it) }
    }

    private suspend fun startChosen(item: MediaItemDto, position: Long?, generation: Long) {
        if (canUseInternalPlayer) {
            acquire(item, position, generation)
        } else {
            val usable = externalPlayers.filter { it.executablePath != null || it.viaUrlScheme }
            val player = usable.firstOrNull { it.id == state.preferredPlayerId } ?: usable.firstOrNull()
                ?: error("没有可用的播放器：内置播放器不可用，也没有找到 PotPlayer / VLC / mpv。可以在设置里指定 libmpv 或自定义播放器。")
            acquire(item, position, generation, player = player)
        }
    }

    fun answerResumePrompt(fromStart: Boolean) {
        val item = resumePrompt ?: return
        resumePrompt = null
        play(item, if (fromStart) 0L else item.userData.positionMs)
    }

    fun dismissResumePrompt() { resumePrompt = null }

    fun cancelStart() {
        ++startGeneration
        startJob?.cancel()
        startJob = null
        starting = false
        startingName = null
        resumePrompt = null
    }

    private fun fail(itemId: String, message: String) {
        error = itemId to message
        state.notify(message)
    }

    private fun launchStart(
        itemId: String,
        name: String,
        replaceEntry: Long? = null,
        block: suspend (Long) -> Unit
    ) {
        if (starting || !scope.isActive) return
        cancelStart()
        val generation = startGeneration
        starting = true
        startingName = name
        error = null
        // Every entry point, including the external-player menu, retires the
        // previous external owner before acquiring a replacement.
        endExternalNow()
        startJob = scope.launch {
            try {
                block(generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == startGeneration && isActive) {
                    handingOver = false
                    upNext = null
                    fail(itemId, "无法开始播放：${state.describe(e)}")
                    if (replaceEntry != null && state.currentEntry.key == replaceEntry) {
                        info = null
                        state.back()
                    }
                }
            } finally {
                // A cancelled, slow request must not dismiss its successor's overlay.
                if (generation == startGeneration) {
                    starting = false
                    startingName = null
                }
            }
        }
    }

    private suspend fun ensureRequest(generation: Long, replaceEntry: Long? = null) {
        currentCoroutineContext().ensureActive()
        if (!scope.isActive || generation != startGeneration ||
            (replaceEntry != null && state.currentEntry.key != replaceEntry)
        ) throw CancellationException("Playback request was superseded")
    }

    private fun startRequest(item: MediaItemDto, kind: PlayerKind, position: Long?) = PlaybackStartRequest(
        itemId = item.id, player = kind, deviceName = backend.deviceName,
        trackThroughProxy = true, startPositionMs = position,
        preferredAudioLanguage = state.preferredAudioLanguage,
        preferredSubtitleLanguage = state.preferredSubtitleLanguage
    )

    fun playInternal(item: MediaItemDto, replaceScreen: Boolean = false, startPositionMs: Long? = null) {
        val entry = if (replaceScreen) state.currentEntry.key else null
        launchStart(item.id, item.name, entry) { acquire(item, startPositionMs, it, replaceEntry = entry) }
    }

    fun playExternal(item: MediaItemDto, player: ExternalPlayerInfo, startPositionMs: Long? = null) {
        launchStart(item.id, item.name) { acquire(item, startPositionMs, it, player = player) }
    }

    private suspend fun acquire(
        item: MediaItemDto,
        position: Long?,
        generation: Long,
        player: ExternalPlayerInfo? = null,
        replaceEntry: Long? = null
    ) {
        ensureRequest(generation, replaceEntry)
        val kind = when (player?.id) {
            null -> PlayerKind.INTERNAL
            "potplayer" -> PlayerKind.POTPLAYER
            "vlc" -> PlayerKind.VLC
            "mpv", "iina" -> PlayerKind.MPV
            else -> PlayerKind.EXTERNAL
        }
        var created: PlaybackInfoDto? = null
        var launched: ExternalPlaybackHandle? = null
        var adopted = false
        try {
            // The facade creates the session inside withContext(IO). Preserve
            // its return value even if the UI cancels during acquisition, so
            // this request can release exactly the resource it just acquired.
            withContext(NonCancellable) {
                created = backend.start(startRequest(item, kind, position))
            }
            ensureRequest(generation, replaceEntry)
            val playback = checkNotNull(created)
            if (player != null) {
                val chosen = playback.subtitleStreamIndex
                val subtitleUrl = when (chosen) {
                    SUBTITLE_OFF -> null
                    null -> playback.item.mediaStreams.firstOrNull { it.type == StreamType.SUBTITLE && it.isExternal }
                        ?.let { playback.subtitleUrls[it.index] }
                    else -> playback.subtitleUrls[chosen]
                }
                launched = backend.launch(
                    ExternalPlayRequest(
                        player, playback.streamUrl, buildTitle(playback.item), playback.startPositionMs,
                        subtitleUrl, playback.audioStreamIndex, chosen, playback.item.mediaStreams
                    )
                )
                if (launched == null && player.id != "copy") error("无法启动 ${player.label}")
                ensureRequest(generation, replaceEntry)
                val owner = ExternalSession(playback, launched)
                externalOwner = owner
                externalSession = null
                externalPlayerLabel = player.label
                info = playback
                ActivePlayback.externalRunning = true
                adopted = true
                state.navigate(Screen.Player(item.id))
                followExternalSession(owner)
            } else {
                internalSessions[playback.sessionId] = AtomicBoolean(false)
                info = playback
                upNext = null
                handingOver = false
                adopted = true
                if (replaceEntry == null) state.navigate(Screen.Player(item.id))
            }
        } finally {
            if (!adopted) created?.let { cleanup(it.sessionId, -1, launched) }
        }
    }

    private fun buildTitle(item: MediaItemDto): String = buildString {
        item.seriesName?.let { append(it).append(" · ") }
        item.episodeLabel?.let { append(it).append(' ') }
        append(item.name)
    }

    private fun followExternalSession(owner: ExternalSession) {
        // Install the finally before returning the owner to the UI, including
        // when this scope is cancelled before a dispatched watcher would run.
        externalJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val sessionId = owner.playback.sessionId
            val watcher = owner.handle
            try {
                while (isActive && externalOwner === owner) {
                    if (watcher == null || !watcher.canObserveExit || watcher.isRunning()) {
                        catchingOperation { backend.keepAlive(sessionId) }
                    }
                    val sessions = catchingOperation { backend.sessions() }
                    currentCoroutineContext().ensureActive()
                    if (externalOwner !== owner) return@launch
                    // A failed poll is not evidence that the player has stopped.
                    if (sessions.isSuccess) {
                        externalSession = sessions.getOrThrow().firstOrNull { it.sessionId == sessionId }
                    }
                    if (watcher != null && watcher.canObserveExit && !watcher.isRunning()) break
                    if (sessions.isSuccess && externalSession == null && watcher?.canObserveExit != true) break
                    delay(3000)
                }
            } finally {
                if (externalOwner === owner) {
                    clearExternal()
                    if (scope.isActive && state.current is Screen.Player) state.back()
                    refreshAfterStop()
                }
                releaseExternal(owner)
            }
        }
    }

    private fun clearExternal() {
        externalOwner = null
        externalSession = null
        externalPlayerLabel = null
        info = null
        ActivePlayback.externalRunning = false
    }

    val canBrowseDuringExternal: Boolean get() = externalOwner?.handle?.canObserveExit == true

    fun leaveExternalPanel() {
        if (!canBrowseDuringExternal) endExternalNow()
        if (state.current is Screen.Player) state.back()
    }

    private fun endExternalNow() {
        val owner = externalOwner ?: return
        clearExternal()
        externalJob?.cancel()
        externalJob = null
        releaseExternal(owner)
    }

    fun showExternalPanel() {
        val playing = externalOwner?.playback ?: return
        if (state.current !is Screen.Player) state.navigate(Screen.Player(playing.item.id))
    }

    fun reportProgress(positionMs: Long, paused: Boolean, audio: Int?, subtitle: Int?) {
        val sessionId = info?.sessionId ?: return
        scope.launch {
            catchingOperation { backend.report(PlaybackProgressRequest(sessionId, positionMs, paused, audio, subtitle)) }
        }
    }

    fun onEnded(sessionId: String, positionMs: Long) {
        val current = info ?: return
        if (current.sessionId != sessionId) return
        val nextId = current.nextItemId
        if (nextId == null || !state.autoPlayNext) {
            closePlayer(sessionId, positionMs)
            return
        }
        handingOver = true
        val stillWatching = unattended >= STILL_WATCHING_AFTER
        upNext = UpNext(
            nextId, current.nextItemName ?: "下一集",
            if (stillWatching) null else System.currentTimeMillis() + UP_NEXT_DELAY_MS, stillWatching
        )
        finishInternal(sessionId, positionMs)
    }

    fun playUpNext(auto: Boolean) {
        if (starting) return
        val next = upNext ?: return
        if (auto) unattended++ else unattended = 0
        skipTo(next.itemId, fromStart = false, manual = false)
    }

    fun cancelUpNext() {
        cancelStart()
        upNext = null
        handingOver = false
        info = null
        if (state.current is Screen.Player) state.back()
        refreshAfterStop()
    }

    fun skipTo(itemId: String, fromStart: Boolean = true, positionMs: Long? = null, manual: Boolean = true) {
        if (starting || state.current !is Screen.Player) return
        if (manual) unattended = 0
        val previous = info
        val entry = state.currentEntry.key
        handingOver = true
        launchStart(itemId, upNext?.name ?: "下一集", entry) { generation ->
            if (previous != null && positionMs != null) finishInternal(previous.sessionId, positionMs)
            val item = backend.item(itemId)
            ensureRequest(generation, entry)
            startingName = item.name
            acquire(item, if (fromStart) 0L else null, generation, replaceEntry = entry)
        }
    }

    /**
     * Both engines report their final position on dispose. Android may also
     * report it from its error UI; only the first close owns stop and sync.
     * No coroutine is launched into the already-disposing composition here.
     */
    fun closePlayer(sessionId: String, positionMs: Long) {
        finishInternal(sessionId, positionMs)
        val current = info
        if (current == null || current.sessionId != sessionId || handingOver) return
        cancelStart()
        info = null
        if (scope.isActive && state.current is Screen.Player) state.back()
        refreshAfterStop()
    }

    fun stopAndLeave() {
        cancelStart()
        if (externalOwner != null) {
            leaveExternalPanel()
            return
        }
        upNext = null
        handingOver = false
        // The actual stop belongs to closePlayer, after the engine has supplied
        // its final position. In particular, seeking then Back must not use the
        // last periodic report or a guessed delay to win a race with dispose.
        info = null
        if (state.current is Screen.Player) state.back()
    }

    fun stopExternal() {
        cancelStart()
        endExternalNow()
        refreshAfterStop()
    }

    private companion object {
        const val UP_NEXT_DELAY_MS = 8_000L
        const val STILL_WATCHING_AFTER = 3
    }
}
