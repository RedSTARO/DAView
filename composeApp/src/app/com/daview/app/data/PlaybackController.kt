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
import com.daview.shared.model.SessionStateDto
import com.daview.shared.model.StreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Turns "play this" into a session plus whatever the platform can actually run:
 * the in-app player on Android, or an external player wherever PotPlayer, VLC,
 * mpv or an Android chooser exists.
 */
class PlaybackController(
    private val state: AppState,
    private val scope: CoroutineScope
) {
    var pending by mutableStateOf<MediaItemDto?>(null)
    var info by mutableStateOf<PlaybackInfoDto?>(null)
    var externalSession by mutableStateOf<SessionStateDto?>(null)
    var externalPlayerLabel by mutableStateOf<String?>(null)
    var starting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val externalPlayers: List<ExternalPlayerInfo> = availableExternalPlayers()

    private var handle: ExternalPlaybackHandle? = null

    fun playInternal(item: MediaItemDto) {
        scope.launch {
            starting = true
            error = null
            try {
                val playback = state.library.startPlayback(
                    PlaybackStartRequest(
                        itemId = item.id,
                        player = PlayerKind.INTERNAL,
                        deviceName = defaultDeviceName(),
                        // Stream through the server rather than handing the player
                        // a redirect. The storage answers a plain GET with a 302 to
                        // a signed CDN link, and ExoPlayer following that link gets
                        // a 502 from the CDN, while the very same link fetched from
                        // the core returns 206 in a second. The pipe is in this
                        // process anyway, so reading through it costs one loopback
                        // hop and buys a link that can be re-resolved when it
                        // expires mid-playback.
                        trackThroughProxy = true
                    ),
                    state.links
                )
                info = playback
                state.navigate(Screen.Player(item.id))
            } catch (e: Throwable) {
                error = e.message ?: "无法开始播放"
            } finally {
                starting = false
            }
        }
    }

    fun playExternal(item: MediaItemDto, player: ExternalPlayerInfo) {
        scope.launch {
            starting = true
            error = null
            try {
                val kind = when (player.id) {
                    "potplayer" -> PlayerKind.POTPLAYER
                    "vlc" -> PlayerKind.VLC
                    "mpv", "iina" -> PlayerKind.MPV
                    else -> PlayerKind.EXTERNAL
                }
                val playback = state.library.startPlayback(
                    PlaybackStartRequest(
                        itemId = item.id,
                        player = kind,
                        deviceName = defaultDeviceName(),
                        // Byte-level tracking is what makes progress sync work at
                        // all for players that never report anything back.
                        trackThroughProxy = true
                    ),
                    state.links
                )
                info = playback
                externalPlayerLabel = player.label

                val subtitleUrl = playback.subtitleStreamIndex
                    ?.let { playback.subtitleUrls[it] }
                    ?: playback.item.mediaStreams
                        .firstOrNull { it.type == StreamType.SUBTITLE && it.isExternal }
                        ?.let { playback.subtitleUrls[it.index] }

                handle = launchExternalPlayer(
                    ExternalPlayRequest(
                        player = player,
                        streamUrl = playback.streamUrl,
                        title = buildTitle(playback.item),
                        startPositionMs = playback.startPositionMs,
                        subtitleUrl = subtitleUrl
                    )
                )
                if (handle == null && player.id != "copy") {
                    error = "无法启动 ${player.label}"
                }
                followExternalSession(playback.sessionId)
            } catch (e: Throwable) {
                error = e.message ?: "无法开始播放"
            } finally {
                starting = false
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
            while (isActive) {
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
            externalSession = null
            externalPlayerLabel = null
            handle = null
            state.refreshHome()
            state.detailItem?.let { state.loadDetail(it.id) }
        }
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

    fun stop(positionMs: Long) {
        val sessionId = info?.sessionId ?: return
        scope.launch {
            runCatching { state.library.stopPlayback(PlaybackStopRequest(sessionId, positionMs)) }
            info = null
            state.refreshHome()
        }
    }

    fun stopExternal() {
        handle?.stop()
        val sessionId = info?.sessionId ?: return
        scope.launch { runCatching { state.library.stopPlayback(PlaybackStopRequest(sessionId, -1)) } }
    }

    val canUseInternalPlayer: Boolean get() = PlatformInfo.hasInternalPlayer

    /**
     * Plays with whatever this device actually has, and says so when it has
     * nothing.
     *
     * The in-app player is no longer a fact of the platform — on the desktop it
     * depends on libmpv being found at run time — so "no player at all" is a
     * state a user can be in, and it used to be expressed as the play button
     * doing nothing whatsoever.
     */
    fun playAnyhow(item: MediaItemDto) {
        if (canUseInternalPlayer) {
            playInternal(item)
            return
        }
        val player = externalPlayers.firstOrNull { it.executablePath != null || it.viaUrlScheme }
        if (player == null) {
            error = "没有可用的播放器：内置播放器不可用，也没有找到 PotPlayer / VLC / mpv。" +
                "可以在设置里指定 libmpv 或自定义播放器。"
            // The detail page shows `error`; the home page and the context menu
            // do not, and they are two of the three places this is reached from.
            state.toast = error
            return
        }
        playExternal(item, player)
    }
}
