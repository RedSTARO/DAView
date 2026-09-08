package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daview.app.player.EnhancementLog
import com.daview.app.player.EnhancementState
import com.daview.app.player.MpvNative
import com.daview.app.player.MpvPlayer
import com.daview.app.player.PlayerPreferences
import com.daview.app.player.TrackMapping
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.PlaybackInfoDto
import com.daview.shared.model.StreamType
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.delay
import java.awt.Canvas
import java.awt.Color as AwtColor
import java.util.concurrent.atomic.AtomicBoolean

/** How a session ended; the two write different things back. */
private enum class Ending { FINISHED, ABANDONED }

/**
 * In-app playback on the desktop, backed by libmpv.
 *
 * The picture is a native child window mpv owns, parented to a heavyweight AWT
 * canvas — see [MpvPlayer] for why nothing else works if the NVIDIA features
 * are to be reachable. The direct consequence is that no Compose surface can be
 * drawn over the video, so the transport controls are mpv's own on-screen
 * controller (which also brings its keyboard bindings), and what Compose draws
 * lives in a bar above the picture: the things mpv cannot know about, namely
 * DAView's own track list and the RTX switches.
 */
@Composable
actual fun InternalPlayer(
    info: PlaybackInfoDto,
    onProgress: (positionMs: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?) -> Unit,
    onClose: (positionMs: Long) -> Unit
) {
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnClose by rememberUpdatedState(onClose)

    val canvas = remember {
        Canvas().apply {
            // The frame between the window appearing and mpv's first frame is
            // this colour; the app's own background would flash white.
            background = AwtColor.BLACK
        }
    }

    var selectedAudio by remember { mutableStateOf(info.audioStreamIndex) }
    var selectedSubtitle by remember { mutableStateOf(info.subtitleStreamIndex) }
    var player by remember { mutableStateOf<MpvPlayer?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var ending by remember { mutableStateOf<Ending?>(null) }
    // Attaching subtitles and choosing tracks has to wait until mpv has a file
    // open, and mpv says so on its own thread - so the work is prepared here and
    // run from the event callback, exactly once per file.
    val configured = remember { AtomicBoolean(false) }
    var attachTracks by remember { mutableStateOf({}) }
    var superResolution by remember { mutableStateOf(EnhancementState.OFF) }
    var videoHdr by remember { mutableStateOf(EnhancementState.OFF) }
    var maxLumaGuess by remember { mutableStateOf(false) }

    // onClose both stops the session and pops the screen, and there are two
    // routes to it — the file ending and the screen going away.
    val closedOnce = remember { AtomicBoolean(false) }
    fun finish(positionMs: Long) {
        if (closedOnce.compareAndSet(false, true)) latestOnClose(positionMs)
    }

    LaunchedEffect(info.sessionId) {
        // Creation is keyed on the session while disposal is keyed on the screen,
        // so a second session arriving at the same screen would otherwise
        // overwrite the reference and strand the first engine — window, decoder
        // and event thread included.
        player?.close()
        player = null

        val enhancement = PlayerPreferences.enhancement
        // Only where the filter exists. Elsewhere applyEnhancement is a no-op,
        // and a chip left saying "waiting" forever would be a worse answer than
        // saying nothing.
        if (MpvNative.isWindows) {
            if (enhancement.superResolution) superResolution = EnhancementState.REQUESTED
            if (enhancement.videoHdr) videoHdr = EnhancementState.REQUESTED
        }

        // JNA can only read a component's native handle once the peer exists,
        // which is after Swing has added and laid the canvas out.
        val handle = awaitNativeHandle(canvas)
        if (handle == null) {
            failure = "无法获取视频窗口句柄"
            return@LaunchedEffect
        }

        val created = runCatching {
            MpvPlayer(object : MpvPlayer.Listener {
                override fun onLog(prefix: String, level: String, text: String) {
                    EnhancementLog.superResolution(text)?.let { superResolution = it }
                    EnhancementLog.videoHdr(text)?.let { videoHdr = it }
                    if (EnhancementLog.isMaxLumaGuess(text)) maxLumaGuess = true
                }

                // Everything a file needs before it can be configured happens
                // here rather than after `loadfile`. `sub-add` and track ids
                // apply to the file mpv currently has open: run before one is
                // loaded they fail with -12 and attach nothing, and loadfile is
                // asynchronous, so following it immediately is just as early.
                override fun onFileLoaded() {
                    if (configured.compareAndSet(false, true)) attachTracks()
                }

                override fun onEndFile(error: String?, reachedEnd: Boolean) {
                    // A file that could not be played must not leave the screen
                    // quietly, and must not be written back as progress.
                    when {
                        error != null -> failure = "播放失败: $error"
                        reachedEnd -> ending = Ending.FINISHED
                        // Stopped rather than finished - `q` over the video, and
                        // the viewer is three minutes in, not at the end.
                        else -> ending = Ending.ABANDONED
                    }
                }

                // mpv's own key bindings are on so its controller works, and `q`
                // over the video quits it. Without this the picture would vanish
                // and the screen would stay, on a dead surface, session open.
                override fun onShutdown() {
                    if (ending == null) ending = Ending.ABANDONED
                }
            }).apply {
                open(handle, MpvPlayer.Config(info.startPositionMs, enhancement))
            }
        }.onFailure { failure = it.message ?: "无法启动内置播放器" }.getOrNull() ?: return@LaunchedEffect

        // Published before it is configured, because the only thing that closes
        // it is this state: leaving the screen mid-setup would otherwise strand
        // an mpv instance holding a window and a decoder.
        player = created

        attachTracks = {
            // Every external subtitle is attached in a fixed order, because that
            // order is what TrackMapping turns DAView's indices into.
            TrackMapping.externalSubtitles(info.item.mediaStreams).forEach { stream ->
                info.subtitleUrls[stream.index]?.let { url ->
                    created.addSubtitle(url, stream.displayTitle, stream.language)
                }
            }
            // A mapping that comes back empty means "nothing is known about this
            // container", not "switch the track off" - leave mpv's own choice,
            // which is what the Android player does with an unmatched track.
            TrackMapping.audioId(info.item.mediaStreams, selectedAudio)
                ?.let { created.selectAudio(it) }
            if (selectedSubtitle == null) {
                created.disableSubtitle()
            } else {
                TrackMapping.subtitleId(info.item.mediaStreams, selectedSubtitle)
                    ?.let { created.selectSubtitle(it) }
            }
        }
        created.play(info.streamUrl)
    }

    // The session's position comes from the player, the same way the Android
    // one reports it; nothing here is derived from byte offsets.
    LaunchedEffect(player) {
        val active = player ?: return@LaunchedEffect
        while (true) {
            delay(5000)
            val position = active.positionMs ?: continue
            latestOnProgress(position, active.paused, selectedAudio, selectedSubtitle)
        }
    }

    LaunchedEffect(ending) {
        val reason = ending ?: return@LaunchedEffect
        val active = player
        // mpv answers nothing once it is shutting down, and the viewer pressing
        // `q` *is* the shutdown, so the live read is expected to come back empty
        // here. What must not happen is falling back to the runtime in that
        // case: the server reads a position at 100% as watched, which would mark
        // an episode someone abandoned three minutes in and drop its resume
        // point. Only a file that actually ran out gets the runtime.
        val position = active?.positionMs
            ?: active?.lastPosition
            ?: info.runtimeMs.takeIf { reason == Ending.FINISHED }
            ?: info.startPositionMs
        finish(position)
    }

    DisposableEffect(Unit) {
        onDispose {
            val active = player
            val position = active?.positionMs ?: active?.lastPosition ?: info.startPositionMs
            active?.close()
            finish(position)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PlayerBar(
            info = info,
            selectedAudio = selectedAudio,
            selectedSubtitle = selectedSubtitle,
            superResolution = superResolution,
            videoHdr = videoHdr,
            onAudio = { stream ->
                selectedAudio = stream.index
                TrackMapping.audioId(info.item.mediaStreams, stream.index)
                    ?.let { player?.selectAudio(it) }
                player?.positionMs?.let { latestOnProgress(it, false, selectedAudio, selectedSubtitle) }
            },
            onSubtitle = { stream ->
                selectedSubtitle = stream?.index
                if (stream == null) {
                    player?.disableSubtitle()
                } else {
                    TrackMapping.subtitleId(info.item.mediaStreams, stream.index)
                        ?.let { player?.selectSubtitle(it) }
                }
                player?.positionMs?.let { latestOnProgress(it, false, selectedAudio, selectedSubtitle) }
            },
            onClose = { finish(player?.positionMs ?: player?.lastPosition ?: info.startPositionMs) }
        )

        Box(Modifier.fillMaxSize().weight(1f)) {
            SwingPanel(
                factory = { canvas },
                modifier = Modifier.fillMaxSize()
            )
            failure?.let { message ->
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        MpvNative.loadError?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        if (maxLumaGuess) {
            Text(
                "RTX Video HDR 的峰值亮度按 1000 nits 估算，与 NVIDIA App 里的设置不一定一致。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlayerBar(
    info: PlaybackInfoDto,
    selectedAudio: Int?,
    selectedSubtitle: Int?,
    superResolution: EnhancementState,
    videoHdr: EnhancementState,
    onAudio: (MediaStreamDto) -> Unit,
    onSubtitle: (MediaStreamDto?) -> Unit,
    onClose: () -> Unit
) {
    var audioMenu by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    val audioStreams = info.item.mediaStreams.filter { it.type == StreamType.AUDIO }
    val subtitleStreams = info.item.mediaStreams.filter { it.type == StreamType.SUBTITLE }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                info.item.seriesName?.let { "$it · ${info.item.name}" } ?: info.item.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            EnhancementChip("RTX 超分", superResolution)
            EnhancementChip("RTX HDR", videoHdr)
            Spacer(Modifier.width(4.dp))

            Box {
                TextButton(onClick = { audioMenu = true }) {
                    Text(audioStreams.firstOrNull { it.index == selectedAudio }?.displayTitle ?: "音轨")
                }
                DropdownMenu(audioMenu, onDismissRequest = { audioMenu = false }) {
                    audioStreams.forEach { stream ->
                        DropdownMenuItem(
                            text = { Text(stream.displayTitle) },
                            onClick = { audioMenu = false; onAudio(stream) }
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { subtitleMenu = true }) {
                    Text(subtitleStreams.firstOrNull { it.index == selectedSubtitle }?.displayTitle ?: "字幕")
                }
                DropdownMenu(subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("关闭字幕") },
                        onClick = { subtitleMenu = false; onSubtitle(null) }
                    )
                    subtitleStreams.forEach { stream ->
                        DropdownMenuItem(
                            text = { Text(stream.displayTitle) },
                            onClick = { subtitleMenu = false; onSubtitle(stream) }
                        )
                    }
                }
            }
            TextButton(onClick = onClose) { Text("结束播放") }
        }
    }
}

@Composable
private fun EnhancementChip(label: String, state: EnhancementState) {
    if (state == EnhancementState.OFF) return
    val suffix = when (state) {
        EnhancementState.ACTIVE -> "已启用"
        EnhancementState.REQUESTED -> "等待中"
        EnhancementState.UNSUPPORTED -> "不适用"
        EnhancementState.FAILED -> "失败"
        EnhancementState.OFF -> return
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text("$label · $suffix", style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = when (state) {
                EnhancementState.ACTIVE -> MaterialTheme.colorScheme.primary
                EnhancementState.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    )
}

/**
 * Waits for the canvas to have a native peer and returns its handle: an HWND on
 * Windows, an NSView on macOS, an X11 window id on Linux — which is what mpv's
 * `wid` option takes on each.
 *
 * There is no callback for "the peer now exists" that survives Compose's own
 * interop lifecycle, so this polls; the wait is a few frames in practice and
 * the bound only exists so a failure ends in a message rather than a hang.
 */
private suspend fun awaitNativeHandle(canvas: Canvas): Long? {
    repeat(HANDLE_ATTEMPTS) {
        if (canvas.isDisplayable) {
            val pointer: Pointer? = runCatching { Native.getComponentPointer(canvas) }.getOrNull()
            if (pointer != null) return Pointer.nativeValue(pointer)
        }
        delay(HANDLE_POLL_MS)
    }
    return null
}

private const val HANDLE_ATTEMPTS = 100
private const val HANDLE_POLL_MS = 50L
