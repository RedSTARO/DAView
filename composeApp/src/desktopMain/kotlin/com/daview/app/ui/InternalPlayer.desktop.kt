package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daview.app.player.EnhancementLog
import com.daview.app.player.EnhancementState
import com.daview.app.player.MpvNative
import com.daview.app.player.MpvPlayer
import com.daview.app.player.PlayerPreferences
import com.daview.app.player.TrackMapping
import com.daview.app.player.TrackMenu
import com.daview.app.player.VideoAdapter
import com.daview.shared.model.PlaybackInfoDto
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
    onClose: (positionMs: Long) -> Unit,
    onEnded: (positionMs: Long) -> Unit
) {
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnClose by rememberUpdatedState(onClose)
    val latestOnEnded by rememberUpdatedState(onEnded)

    val canvas = remember {
        Canvas().apply {
            // The frame between the window appearing and mpv's first frame is
            // this colour; the app's own background would flash white.
            background = AwtColor.BLACK
        }
    }

    // All of this describes *one file*, and one screen plays several of them:
    // finishing an episode swaps `info` for the next without the composable
    // ever leaving the tree. Remembered without a key it all survived into the
    // next episode, and three separate faults came out of that. `configured`
    // stayed latched, so no external subtitle was attached and no track chosen
    // ever again. `ending` still read FINISHED, so setting it to FINISHED a
    // second time was not a state change, the effect watching it never ran, and
    // the chain stopped dead on the episode after the first. And with `ending`
    // never null again, the shutdown guard below could not fire either, which
    // is the case that leaves a live session on a dead surface.
    var selectedAudio by remember(info.sessionId) { mutableStateOf(info.audioStreamIndex) }
    var selectedSubtitle by remember(info.sessionId) { mutableStateOf(info.subtitleStreamIndex) }
    var failure by remember(info.sessionId) { mutableStateOf<String?>(null) }
    var ending by remember(info.sessionId) { mutableStateOf<Ending?>(null) }
    // Attaching subtitles and choosing tracks has to wait until mpv has a file
    // open, and mpv says so on its own thread - so the work is prepared here and
    // run from the event callback, exactly once per file.
    val configured = remember(info.sessionId) { AtomicBoolean(false) }
    var attachTracks by remember(info.sessionId) { mutableStateOf({}) }
    var superResolution by remember(info.sessionId) { mutableStateOf(EnhancementState.OFF) }
    var videoHdr by remember(info.sessionId) { mutableStateOf(EnhancementState.OFF) }
    var maxLumaGuess by remember(info.sessionId) { mutableStateOf(false) }
    var adapterInUse by remember(info.sessionId) { mutableStateOf<String?>(null) }
    var unboundKeys by remember(info.sessionId) { mutableStateOf(emptyList<String>()) }

    // The engine outlives the file. The effect that starts the next one has to
    // be able to see the previous one in order to close it, which a keyed
    // remember would have thrown away before it got the chance.
    var player by remember { mutableStateOf<MpvPlayer?>(null) }

    // onClose both stops the session and pops the screen, and there are two
    // routes to it — the file ending and the screen going away. That is a
    // property of the screen rather than of the file, so it is deliberately not
    // keyed: per session it would let one screen close itself once per episode.
    val closedOnce = remember { AtomicBoolean(false) }
    fun finish(positionMs: Long) {
        if (closedOnce.compareAndSet(false, true)) latestOnClose(positionMs)
    }

    // mpv cannot go full screen for itself while embedded in someone else's
    // window (--wid), so the window is what goes full screen. Its `f` and `ESC`
    // are bound below to ask for exactly that, which is the only way they mean
    // anything at all here.
    PlaybackPresentation(ScreenOrientation.SENSOR)
    val fullscreen = LocalWindowFullscreen.current
    val isFullscreen = fullscreen?.value ?: false

    // Every route to a track change ends here: mpv's own key, handed back as a
    // client message, and the button in the bar. One list, one recorded choice,
    // whichever of them was used — which is what stops mpv and the app from
    // disagreeing about what is playing.
    //
    // The list is drawn by mpv rather than by Compose. See [TrackMenu] for why
    // there is no menu to open and close: an OSD message needs no room, so the
    // picture no longer moves down to make space for one.
    fun stepAudio() {
        val entries = TrackMenu.audio(info.item.mediaStreams)
        val next = TrackMenu.next(entries, selectedAudio) ?: return
        selectedAudio = next.index
        TrackMapping.audioId(info.item.mediaStreams, next.index)
            ?.let { player?.selectAudio(it) }
        player?.showText(TrackMenu.osd("音轨", entries, next.index), OSD_MS)
        player?.positionMs?.let { latestOnProgress(it, false, next.index, selectedSubtitle) }
    }

    fun stepSubtitle() {
        val entries = TrackMenu.subtitles(info.item.mediaStreams)
        val next = TrackMenu.next(entries, selectedSubtitle) ?: return
        selectedSubtitle = next.index
        if (next.index == null) {
            player?.disableSubtitle()
        } else {
            TrackMapping.subtitleId(info.item.mediaStreams, next.index)
                ?.let { player?.selectSubtitle(it) }
        }
        player?.showText(TrackMenu.osd("字幕", entries, next.index), OSD_MS)
        player?.positionMs?.let { latestOnProgress(it, false, selectedAudio, next.index) }
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
                    // The adapter comes first — the video output is created
                    // before the filter chain — so it is already known by the
                    // time the RTX lines arrive, and it is what decides whether
                    // to believe them.
                    VideoAdapter.deviceNameFrom(prefix, text)?.let {
                        adapterInUse = it
                        PlayerPreferences.lastAdapterInUse = it
                    }
                    EnhancementLog.superResolution(text)?.let {
                        superResolution = EnhancementLog.believable(it, adapterInUse)
                    }
                    EnhancementLog.videoHdr(text)?.let {
                        videoHdr = EnhancementLog.believable(it, adapterInUse)
                    }
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

                // A key pressed over the picture. mpv holds the keyboard
                // whenever the pointer gave its window focus, which is most of
                // the time, so this is the route that always works — the
                // Compose bindings below only fire while Compose has focus.
                override fun onClientMessage(name: String) {
                    when (name) {
                        MSG_AUDIO -> stepAudio()
                        MSG_SUBTITLE -> stepSubtitle()
                        MSG_FULLSCREEN -> fullscreen?.let { it.value = !it.value }
                        // Leave full screen first, as every other player does;
                        // a second press ends playback. Routed through `ending`
                        // rather than closing from here, so that the one effect
                        // that decides what position to write still decides it.
                        MSG_ESCAPE -> when {
                            fullscreen?.value == true -> fullscreen.value = false
                            ending == null -> ending = Ending.ABANDONED
                            else -> Unit
                        }
                    }
                }
            }).apply {
                open(
                    handle,
                    MpvPlayer.Config(info.startPositionMs, enhancement, PlayerPreferences.adapter)
                )
            }
        }.onFailure { failure = it.message ?: "无法启动内置播放器" }.getOrNull() ?: return@LaunchedEffect

        // Published before it is configured, because the only thing that closes
        // it is this state: leaving the screen mid-setup would otherwise strand
        // an mpv instance holding a window and a decoder.
        player = created

        // mpv's own keys, pointed at DAView's list instead of mpv's. `#` and
        // `j` already mean "next audio track" and "next subtitle" to anyone who
        // has used mpv; what changes is who decides — so an external subtitle
        // file is part of the same cycle, every track is named the way DAView
        // names it, and the choice reaches the session.
        //
        // `f` and `ESC` are here for a different reason: embedded in someone
        // else's window mpv cannot go full screen for itself, so the two keys
        // that mean full screen in every player there is did nothing at all.
        // Now they ask the window.
        //
        // Each is tried under more than one name, because the key table is
        // mpv's rather than this app's — `#` is written `SHARP` in a config
        // file because `#` starts a comment there, and whether that alias
        // survives into the `keybind` command is mpv's business. A binding mpv
        // refused is silent by nature: the key never fires, and a key that
        // never fires looks exactly like a key nobody pressed. So the refusals
        // are collected and said out loud once, where whoever pressed the key
        // is already looking.
        unboundKeys = listOf(
            listOf("SHARP", "#") to MSG_AUDIO,
            listOf("j") to MSG_SUBTITLE,
            listOf("f") to MSG_FULLSCREEN,
            listOf("ESC", "ESCAPE") to MSG_ESCAPE
        ).filterNot { (names, message) -> names.any { created.bindKey(it, message) } }
            .map { (names, _) -> names.first() }
        // What mpv's controller shows above its seek bar. Left alone it is the
        // address of a loopback port, which is what the viewer would have read.
        created.setTitle(displayTitle(info))

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
        // The RTX log lines arrive with the video output, a moment after the
        // file opens. The chips that carry them live in the bar, and the bar is
        // not on screen in full screen — which is how this player always starts
        // — so the outcome is said once on the only layer above the picture.
        delay(ENHANCEMENT_REPORT_MS)
        startupReport(superResolution, videoHdr, unboundKeys)?.let { active.showText(it, OSD_MS) }
        while (true) {
            delay(PROGRESS_INTERVAL_MS)
            // mpv picks a track when nothing was asked for, and keys of its own
            // can change it afterwards. Read back rather than assumed: a switch
            // made in mpv used to go unrecorded, and the next device then
            // resumed on the track nobody had chosen.
            TrackMapping.audioIndex(info.item.mediaStreams, active.audioId)
                ?.let { if (it != selectedAudio) selectedAudio = it }
            // `sid=no` means either that somebody turned subtitles off or
            // that the chosen one was never attached — a container this app
            // could not probe has nothing to map, and `sub-add` can fail on its
            // own. Only the first of those is a decision, so it is adopted only
            // when the stored choice was one this app could have applied;
            // otherwise reading mpv back would quietly erase a choice mpv never
            // honoured in the first place.
            val applied = TrackMapping.subtitleId(info.item.mediaStreams, selectedSubtitle)
            if (active.subtitleOff) {
                if (selectedSubtitle != null && applied != null) selectedSubtitle = null
            } else {
                TrackMapping.subtitleIndex(info.item.mediaStreams, active.subtitleId)
                    ?.let { if (it != selectedSubtitle) selectedSubtitle = it }
            }
            val position = active.positionMs ?: continue
            latestOnProgress(position, active.paused, selectedAudio, selectedSubtitle)
        }
    }

    // mpv is still up and painting black when a file fails, and its window
    // covers whatever Compose draws in the same place — so the card below is
    // only ever seen when there is no mpv window at all, which is the case
    // where the engine itself would not start. Everything else has to be said
    // where it can be read.
    LaunchedEffect(failure) {
        failure?.let { player?.showText(it, FAILURE_MS) }
    }

    // Same reason: this used to be a strip under the picture, and in full
    // screen there is no strip under the picture.
    LaunchedEffect(maxLumaGuess) {
        if (maxLumaGuess) player?.showText(MAX_LUMA_NOTICE, FAILURE_MS)
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

        // A file that ran out is the one case where the next episode is what
        // the viewer wants. Abandoning it is not, so only FINISHED plays on.
        if (reason == Ending.FINISHED) {
            latestOnEnded(position)
            return@LaunchedEffect
        }
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

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            // mpv has its own bindings, but they only fire while its canvas
            // holds keyboard focus — and clicking anything in the bar takes
            // that away, after which space re-triggered whichever button was
            // pressed last. In full screen the bar is not rendered at all, so
            // there mpv holds the keyboard and the bindings it was given above
            // are the ones that answer.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Spacebar -> { player?.togglePause(); true }
                    Key.DirectionLeft -> { player?.seekBy(-10); true }
                    Key.DirectionRight -> { player?.seekBy(10); true }
                    Key.F11 -> { fullscreen?.let { it.value = !it.value }; true }
                    Key.Escape -> {
                        // Leave full screen first, as every other player does;
                        // a second press ends playback — through `ending`, so
                        // that the one effect which knows what position to
                        // write keeps deciding it, whichever route asked.
                        if (fullscreen?.value == true) fullscreen.value = false
                        else if (ending == null) ending = Ending.ABANDONED
                        true
                    }
                    else -> false
                }
            }
    ) {
        // The bar takes room away from the picture, because nothing Compose
        // draws can be *over* mpv's native window — whatever is on screen is
        // beside the video, never on it. In full screen there is nothing for it
        // to be beside, so it is simply not there: the picture is the window,
        // and everything the bar carries has an OSD route as well. It does not
        // fade on a timer either; appearing and disappearing would resize the
        // video every few seconds, which no player does to its viewer.
        if (!isFullscreen) {
            PlayerBar(
                info = info,
                superResolution = superResolution,
                videoHdr = videoHdr,
                adapterInUse = adapterInUse,
                onAudio = { stepAudio() },
                onSubtitle = { stepSubtitle() },
                onToggleFullscreen = { fullscreen?.let { it.value = !it.value } },
                onClose = { if (ending == null) ending = Ending.ABANDONED }
            )
        }

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
    }
}

@Composable
private fun PlayerBar(
    info: PlaybackInfoDto,
    superResolution: EnhancementState,
    videoHdr: EnhancementState,
    adapterInUse: String?,
    onAudio: () -> Unit,
    onSubtitle: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    // Playback chrome stays dark whichever theme the rest of the app is in. In
    // the light theme this was a near-white band across the top of a black
    // picture, which is not something any player does.
    Surface(color = Color(0xFF15131C), contentColor = Color.White) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                displayTitle(info),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Takes what is left after the controls instead of competing
                // with them. The bar was one unwrapped Row, so the GPU name and
                // a long track title pushed the close button off the edge and
                // then wrapped the whole thing onto three lines.
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.weight(1f))

            adapterInUse?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            EnhancementChip("RTX 超分", superResolution)
            EnhancementChip("RTX HDR", videoHdr)

            // These step to the next track and let mpv draw the list, which is
            // the same thing the keys do. Naming the key in the description is
            // the only place the pairing is discoverable, since the picture
            // belongs to mpv and this bar is gone in full screen.
            IconButton(onClick = onAudio) {
                Icon(Icons.Filled.Audiotrack, contentDescription = "下一条音轨（#）")
            }
            IconButton(onClick = onSubtitle) {
                Icon(Icons.Filled.ClosedCaption, contentDescription = "下一条字幕（j）")
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(Icons.Filled.Fullscreen, contentDescription = "全屏（F11 / f）")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "结束播放")
            }
        }
    }
}

@Composable
private fun EnhancementChip(label: String, state: EnhancementState) {
    val suffix = enhancementSuffix(state) ?: return
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

/** The show, then the episode, which is how the rest of the app names one too. */
private fun displayTitle(info: PlaybackInfoDto): String =
    info.item.seriesName?.let { "$it · ${info.item.name}" } ?: info.item.name

/**
 * Everything worth saying once a file is running: what became of the two NVIDIA
 * features, and which keys mpv would not take. Null when there is nothing to
 * report, which is the ordinary case.
 */
private fun startupReport(
    superResolution: EnhancementState,
    videoHdr: EnhancementState,
    unboundKeys: List<String>
): String? = listOfNotNull(
    listOfNotNull(
        enhancementSuffix(superResolution)?.let { "RTX 超分 · $it" },
        enhancementSuffix(videoHdr)?.let { "RTX HDR · $it" }
    ).takeIf { it.isNotEmpty() }?.joinToString("   "),
    unboundKeys.takeIf { it.isNotEmpty() }
        ?.let { "mpv 未接受快捷键 ${it.joinToString(" ")}，请用工具条上的按钮" }
).takeIf { it.isNotEmpty() }?.joinToString("\n")

/** Null for a feature nobody turned on: there is nothing to say about it. */
private fun enhancementSuffix(state: EnhancementState): String? = when (state) {
    EnhancementState.ACTIVE -> "已启用"
    EnhancementState.REQUESTED -> "等待中"
    EnhancementState.UNSUPPORTED -> "不适用"
    EnhancementState.FAILED -> "失败"
    EnhancementState.OFF -> null
}

/**
 * Names for the keys mpv hands back. They only have to be unique among the
 * messages this client listens for.
 */
private const val MSG_AUDIO = "daview-audio"
private const val MSG_SUBTITLE = "daview-subtitle"
private const val MSG_FULLSCREEN = "daview-fullscreen"
private const val MSG_ESCAPE = "daview-escape"

private const val OSD_MS = 3000
private const val FAILURE_MS = 8000
private const val PROGRESS_INTERVAL_MS = 5000L
private const val ENHANCEMENT_REPORT_MS = 2500L

private const val MAX_LUMA_NOTICE =
    "RTX Video HDR 的峰值亮度按 1000 nits 估算，与 NVIDIA App 里的设置不一定一致。"
