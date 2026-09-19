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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
class PlaybackController(
    private val state: AppState,
    private val scope: CoroutineScope
) {
    var info by mutableStateOf<PlaybackInfoDto?>(null)
    var externalSession by mutableStateOf<SessionStateDto?>(null)
    var externalPlayerLabel by mutableStateOf<String?>(null)

    /** True from pressing play until the player is on screen or has failed. */
    var starting by mutableStateOf(false)
        private set

    /** What [starting] is for, so the overlay can name it. */
    var startingName by mutableStateOf<String?>(null)
        private set

    /**
     * The last failure and the item it belongs to. It used to be one global
     * string, printed under the buttons of whatever detail page was open next.
     */
    var error by mutableStateOf<Pair<String, String>?>(null)
        private set

    fun errorFor(itemId: String): String? = error?.takeIf { it.first == itemId }?.second

    /** Asked before playing something part-watched, when the viewer wants to be. */
    var resumePrompt by mutableStateOf<MediaItemDto?>(null)
        private set

    var upNext by mutableStateOf<UpNext?>(null)
        private set

    /**
     * Set while one session hands over to the next. The player that just
     * finished leaves the screen in that window, and leaving must not read as
     * the viewer closing it.
     */
    private var handingOver = false

    /** Episodes that rolled in by themselves since someone last chose one. */
    private var unattended = 0

    val externalPlayers: List<ExternalPlayerInfo> = availableExternalPlayers()

    private var handle: ExternalPlaybackHandle? = null
    private var startJob: Job? = null

    init {
        // The navigation bars can take the user off the player screen, and only
        // this class knows how to shut the engine down.
        state.leavingPlayer = { stopWithoutLeaving() }
    }

    /**
     * Ends an in-app session, leaving where to go next to whoever asked. An
     * external player is left running: moving to another tab while PotPlayer
     * plays is browsing, not a request to kill the film.
     */
    private fun stopWithoutLeaving() {
        val current = info ?: return
        if (externalPlayerLabel != null) return
        info = null
        upNext = null
        handingOver = false
        scope.launch {
            runCatching { state.library.stopPlayback(PlaybackStopRequest(current.sessionId, -1)) }
            state.refreshHome()
            runCatching { state.library.syncAfterPlayback() }
        }
    }

    // ------------------------------------------------------------ starting

    /**
     * Plays with whatever this device actually has, and says so when it has
     * nothing.
     *
     * The in-app player is no longer a fact of the platform — on the desktop it
     * depends on libmpv being found at run time — so "no player at all" is a
     * state a user can be in, and it used to be expressed as the play button
     * doing nothing whatsoever.
     */
    fun play(item: MediaItemDto, startPositionMs: Long? = null) {
        if (starting) return
        // Part-watched, and the viewer wants to be asked: the prompt decides.
        if (startPositionMs == null && item.isPlayable && item.userData.positionMs > 0 &&
            state.resumeBehavior == ResumeBehavior.ASK
        ) {
            resumePrompt = item
            return
        }
        unattended = 0
        if (canUseInternalPlayer) {
            playInternal(item, startPositionMs = startPositionMs)
            return
        }
        val usable = externalPlayers.filter { it.executablePath != null || it.viaUrlScheme }
        // The remembered choice first, then whatever detection turned up.
        val player = usable.firstOrNull { it.id == state.preferredPlayerId } ?: usable.firstOrNull()
        if (player == null) {
            fail(
                item.id,
                "没有可用的播放器：内置播放器不可用，也没有找到 PotPlayer / VLC / mpv。" +
                    "可以在设置里指定 libmpv 或自定义播放器。"
            )
            return
        }
        playExternal(item, player, startPositionMs = startPositionMs)
    }

    fun answerResumePrompt(fromStart: Boolean) {
        val item = resumePrompt ?: return
        resumePrompt = null
        play(item, startPositionMs = if (fromStart) 0L else item.userData.positionMs)
    }

    fun dismissResumePrompt() {
        resumePrompt = null
    }

    /** Stops waiting for a player that is taking too long to start. */
    fun cancelStart() {
        startJob?.cancel()
        startJob = null
        starting = false
        startingName = null
    }

    /**
     * A failure the viewer has to hear about wherever they are. The detail page
     * also shows it under its buttons, but only on the item it belongs to.
     */
    private fun fail(itemId: String, message: String) {
        error = itemId to message
        state.notify(message)
    }

    private fun startRequest(item: MediaItemDto, player: PlayerKind, startPositionMs: Long?) = PlaybackStartRequest(
        itemId = item.id,
        player = player,
        deviceName = defaultDeviceName(),
        // Stream through the core rather than hand the player a redirect: the
        // storage answers a plain GET with a 302 to a signed CDN link, which
        // ExoPlayer gets a 502 for, while the same link fetched from the core
        // returns 206. Reading through the pipe also lets the link be resolved
        // again when it expires mid-film, and is how an external player's
        // position is followed at all.
        trackThroughProxy = true,
        startPositionMs = startPositionMs,
        preferredAudioLanguage = state.preferredAudioLanguage,
        preferredSubtitleLanguage = state.preferredSubtitleLanguage
    )

    /**
     * [replaceScreen] is for rolling from one episode into the next: the player
     * screen is already on top of the stack, so pushing another would mean the
     * back arrow walked out through every episode watched that evening.
     */
    fun playInternal(
        item: MediaItemDto,
        replaceScreen: Boolean = false,
        /** Null carries on from the resume point; 0 is "start it again". */
        startPositionMs: Long? = null
    ) {
        startJob?.cancel()
        starting = true
        startingName = item.seriesName?.let { "$it · ${item.episodeLabel ?: item.name}" } ?: item.name
        error = null
        startJob = scope.launch {
            try {
                val playback = state.library.startPlayback(
                    startRequest(item, PlayerKind.INTERNAL, startPositionMs),
                    state.links
                )
                info = playback
                upNext = null
                handingOver = false
                if (!replaceScreen) state.navigate(Screen.Player(item.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handingOver = false
                upNext = null
                fail(item.id, "无法开始播放：${state.describe(e)}")
                // A roll-over that failed leaves nothing to show on the player
                // screen; the page underneath is the one to go back to.
                if (replaceScreen && state.current is Screen.Player) {
                    info = null
                    state.back()
                }
            } finally {
                starting = false
                startingName = null
            }
        }
    }

    fun playExternal(
        item: MediaItemDto,
        player: ExternalPlayerInfo,
        startPositionMs: Long? = null
    ) {
        if (starting) return
        startJob?.cancel()
        starting = true
        startingName = item.name
        error = null
        startJob = scope.launch {
            try {
                val kind = when (player.id) {
                    "potplayer" -> PlayerKind.POTPLAYER
                    "vlc" -> PlayerKind.VLC
                    "mpv", "iina" -> PlayerKind.MPV
                    else -> PlayerKind.EXTERNAL
                }
                val playback = state.library.startPlayback(startRequest(item, kind, startPositionMs), state.links)
                info = playback
                externalPlayerLabel = player.label
                // The panel that shows what is playing, where it has got to and
                // how to stop it lives on the player screen.
                state.navigate(Screen.Player(item.id))

                val chosenSubtitle = playback.subtitleStreamIndex
                val subtitleUrl = when (chosenSubtitle) {
                    SUBTITLE_OFF -> null
                    null -> playback.item.mediaStreams
                        .firstOrNull { it.type == StreamType.SUBTITLE && it.isExternal }
                        ?.let { playback.subtitleUrls[it.index] }
                    else -> playback.subtitleUrls[chosenSubtitle]
                }

                handle = launchExternalPlayer(
                    ExternalPlayRequest(
                        player = player,
                        streamUrl = playback.streamUrl,
                        title = buildTitle(playback.item),
                        startPositionMs = playback.startPositionMs,
                        subtitleUrl = subtitleUrl,
                        // The track the viewer chose on another device, or the
                        // one the core picked. It was computed and stored and
                        // then not passed on, so an external player fell back to
                        // its own default — usually the wrong language.
                        audioTrack = playback.audioStreamIndex,
                        subtitleTrack = chosenSubtitle
                    )
                )
                if (handle == null && player.id != "copy") {
                    fail(item.id, "无法启动 ${player.label}")
                }
                followExternalSession(playback.sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(item.id, "无法开始播放：${state.describe(e)}")
            } finally {
                starting = false
                startingName = null
            }
        }
    }

    private fun buildTitle(item: MediaItemDto): String = buildString {
        item.seriesName?.let { append(it).append(" · ") }
        item.episodeLabel?.let { append(it).append(' ') }
        append(item.name)
    }

    /**
     * Follows the session while an external player runs. The position it reports
     * is derived from the byte ranges the player asks the pipe for, so the panel
     * shows it as an estimate.
     */
    private fun followExternalSession(sessionId: String) {
        scope.launch {
            val watcher = handle
            ActivePlayback.externalRunning = true
            while (isActive) {
                // While the process is alive the session is wanted, whether
                // or not the player is currently reading bytes: pausing for
                // longer than the idle timeout used to retire it and close the
                // pipe underneath a film that was still open.
                if (watcher == null || !watcher.canObserveExit || watcher.isRunning()) {
                    runCatching { state.library.keepSessionAlive(sessionId) }
                }
                val session = runCatching { state.library.sessions() }.getOrNull().orEmpty()
                    .firstOrNull { it.sessionId == sessionId }
                externalSession = session
                if (watcher != null && watcher.canObserveExit && !watcher.isRunning()) {
                    // Let the session settle on its own estimate rather than
                    // guessing a position the player never told us.
                    runCatching { state.library.stopPlayback(PlaybackStopRequest(sessionId, -1)) }
                    break
                }
                if (session == null && watcher?.canObserveExit != true) break
                delay(3000)
            }
            ActivePlayback.externalRunning = false
            externalSession = null
            externalPlayerLabel = null
            handle = null
            if (info?.sessionId == sessionId) info = null
            // The player has gone; the panel describing it should go too.
            if (state.current is Screen.Player) state.back()
            state.refreshHome()
            state.detailItem?.let { state.loadDetail(it.id, quiet = true) }
            runCatching { state.library.syncAfterPlayback() }
        }
    }

    /** Leaves the external panel while the player keeps going. */
    fun leaveExternalPanel() {
        if (state.current is Screen.Player) state.back()
    }

    /** Goes back to the panel of the external player that is still running. */
    fun showExternalPanel() {
        val playing = info ?: return
        if (state.current !is Screen.Player) state.navigate(Screen.Player(playing.item.id))
    }

    fun reportProgress(positionMs: Long, paused: Boolean, audio: Int?, subtitle: Int?) {
        val sessionId = info?.sessionId ?: return
        scope.launch {
            runCatching {
                state.library.reportProgress(
                    PlaybackProgressRequest(sessionId, positionMs, paused, audio, subtitle)
                )
            }
        }
    }

    // ------------------------------------------------------------ ending

    /**
     * A file ran to its end. With another episode to follow, the screen shows
     * what is next and counts down — the viewer can start it at once or stop
     * there — instead of cutting straight into it. After three episodes in a
     * row that nobody touched, it waits to be asked.
     */
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
            itemId = nextId,
            name = current.nextItemName ?: "下一集",
            startsAt = if (stillWatching) null else System.currentTimeMillis() + UP_NEXT_DELAY_MS,
            stillWatching = stillWatching
        )
        scope.launch {
            runCatching { state.library.stopPlayback(PlaybackStopRequest(sessionId, positionMs)) }
        }
    }

    /** Starts the episode the up-next card is showing. */
    fun playUpNext(auto: Boolean) {
        val next = upNext ?: return
        if (auto) unattended++ else unattended = 0
        skipTo(next.itemId, fromStart = false)
    }

    /** Declines the next episode and leaves the player. */
    fun cancelUpNext() {
        upNext = null
        handingOver = false
        info = null
        if (state.current is Screen.Player) state.back()
        scope.launch {
            state.refreshHome()
            runCatching { state.library.syncAfterPlayback() }
        }
    }

    /**
     * Moves the player to another episode — the next one, the one before, or
     * whatever the up-next card offered. The player on screen hands over rather
     * than closes: its session is stopped where it stood, and the screen stays.
     */
    fun skipTo(itemId: String, fromStart: Boolean = true, positionMs: Long? = null) {
        val current = info
        handingOver = true
        scope.launch {
            if (current != null && upNext == null) {
                runCatching {
                    state.library.stopPlayback(PlaybackStopRequest(current.sessionId, positionMs ?: -1))
                }
            }
            val item = runCatching { state.library.item(itemId, state.links) }.getOrNull()
            if (item == null) {
                handingOver = false
                upNext = null
                info = null
                if (state.current is Screen.Player) state.back()
                return@launch
            }
            playInternal(item, replaceScreen = true, startPositionMs = if (fromStart) 0L else null)
        }
    }

    /**
     * What a player reports as it leaves the screen, with the session it was
     * playing. Leaving because the viewer closed it ends the session and goes
     * back a page; leaving because a newer session took its place, or during a
     * hand-over to the next episode, only records where it stopped.
     *
     * The player used to call "stop and go back" on the way out whatever the
     * reason, so leaving it by the back gesture — which had already gone back
     * — went back a second time and landed a page too far.
     */
    fun closePlayer(sessionId: String, positionMs: Long) {
        val current = info
        if (current == null || current.sessionId != sessionId || handingOver) {
            scope.launch {
                runCatching {
                    state.library.stopPlayback(PlaybackStopRequest(sessionId, positionMs.takeIf { it > 0 } ?: -1))
                }
            }
            return
        }
        info = null
        if (state.current is Screen.Player) state.back()
        scope.launch {
            runCatching { state.library.stopPlayback(PlaybackStopRequest(sessionId, positionMs)) }
            state.refreshHome()
            state.detailItem?.let { state.loadDetail(it.id, quiet = true) }
            // Straight away, rather than waiting for the periodic upload: this
            // is the moment the other device wants.
            runCatching { state.library.syncAfterPlayback() }
        }
    }

    /**
     * Ends playback and leaves the player screen, in that order — the back
     * gesture, and leaving from the keyboard.
     */
    fun stopAndLeave() {
        val current = info
        if (current == null || upNext != null) {
            if (upNext != null) cancelUpNext() else state.back()
            return
        }
        if (externalPlayerLabel != null) {
            leaveExternalPanel()
            return
        }
        // Cleared first so the player composable leaves the tree and releases
        // the engine before anything else happens. The player then reports its
        // own last position through closePlayer, which finds the session gone
        // from info and only records it.
        info = null
        state.back()
        scope.launch {
            runCatching { state.library.stopPlayback(PlaybackStopRequest(current.sessionId, -1)) }
            state.refreshHome()
            runCatching { state.library.syncAfterPlayback() }
        }
    }

    /** Ends the external player, which is what 「结束播放」 on its panel asks for. */
    fun stopExternal() {
        handle?.stop()
        val sessionId = info?.sessionId ?: return
        scope.launch { runCatching { state.library.stopPlayback(PlaybackStopRequest(sessionId, -1)) } }
    }

    val canUseInternalPlayer: Boolean get() = PlatformInfo.hasInternalPlayer

    private companion object {
        const val UP_NEXT_DELAY_MS = 8_000L
        const val STILL_WATCHING_AFTER = 3
    }
}
