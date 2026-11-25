package com.daview.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.daview.app.platform.chooseFile
import com.daview.app.player.EnhancementLog
import com.daview.app.player.EnhancementState
import com.daview.app.player.MpvNative
import com.daview.app.player.MpvPlayer
import com.daview.app.player.PlayerPreferences
import com.daview.app.player.TrackMapping
import com.daview.app.player.TrackMenu
import com.daview.app.player.VideoAdapter
import com.daview.app.theme.DaViewTheme
import com.daview.shared.model.PlaybackInfoDto
import com.daview.shared.model.SUBTITLE_OFF
import com.daview.shared.model.StreamType
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Canvas
import java.awt.Cursor
import java.awt.Dialog
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import androidx.compose.foundation.Canvas as ComposeCanvas
import java.awt.Color as AwtColor

/** How a session ended; the two write different things back. */
private enum class Ending { FINISHED, ABANDONED }

/** What the controls have opened above themselves. */
private enum class Panel(val title: String) {
    AUDIO("音轨"), SUBTITLE("字幕"), SUBTITLE_TUNING("字幕调整"),
    SPEED("播放速度"), CHAPTERS("章节"), OPTIONS("更多播放选项")
}

/**
 * In-app playback on the desktop, backed by libmpv.
 *
 * The picture is a native child window mpv owns, parented to a heavyweight AWT
 * canvas — see [MpvPlayer] for why nothing else works if the NVIDIA features
 * are to be reachable. Nothing Compose draws in the same window can appear over
 * that child window, so the controls live in a window of their own: a
 * transparent, undecorated one that never takes focus, laid over the bottom of
 * the picture and moved with it. The picture keeps the whole screen whether the
 * controls are showing or not; with them in the layout it shrank and grew every
 * time the pointer moved, and the only way to choose a subtitle was to cycle
 * through all of them.
 */
@Composable
actual fun InternalPlayer(
    info: PlaybackInfoDto,
    screen: PlayerScreenState,
    subtitleScale: Float,
    onProgress: (positionMs: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?) -> Unit,
    onClose: (positionMs: Long) -> Unit,
    onEnded: (positionMs: Long) -> Unit,
    onSkip: (itemId: String, positionMs: Long) -> Unit
) {
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnClose by rememberUpdatedState(onClose)
    val latestOnEnded by rememberUpdatedState(onEnded)
    val latestOnSkip by rememberUpdatedState(onSkip)
    val scope = rememberCoroutineScope()

    val canvas = remember {
        Canvas().apply {
            // The frame between the window appearing and mpv's first frame is
            // this colour; the app's own background would flash white.
            background = AwtColor.BLACK
        }
    }

    // The player screen builds one of these per session, so plain remembers
    // are per file.
    var selectedAudio by remember { mutableStateOf(info.audioStreamIndex) }
    var selectedSubtitle by remember { mutableStateOf(info.subtitleStreamIndex?.takeIf { it != SUBTITLE_OFF }) }
    /** A subtitle file picked from this computer for this session. */
    var mountedSubtitle by remember { mutableStateOf<String?>(null) }
    /** The external subtitle files mpv actually took, once they are added. */
    var attachedSubtitles by remember { mutableStateOf<Map<Int, Int>?>(null) }
    /** From opening the file until its first frame, which over the network is a while. */
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var ending by remember { mutableStateOf<Ending?>(null) }
    val configured = remember { AtomicBoolean(false) }
    var attachTracks by remember { mutableStateOf({}) }
    var superResolution by remember { mutableStateOf(EnhancementState.OFF) }
    var videoHdr by remember { mutableStateOf(EnhancementState.OFF) }
    var maxLumaGuess by remember { mutableStateOf(false) }
    var adapterInUse by remember { mutableStateOf<String?>(null) }
    var unboundKeys by remember { mutableStateOf(emptyList<String>()) }

    var positionMs by remember { mutableStateOf(info.startPositionMs) }
    var durationMs by remember { mutableStateOf(info.runtimeMs ?: 0L) }
    var paused by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(PlayerPreferences.volume) }
    var muted by remember { mutableStateOf(PlayerPreferences.muted) }
    var speed by remember { mutableStateOf(screen.speed) }
    var buffering by remember { mutableStateOf(false) }
    var bufferingPercent by remember { mutableStateOf<Int?>(null) }
    var chapters by remember { mutableStateOf(emptyList<MpvPlayer.Chapter>()) }
    var chapter by remember { mutableStateOf<Int?>(null) }
    var scrubbingMs by remember { mutableStateOf<Long?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }

    var player by remember { mutableStateOf<MpvPlayer?>(null) }

    // Set further down, once the chapters are known; mpv's Enter reaches it.
    var skipIntroAction by remember { mutableStateOf({}) }

    // When the pointer last moved or a control was used; the controls show for
    // a few seconds after that.
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    fun stirred() { lastActivity = System.currentTimeMillis() }
    var overControls by remember { mutableStateOf(false) }

    val closedOnce = remember { AtomicBoolean(false) }
    fun finish(position: Long) {
        if (closedOnce.compareAndSet(false, true)) latestOnClose(position)
    }

    val fullscreen = LocalWindowFullscreen.current
    val isFullscreen = fullscreen?.value ?: false
    fun toggleFullscreen() {
        fullscreen?.let { it.value = !it.value }
        stirred()
    }

    fun report(audio: Int? = selectedAudio, subtitle: Int? = selectedSubtitle) {
        val position = player?.positionMs ?: return
        // "Off" is said as off, so the next play does not turn subtitles back on.
        val subtitleChoice = when {
            mountedSubtitle != null -> null
            subtitle == null && info.item.mediaStreams.any { it.type == StreamType.SUBTITLE } -> SUBTITLE_OFF
            else -> subtitle
        }
        latestOnProgress(position, player?.paused ?: false, audio, subtitleChoice)
    }

    fun chooseAudio(index: Int) {
        selectedAudio = index
        TrackMapping.audioId(info.item.mediaStreams, index)?.let { player?.selectAudio(it) }
        report(audio = index)
    }

    // What `v` brings back after hiding the subtitles.
    var lastShownSubtitle by remember { mutableStateOf(info.subtitleStreamIndex?.takeIf { it != SUBTITLE_OFF }) }

    fun chooseSubtitle(index: Int?) {
        if (index != null) lastShownSubtitle = index
        selectedSubtitle = index
        mountedSubtitle = null
        if (index == null) player?.disableSubtitle()
        else TrackMapping.subtitleId(info.item.mediaStreams, index, attachedSubtitles)?.let { player?.selectSubtitle(it) }
        report(subtitle = index)
    }

    // The keys that step through the lists, for anyone who knows them from mpv.
    fun stepAudio() {
        val entries = TrackMenu.audio(info.item.mediaStreams)
        val next = TrackMenu.next(entries, selectedAudio) ?: return
        next.index?.let { chooseAudio(it) }
        player?.showText(TrackMenu.osd("音轨", entries, next.index), OSD_MS)
    }

    fun stepSubtitle() {
        val entries = TrackMenu.subtitles(info.item.mediaStreams)
        val next = TrackMenu.next(entries, selectedSubtitle) ?: return
        chooseSubtitle(next.index)
        player?.showText(TrackMenu.osd("字幕", entries, next.index), OSD_MS)
    }

    fun changeVolume(value: Int) {
        val clamped = value.coerceIn(0, 100)
        player?.volume = clamped
        volume = clamped
        PlayerPreferences.volume = clamped
        if (muted && clamped > 0) {
            player?.muted = false
            muted = false
            PlayerPreferences.muted = false
        }
        stirred()
    }

    fun toggleMute() {
        val now = !muted
        player?.muted = now
        muted = now
        PlayerPreferences.muted = now
        player?.showText(if (now) "静音" else "音量 $volume%", 1200)
        stirred()
    }

    fun changeSpeed(value: Float) {
        val clamped = value.coerceIn(0.25f, 3f)
        player?.speed = clamped
        speed = clamped
        screen.speed = clamped
        player?.showText("倍速 ${formatSpeed(clamped)}", 1200)
        stirred()
    }

    fun stepSpeed(up: Boolean) {
        val index = SPEEDS.indexOfFirst { it >= speed - 0.001f }.let { if (it < 0) SPEEDS.lastIndex else it }
        val target = if (up) SPEEDS.getOrElse(index + 1) { SPEEDS.last() } else SPEEDS.getOrElse(index - 1) { SPEEDS.first() }
        changeSpeed(target)
    }

    fun changeSubtitleDelay(deltaMs: Long) {
        val value = (screen.subtitleDelayMs + deltaMs).coerceIn(-60_000, 60_000)
        screen.subtitleDelayMs = value
        player?.subtitleDelayMs = value
        player?.showText("字幕延迟 ${formatDelay(value)}", 1200)
        stirred()
    }

    fun skip(itemId: String?) {
        itemId ?: return
        latestOnSkip(itemId, player?.positionMs ?: positionMs)
    }

    fun mountSubtitleFile() {
        val file = chooseFile("选择字幕文件", listOf("ass", "ssa", "srt", "vtt", "sup", "sub")) ?: return
        player?.addSubtitleFile(file.absolutePath, file.name)
        mountedSubtitle = file.name
        selectedSubtitle = null
        player?.showText("已加载字幕：${file.name}", OSD_MS)
        stirred()
    }

    LaunchedEffect(Unit) {
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

                // Subtitles and track ids apply to the file mpv has open, so
                // they wait for it.
                override fun onFileLoaded() {
                    if (configured.compareAndSet(false, true)) attachTracks()
                }

                override fun onEndFile(error: String?, reachedEnd: Boolean) {
                    when {
                        error != null -> failure = "播放失败：$error"
                        reachedEnd -> ending = Ending.FINISHED
                        else -> ending = Ending.ABANDONED
                    }
                }

                // `q` over the picture quits mpv; the screen has to follow.
                override fun onShutdown() {
                    if (ending == null) ending = Ending.ABANDONED
                }

                override fun onClientMessage(name: String) {
                    when (name) {
                        MSG_AUDIO -> stepAudio()
                        MSG_SUBTITLE -> stepSubtitle()
                        MSG_FULLSCREEN -> toggleFullscreen()
                        // Escape leaves full screen, as every player does, and
                        // does nothing else: ending the film is the close button
                        // or `q`, not the key people press to get out of full
                        // screen.
                        MSG_ESCAPE -> when {
                            panel != null -> panel = null
                            fullscreen?.value == true -> fullscreen.value = false
                        }
                        MSG_PREVIOUS -> skip(info.previousItemId)
                        MSG_NEXT -> skip(info.nextItemId)
                        MSG_SKIP -> skipIntroAction()
                        // mpv's own `v` only hid them, so the panel and the
                        // recorded choice still said they were on.
                        MSG_SUBTITLE_VISIBILITY -> chooseSubtitle(if (selectedSubtitle != null) null else lastShownSubtitle)
                    }
                }
            }).apply {
                open(
                    handle,
                    MpvPlayer.Config(
                        startPositionMs = info.startPositionMs,
                        enhancement = enhancement,
                        adapter = PlayerPreferences.adapter,
                        volume = PlayerPreferences.volume,
                        muted = PlayerPreferences.muted,
                        speed = screen.speed,
                        subtitleScale = screen.subtitleScale ?: subtitleScale,
                        subtitleDelayMs = screen.subtitleDelayMs
                    )
                )
            }
        }.onFailure { failure = it.message ?: "无法启动内置播放器" }.getOrNull() ?: return@LaunchedEffect

        player = created

        // mpv's own keys, made to do what the same keys do when the app has the
        // keyboard. Which side has it depends on what was clicked last, which
        // nothing on screen shows — so the two used to disagree: ←/→ moved 5
        // seconds or 10, ↑/↓ jumped a minute or did nothing.
        unboundKeys = listOf(
            listOf("SHARP", "#") to MSG_AUDIO,
            listOf("j") to MSG_SUBTITLE,
            listOf("f", "F11") to MSG_FULLSCREEN,
            listOf("ESC", "ESCAPE") to MSG_ESCAPE,
            listOf("v") to MSG_SUBTITLE_VISIBILITY,
            listOf("<") to MSG_PREVIOUS,
            listOf(">") to MSG_NEXT
        ).filterNot { (names, message) -> names.any { created.bindKey(it, message) } }
            .map { (names, _) -> names.first() } +
            listOf(
                "UP" to "add volume 5",
                "DOWN" to "add volume -5",
                "LEFT" to "seek -5",
                "RIGHT" to "seek 5",
                // mpv's defaults have these the other way round from the
                // app's side and from the shortcut list.
                "PGUP" to "add chapter -1",
                "PGDWN" to "add chapter 1"
            ).filterNot { (key, command) -> created.bindCommand(key, command) }.map { it.first }
        // What mpv's OSD shows as the title: the show, the episode number and
        // its name — the number was missing, which is the part that says where
        // in the run this is.
        created.setTitle(displayTitle(info))

        attachTracks = {
            // Every external subtitle is attached in a fixed order, and the id
            // mpv gives each is read back from its track list rather than
            // counted: mpv can know a subtitle track the probe did not list.
            val added = mutableMapOf<Int, Int>()
            TrackMapping.externalSubtitles(info.item.mediaStreams).forEach { stream ->
                info.subtitleUrls[stream.index]?.let { url ->
                    if (created.addSubtitle(url, stream.displayTitle, stream.language)) {
                        added[stream.index] = created.externalSubtitleId(url)
                            ?: (TrackMapping.embeddedSubtitles(info.item.mediaStreams).size + added.size + 1)
                    }
                }
            }
            attachedSubtitles = added
            TrackMapping.audioId(info.item.mediaStreams, selectedAudio)
                ?.let { created.selectAudio(it) }
            if (selectedSubtitle == null) {
                created.disableSubtitle()
            } else {
                TrackMapping.subtitleId(info.item.mediaStreams, selectedSubtitle, added)
                    ?.let { created.selectSubtitle(it) }
            }
            chapters = created.chapters
        }
        created.play(info.streamUrl)
    }

    // The position the session keeps, every five seconds.
    LaunchedEffect(player) {
        val active = player ?: return@LaunchedEffect
        delay(ENHANCEMENT_REPORT_MS)
        startupReport(superResolution, videoHdr, unboundKeys)?.let { active.showText(it, OSD_MS) }
        while (true) {
            delay(PROGRESS_INTERVAL_MS)
            // mpv picks a track when nothing was asked for, and keys of its own
            // can change it afterwards. Read back rather than assumed.
            TrackMapping.audioIndex(info.item.mediaStreams, active.audioId)
                ?.let { if (it != selectedAudio) selectedAudio = it }
            if (mountedSubtitle == null) {
                // A choice whose file never made it into mpv is not "off":
                // it is kept, for the next time the file can be fetched.
                val applied = TrackMapping.subtitleId(info.item.mediaStreams, selectedSubtitle, attachedSubtitles)
                if (active.subtitleOff) {
                    if (selectedSubtitle != null && applied != null) selectedSubtitle = null
                } else {
                    TrackMapping.subtitleIndex(info.item.mediaStreams, active.subtitleId, attachedSubtitles)
                        ?.let { if (it != selectedSubtitle) selectedSubtitle = it }
                }
            }
            report()
        }
    }

    // The controls' own clock, four times a second.
    LaunchedEffect(player) {
        val active = player ?: return@LaunchedEffect
        var bufferingSince = 0L
        while (true) {
            delay(TICK_MS)
            active.positionMs?.let { if (scrubbingMs == null) positionMs = it }
            active.durationMs?.let { if (it > 0) durationMs = it }
            paused = active.paused
            // The keys on mpv's side change these too; the bar and the stored
            // preference follow whoever changed them.
            active.volumeOrNull?.let { if (it != volume) { volume = it; PlayerPreferences.volume = it } }
            active.mutedOrNull?.let { if (it != muted) { muted = it; PlayerPreferences.muted = it } }
            active.speedOrNull?.let { if (kotlin.math.abs(it - speed) > 0.001f) { speed = it; screen.speed = it } }
            // mpv's z / x change the delay too.
            active.subtitleDelayOrNull?.let { if (it != screen.subtitleDelayMs) screen.subtitleDelayMs = it }
            chapter = active.chapter
            val nowBuffering = active.buffering
            if (loading && active.positionMs != null && !nowBuffering) loading = false
            bufferingPercent = if (nowBuffering) active.bufferingPercent else null
            if (nowBuffering && !buffering) bufferingSince = System.currentTimeMillis()
            buffering = nowBuffering
            // With the controls hidden the OSD is the only place to say it.
            if (nowBuffering && System.currentTimeMillis() - bufferingSince > 800 &&
                !controlsShowing(lastActivity, paused, panel, overControls)
            ) {
                active.showText("缓冲中" + (bufferingPercent?.let { " $it%" } ?: "…"), 600)
            }
        }
    }

    LaunchedEffect(maxLumaGuess) {
        if (maxLumaGuess) player?.showText(MAX_LUMA_NOTICE, FAILURE_MS)
    }

    // A file that fails leaves no picture worth keeping: mpv is closed and the
    // card that says what happened — with a way to try again — takes its place.
    // It used to stay behind mpv's black window, readable only in the eight
    // seconds the OSD held the message.
    LaunchedEffect(failure) {
        if (failure == null) return@LaunchedEffect
        val active = player ?: return@LaunchedEffect
        val last = active.lastPosition ?: active.positionMs ?: positionMs
        positionMs = last
        player = null
        active.close()
    }

    LaunchedEffect(ending) {
        val reason = ending ?: return@LaunchedEffect
        val active = player
        // mpv answers nothing once it is shutting down, and the viewer pressing
        // `q` *is* the shutdown, so the live read is expected to come back empty
        // here. Only a file that actually ran out gets the runtime.
        val position = active?.positionMs
            ?: active?.lastPosition
            ?: info.runtimeMs.takeIf { reason == Ending.FINISHED }
            ?: info.startPositionMs
        if (reason == Ending.FINISHED) {
            latestOnEnded(position)
            return@LaunchedEffect
        }
        finish(position)
    }

    DisposableEffect(Unit) {
        onDispose {
            val active = player
            val position = active?.positionMs ?: active?.lastPosition ?: positionMs
            active?.close()
            finish(position)
        }
    }

    // The pointer over the picture. mpv sees none of it (see MpvPlayer), and
    // Compose cannot see into a heavyweight peer, so AWT's own listeners on the
    // canvas are the only thing that knows the mouse moved.
    DisposableEffect(canvas) {
        var lastX = Int.MIN_VALUE
        var lastY = Int.MIN_VALUE
        fun moved(e: MouseEvent) {
            if (e.xOnScreen == lastX && e.yOnScreen == lastY) return
            lastX = e.xOnScreen
            lastY = e.yOnScreen
            stirred()
        }
        val motion = object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) = moved(e)
            override fun mouseDragged(e: MouseEvent) = moved(e)
        }
        // One click pauses, two go full screen. The pause waits out the
        // double-click interval so a double click does not pause and unpause
        // on its way to full screen — which is all a double click used to do.
        var pendingClick: Job? = null
        val clicks = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                stirred()
                if (e.button != MouseEvent.BUTTON1) return
                if (panel != null) {
                    panel = null
                    return
                }
                when (e.clickCount) {
                    1 -> pendingClick = scope.launch {
                        delay(doubleClickMs())
                        player?.togglePause()
                    }
                    2 -> {
                        pendingClick?.cancel()
                        toggleFullscreen()
                    }
                    // A third click is the end of a double click, not another.
                    else -> pendingClick?.cancel()
                }
            }
        }
        // The wheel over the picture is the volume, as in most desktop players.
        val wheel = java.awt.event.MouseWheelListener { e ->
            stirred()
            changeVolume(volume - e.wheelRotation * 5)
            player?.showText(if (muted) "静音" else "音量 $volume%", 800)
        }
        canvas.addMouseMotionListener(motion)
        canvas.addMouseListener(clicks)
        canvas.addMouseWheelListener(wheel)
        onDispose {
            canvas.removeMouseMotionListener(motion)
            canvas.removeMouseListener(clicks)
            canvas.removeMouseWheelListener(wheel)
        }
    }

    // Re-evaluated when the linger runs out, not only when something changes.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(lastActivity, paused, panel, overControls, scrubbingMs) {
        delay(CHROME_LINGER_MS + 50)
        tick++
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val showControls = controlsShowing(lastActivity, paused, panel, overControls) || scrubbingMs != null

    // The cursor goes with the controls in full screen, and comes back with them.
    LaunchedEffect(showControls, isFullscreen) {
        canvas.cursor = if (!showControls && isFullscreen) blankCursor else Cursor.getDefaultCursor()
    }

    // Esc on the app's side, and in full screen before the window's own handler
    // leaves full screen: an open list closes first.
    com.daview.app.data.OnEscape(enabled = panel != null) { panel = null }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            // The same keys mpv answers to, for when the app has the keyboard.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // With no picture the retry/return buttons should get their ordinary Space action.
                if (failure != null && event.key == Key.Spacebar) return@onPreviewKeyEvent false
                stirred()
                val char = event.utf16CodePoint.toChar()
                when {
                    event.key == Key.Spacebar -> { player?.togglePause(); true }
                    event.key == Key.DirectionLeft -> { player?.seekBy(-SEEK_STEP_SECONDS); true }
                    event.key == Key.DirectionRight -> { player?.seekBy(SEEK_STEP_SECONDS); true }
                    event.key == Key.DirectionUp -> { changeVolume(volume + 5); player?.showText("音量 $volume%", 800); true }
                    event.key == Key.DirectionDown -> { changeVolume(volume - 5); player?.showText("音量 $volume%", 800); true }
                    event.key == Key.M -> { toggleMute(); true }
                    event.key == Key.PageUp -> { player?.seekChapter(-1); true }
                    event.key == Key.PageDown -> { player?.seekChapter(1); true }
                    event.key == Key.F -> { toggleFullscreen(); true }
                    event.key == Key.J -> { stepSubtitle(); true }
                    char == '#' -> { stepAudio(); true }
                    char == '[' -> { stepSpeed(up = false); true }
                    char == ']' -> { stepSpeed(up = true); true }
                    char == '<' -> { skip(info.previousItemId); true }
                    char == '>' -> { skip(info.nextItemId); true }
                    // Closes an open list, then leaves full screen, and nothing
                    // more; ending playback is the close button or Q.
                    event.key == Key.Escape -> {
                        when {
                            panel != null -> panel = null
                            fullscreen?.value == true -> fullscreen.value = false
                        }
                        true
                    }
                    event.key == Key.Q -> { if (ending == null) ending = Ending.ABANDONED; true }
                    event.key == Key.Enter && failure == null -> { skipIntroAction(); true }
                    else -> false
                }
            }
    ) {
        val failed = failure
        if (failed == null) {
            SwingPanel(factory = { canvas }, modifier = Modifier.fillMaxSize())
        } else {
            PlaybackFailure(
                message = failed,
                detail = MpvNative.loadError,
                onRetry = { latestOnSkip(info.item.id, positionMs) },
                onClose = { finish(positionMs) }
            )
        }
    }

    // An opening the release marked as a chapter can be skipped, the way
    // streaming services offer it; the button shows while that chapter plays.
    val introEnd = chapter?.let { index ->
        chapters.getOrNull(index)?.takeIf { com.daview.shared.model.ChapterDto(it.startMs, it.title).isIntro }
            ?.let { chapters.getOrNull(index + 1)?.startMs ?: (it.startMs + 90_000) }
    }
    fun skipIntro() {
        introEnd?.let { player?.seekTo(it) }
    }
    LaunchedEffect(player) {
        val active = player ?: return@LaunchedEffect
        active.bindKey("ENTER", MSG_SKIP)
    }
    skipIntroAction = { skipIntro() }

    // Shown for as long as the opening plays, above the controls when they are up.
    if (failure == null && introEnd != null) {
        SkipOverlay(
            canvas = canvas,
            showControls = showControls,
            menuOpen = panel != null,
            onSkip = { skipIntro() }
        )
    }

    if (failure == null) {
        ControlsOverlay(
            canvas = canvas,
            visible = showControls && player != null,
            menuOpen = panel != null,
            onHoverChange = { overControls = it; stirred() }
        ) { collapsed ->
            Controls(
                info = info,
                positionMs = scrubbingMs ?: positionMs,
                durationMs = durationMs,
                paused = paused,
                volume = volume,
                muted = muted,
                speed = speed,
                buffering = buffering || loading,
                bufferingPercent = bufferingPercent,
                chapters = chapters,
                chapter = chapter,
                fullscreen = isFullscreen,
                superResolution = superResolution,
                videoHdr = videoHdr,
                adapterInUse = adapterInUse,
                panel = panel,
                selectedAudio = selectedAudio,
                selectedSubtitle = selectedSubtitle,
                mountedSubtitle = mountedSubtitle,
                subtitleDelayMs = screen.subtitleDelayMs,
                collapsed = collapsed,
                onStir = { stirred() },
                onPanel = { panel = it; stirred() },
                onTogglePause = { player?.togglePause(); stirred() },
                onScrub = { scrubbingMs = it; stirred() },
                onSeek = { target ->
                    player?.seekTo(target)
                    positionMs = target
                    scrubbingMs = null
                    stirred()
                },
                onVolume = { changeVolume(it) },
                onToggleMute = { toggleMute() },
                onSpeed = { changeSpeed(it); panel = null },
                onAudio = { chooseAudio(it); panel = null },
                onSubtitle = { chooseSubtitle(it); panel = null },
                onMountSubtitle = { panel = null; mountSubtitleFile() },
                onSubtitleDelay = { changeSubtitleDelay(it) },
                onSubtitleSize = { up ->
                    val value = ((player?.subtitleScale ?: screen.subtitleScale ?: subtitleScale) + if (up) 0.1f else -0.1f).coerceIn(0.5f, 3f)
                    player?.subtitleScale = value
                    // Kept for the next episode, as the delay is.
                    screen.subtitleScale = value
                    player?.showText("字幕大小 ${(value * 100).toInt()}%", 1200)
                    stirred()
                },
                onChapter = { index -> player?.seekChapterTo(index); panel = null; stirred() },
                onPrevious = info.previousItemId?.let { id -> { skip(id) } },
                onNext = info.nextItemId?.let { id -> { skip(id) } },
                onToggleFullscreen = { toggleFullscreen() },
                onClose = { if (ending == null) ending = Ending.ABANDONED }
            )
        }
    }
}

/** Whether the controls are wanted right now. */
private fun controlsShowing(lastActivity: Long, paused: Boolean, panel: Panel?, overControls: Boolean): Boolean =
    paused || panel != null || overControls || System.currentTimeMillis() - lastActivity < CHROME_LINGER_MS

/**
 * The controls' own window: transparent, undecorated, never focused, owned by
 * the main window and kept over the bottom of the picture as the window moves,
 * resizes and goes full screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ControlsOverlay(
    canvas: Canvas,
    visible: Boolean,
    menuOpen: Boolean,
    onHoverChange: (Boolean) -> Unit,
    content: @Composable (collapsed: Boolean) -> Unit
) {
    var bounds by remember { mutableStateOf<Rectangle?>(null) }
    DisposableEffect(canvas) {
        fun update() {
            bounds = if (canvas.isShowing) {
                runCatching { Rectangle(canvas.locationOnScreen, canvas.size) }.getOrNull()
            } else null
        }
        val component = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = update()
            override fun componentResized(e: ComponentEvent) = update()
            override fun componentShown(e: ComponentEvent) = update()
        }
        val ancestors = object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(e: HierarchyEvent) = update()
            override fun ancestorResized(e: HierarchyEvent) = update()
        }
        canvas.addComponentListener(component)
        canvas.addHierarchyBoundsListener(ancestors)
        update()
        onDispose {
            canvas.removeComponentListener(component)
            canvas.removeHierarchyBoundsListener(ancestors)
        }
    }
    // The first layout pass may not have happened yet when this composes.
    LaunchedEffect(Unit) {
        repeat(40) {
            if (bounds == null && canvas.isShowing) {
                bounds = runCatching { Rectangle(canvas.locationOnScreen, canvas.size) }.getOrNull()
            }
            if (bounds != null) return@LaunchedEffect
            delay(50)
        }
    }
    val area = bounds ?: return
    val owner = SwingUtilities.getWindowAncestor(canvas) ?: return
    val collapsed = area.width < WIDE_TRANSPORT_WIDTH
    val overlayHeight = controlsOverlayHeight(area.width, menuOpen).coerceAtMost(area.height)

    DialogWindow(
        visible = visible,
        onPreviewKeyEvent = { false },
        onKeyEvent = { false },
        create = {
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                isUndecorated = true
                // Where the platform cannot do per-pixel alpha the controls sit
                // on their own dark band, which is still over the picture.
                runCatching { isTransparent = true }
                // Clicks work; the keyboard stays with the player window.
                focusableWindowState = false
                isAutoRequestFocus = false
                title = "DAView 播放控制"
                keepOwnerFullscreen()
            }
        },
        dispose = { it.dispose() },
        update = { dialog ->
            dialog.keepOwnerFullscreen()
            dialog.setBounds(area.x, area.y + area.height - overlayHeight, area.width, overlayHeight)
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Enter) { onHoverChange(true) }
                .onPointerEvent(PointerEventType.Exit) { onHoverChange(false) }
                .onPointerEvent(PointerEventType.Move) { onHoverChange(true) }
        ) {
            DaViewTheme(darkTheme = true) { content(collapsed) }
        }
    }
}

/**
 * Stops this window from throwing the main window out of full screen.
 *
 * Skiko hangs a listener on every Compose window that, whenever the window is
 * shown, applies that window's own full-screen flag through
 * `GraphicsDevice.setFullScreenWindow` — and for a window that is not full
 * screen that call is `setFullScreenWindow(null)`, which ends full screen for
 * whichever window on the display had it. Every time the controls appeared,
 * the film dropped out of full screen. The listener is attached when the
 * window gets its native peer, so the peer is made here and the listener taken
 * off before the window is ever shown. None of these windows is ever full
 * screen itself, so nothing is lost.
 */
private fun java.awt.Window.keepOwnerFullscreen() {
    if (!isDisplayable) addNotify()
    // Internal to skiko, so matched by name.
    componentListeners
        .filter { it.javaClass.name == "org.jetbrains.skiko.FullscreenAdapter" }
        .forEach { removeComponentListener(it) }
}

/** A small "skip the opening" button over the bottom right of the picture. */
@Composable
private fun SkipOverlay(canvas: Canvas, showControls: Boolean, menuOpen: Boolean, onSkip: () -> Unit) {
    var bounds by remember { mutableStateOf<Rectangle?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (canvas.isShowing) bounds = runCatching { Rectangle(canvas.locationOnScreen, canvas.size) }.getOrNull()
            delay(500)
        }
    }
    val area = bounds ?: return
    val owner = SwingUtilities.getWindowAncestor(canvas) ?: return
    val lift = if (showControls) controlsOverlayHeight(area.width, menuOpen) else 0
    val skipWidth = 220.coerceAtMost(area.width)
    val skipHeight = 64.coerceAtMost(area.height)
    val margin = if (area.width >= skipWidth + 16) 16 else 0
    DialogWindow(
        visible = true,
        onPreviewKeyEvent = { false },
        onKeyEvent = { false },
        create = {
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                isUndecorated = true
                runCatching { isTransparent = true }
                focusableWindowState = false
                isAutoRequestFocus = false
                keepOwnerFullscreen()
            }
        },
        dispose = { it.dispose() },
        update = { dialog ->
            dialog.keepOwnerFullscreen()
            val x = area.x + area.width - skipWidth - margin
            val y = (area.y + area.height - lift - skipHeight - 8).coerceAtLeast(area.y)
            dialog.setBounds(x, y, skipWidth, skipHeight)
        }
    ) {
        DaViewTheme(darkTheme = true) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                SkipIntroButton(onClick = onSkip, label = "跳过片头（Enter）")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Controls(
    info: PlaybackInfoDto,
    positionMs: Long,
    durationMs: Long,
    paused: Boolean,
    volume: Int,
    muted: Boolean,
    speed: Float,
    buffering: Boolean,
    bufferingPercent: Int?,
    chapters: List<MpvPlayer.Chapter>,
    chapter: Int?,
    fullscreen: Boolean,
    superResolution: EnhancementState,
    videoHdr: EnhancementState,
    adapterInUse: String?,
    panel: Panel?,
    selectedAudio: Int?,
    selectedSubtitle: Int?,
    mountedSubtitle: String?,
    subtitleDelayMs: Long,
    collapsed: Boolean,
    onStir: () -> Unit,
    onPanel: (Panel?) -> Unit,
    onTogglePause: () -> Unit,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onSpeed: (Float) -> Unit,
    onAudio: (Int) -> Unit,
    onSubtitle: (Int?) -> Unit,
    onMountSubtitle: () -> Unit,
    onSubtitleDelay: (Long) -> Unit,
    onSubtitleSize: (Boolean) -> Unit,
    onChapter: (Int) -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (panel != null) {
            Box(Modifier.weight(1f, fill = false).fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.BottomEnd) {
                Surface(
                    color = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface,
                    shape = MaterialTheme.shapes.largeIncreased,
                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(max = PANEL_HEIGHT.dp)
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                panel.title, style = MaterialTheme.typography.titleMediumEmphasized,
                                modifier = Modifier.weight(1f).semantics { heading() }
                            )
                            ControlButton(Icons.Filled.Close, "关闭菜单（Esc）", onClick = { onPanel(null) })
                        }
                        HorizontalDivider(color = colors.outlineVariant)
                        androidx.compose.runtime.key(panel) {
                            PanelContent(
                                panel, info, speed, chapters, chapter, selectedAudio, selectedSubtitle, mountedSubtitle,
                                subtitleDelayMs, onSpeed, onAudio, onSubtitle, onMountSubtitle, onSubtitleDelay,
                                onSubtitleSize, onChapter, onPanel, volume, muted, onVolume, onToggleMute
                            )
                        }
                    }
                }
            }
        }
        Surface(
            color = colors.surfaceContainer,
            contentColor = colors.onSurface,
            shape = MaterialTheme.shapes.extraLargeIncreased,
            modifier = Modifier.widthIn(max = 1360.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val currentChapter = chapter?.let(chapters::getOrNull)?.title?.takeIf { it.isNotBlank() }
                PlaybackMetadata(
                    title = displayTitle(info),
                    chapter = currentChapter,
                    buffering = buffering,
                    bufferingPercent = bufferingPercent,
                    adapterInUse = adapterInUse,
                    superResolution = superResolution,
                    videoHdr = videoHdr
                )
                PlaybackTimeDirection {
                    SeekBar(positionMs, durationMs, chapters, onScrub, onSeek, onStir)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(color = colors.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge) {
                            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                ControlButton(Icons.Filled.SkipPrevious, "上一集（<）", enabled = onPrevious != null, onClick = { onPrevious?.invoke() })
                                PlaybackControlTip(if (paused) "播放（空格）" else "暂停（空格）") {
                                    PlaybackMainButton(showPause = !paused, onClick = onTogglePause, modifier = Modifier.size(width = 64.dp, height = 48.dp),
                                        label = if (paused) "播放（空格）" else "暂停（空格）")
                                }
                                ControlButton(Icons.Filled.SkipNext, "下一集（>）", enabled = onNext != null, onClick = { onNext?.invoke() })
                            }
                        }
                        if (!collapsed) {
                            Spacer(Modifier.width(4.dp))
                            VolumeButton(muted, volume, onToggleMute)
                            VolumeSlider(muted, volume, onVolume, Modifier.width(84.dp))
                            Text(
                                "${if (muted) 0 else volume}%", Modifier.width(44.dp),
                                color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.End, maxLines = 1
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (collapsed) {
                            VolumeButton(muted, volume, onToggleMute)
                            ControlButton(Icons.Filled.MoreVert, "更多播放选项", active = panel != null, onClick = {
                                onPanel(if (panel == null) Panel.OPTIONS else null)
                            })
                        } else {
                            TextButton(
                                onClick = { onPanel(if (panel == Panel.SPEED) null else Panel.SPEED) },
                                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp, max = 80.dp).pointerHoverIcon(PointerIcon.Hand)
                                    .semantics { contentDescription = "播放速度 ${formatSpeed(speed)}" },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    formatSpeed(speed),
                                    style = if (panel == Panel.SPEED) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                                    color = if (speed != 1f || panel == Panel.SPEED) colors.primary else colors.onSurfaceVariant
                                )
                            }
                            if (chapters.isNotEmpty()) {
                                PanelButton(Panel.CHAPTERS, panel, Icons.AutoMirrored.Filled.Toc, "章节（PgUp / PgDn）", onPanel)
                            }
                            PanelButton(Panel.AUDIO, panel, Icons.Filled.Audiotrack, "音轨（#）", onPanel)
                            PanelButton(Panel.SUBTITLE, panel, Icons.Filled.ClosedCaption, "字幕（J）", onPanel)
                            PanelButton(Panel.SUBTITLE_TUNING, panel, Icons.Filled.Tune, "字幕延迟与大小", onPanel)
                        }
                        ControlButton(
                            if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            if (fullscreen) "退出全屏（Esc / F）" else "全屏（F / 双击画面）", onClick = onToggleFullscreen
                        )
                        ControlButton(Icons.Filled.Close, "结束播放（Q）", onClick = onClose)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaybackMetadata(
    title: String,
    chapter: String?,
    buffering: Boolean,
    bufferingPercent: Int?,
    adapterInUse: String?,
    superResolution: EnhancementState,
    videoHdr: EnhancementState
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (buffering) {
                Surface(color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer, shape = CircleShape) {
                    Text(
                        "缓冲中" + (bufferingPercent?.let { " $it%" } ?: "…"),
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        if (chapter != null || adapterInUse != null || enhancementSuffix(superResolution) != null || enhancementSuffix(videoHdr) != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chapter?.let {
                    Text(it, Modifier.weight(1f), color = if (buffering) colors.onSurfaceVariant else colors.tertiary,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } ?: Spacer(Modifier.weight(1f))
                adapterInUse?.let {
                    Text(it, Modifier.widthIn(max = 148.dp), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                EnhancementLabel("RTX 超分", superResolution)
                EnhancementLabel("RTX HDR", videoHdr)
            }
        }
    }
}

@Composable
private fun VolumeButton(muted: Boolean, volume: Int, onClick: () -> Unit) {
    ControlButton(
        if (muted || volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
        if (muted) "取消静音（M）" else "静音（M）", onClick = onClick
    )
}

@Composable
private fun VolumeSlider(muted: Boolean, volume: Int, onVolume: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val value = if (muted) 0 else volume
    Slider(
        value = value / 100f,
        onValueChange = { onVolume((it * 100).toInt()) },
        colors = SliderDefaults.colors(thumbColor = colors.onSurfaceVariant, activeTrackColor = colors.secondary),
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "音量"
            stateDescription = "$value%"
        }
    )
}

@Composable
private fun PanelButton(panel: Panel, openPanel: Panel?, icon: ImageVector, tip: String, onPanel: (Panel?) -> Unit) {
    ControlButton(icon, tip, active = openPanel == panel, onClick = { onPanel(if (openPanel == panel) null else panel) })
}

/**
 * The seek bar, with the chapters marked on it, the time under the pointer
 * while it hovers, and the total that turns into "time left" when clicked.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    chapters: List<MpvPlayer.Chapter>,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onStir: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val seekable = durationMs > 0
    // A click on the track calls both callbacks inside one frame; retain the newly selected value.
    var dragged by remember { mutableStateOf<Float?>(null) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    var showRemaining by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            hoverFraction?.takeIf { seekable }?.let { "→ " + formatDuration((it * durationMs).toLong()) }
                ?: if (positionMs > 0) formatDuration(positionMs) else "0:00",
            color = colors.primary,
            style = MaterialTheme.typography.labelMediumEmphasized,
            maxLines = 1,
            modifier = Modifier.width(76.dp).padding(start = 4.dp)
        )
        Box(
            Modifier.weight(1f)
                .onPointerEvent(PointerEventType.Move) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val width = size.width.takeIf { it > 0 } ?: return@onPointerEvent
                    hoverFraction = (change.position.x / width).coerceIn(0f, 1f)
                    onStir()
                }
                .onPointerEvent(PointerEventType.Exit) { hoverFraction = null }
        ) {
            Slider(
                value = dragged ?: if (seekable) (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f,
                onValueChange = {
                    dragged = it
                    if (seekable) onScrub((it * durationMs).toLong())
                },
                onValueChangeFinished = {
                    val at = dragged
                    dragged = null
                    if (seekable && at != null) onSeek((at * durationMs).toLong())
                },
                enabled = seekable,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "播放进度"
                    stateDescription = "${if (positionMs > 0) formatDuration(positionMs) else "0:00"} / ${formatDuration(durationMs)}"
                }
            )
            // Chapter marks sit below the track: they don't obscure the playhead or intercept a seek.
            if (seekable && chapters.size > 1) {
                ComposeCanvas(Modifier.matchParentSize()) {
                    val inset = 4.dp.toPx()
                    chapters.drop(1).forEach { mark ->
                        val x = inset + (size.width - inset * 2).coerceAtLeast(0f) * (mark.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        drawCircle(color = colors.tertiary, radius = 2.dp.toPx(), center = Offset(x, size.height - 3.dp.toPx()))
                    }
                }
            }
        }
        val endTime = if (showRemaining && seekable) "−" + formatDuration((durationMs - positionMs).coerceAtLeast(0)).replace("--:--", "0:00")
            else formatDuration(durationMs)
        Box(
            Modifier.width(76.dp).heightIn(min = 48.dp)
                .clickable(enabled = seekable, role = Role.Button, onClickLabel = if (showRemaining) "显示总时长" else "显示剩余时间") {
                    showRemaining = !showRemaining
                }
                .then(if (seekable) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .padding(end = 4.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(endTime, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.End)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelContent(
    panel: Panel,
    info: PlaybackInfoDto,
    speed: Float,
    chapters: List<MpvPlayer.Chapter>,
    chapter: Int?,
    selectedAudio: Int?,
    selectedSubtitle: Int?,
    mountedSubtitle: String?,
    subtitleDelayMs: Long,
    onSpeed: (Float) -> Unit,
    onAudio: (Int) -> Unit,
    onSubtitle: (Int?) -> Unit,
    onMountSubtitle: () -> Unit,
    onSubtitleDelay: (Long) -> Unit,
    onSubtitleSize: (Boolean) -> Unit,
    onChapter: (Int) -> Unit,
    onPanel: (Panel?) -> Unit,
    volume: Int,
    muted: Boolean,
    onVolume: (Int) -> Unit,
    onToggleMute: () -> Unit
) {
    val itemPadding = PaddingValues(4.dp)
    when (panel) {
        Panel.AUDIO -> LazyColumn(contentPadding = itemPadding) {
            val entries = info.item.mediaStreams.filter { it.type == StreamType.AUDIO }
            if (entries.isEmpty()) item { PanelNote("没有可选音轨") }
            itemsIndexed(entries) { _, stream ->
                PanelChoice(stream.displayTitle, chosen = stream.index == selectedAudio) { onAudio(stream.index) }
            }
        }
        Panel.SUBTITLE -> LazyColumn(contentPadding = itemPadding) {
            val entries = info.item.mediaStreams.filter { it.type == StreamType.SUBTITLE }
            item { PanelChoice("关闭字幕", chosen = selectedSubtitle == null && mountedSubtitle == null) { onSubtitle(null) } }
            itemsIndexed(entries) { _, stream ->
                PanelChoice(stream.displayTitle, chosen = stream.index == selectedSubtitle && mountedSubtitle == null) {
                    onSubtitle(stream.index)
                }
            }
            mountedSubtitle?.let { name -> item { PanelNote("当前字幕：$name（本次加载）") } }
            item {
                HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                PanelChoice("从电脑加载字幕文件…", onClick = onMountSubtitle)
            }
        }
        Panel.SUBTITLE_TUNING -> Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("延迟 ${formatDelay(subtitleDelayMs)}", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSubtitleDelay(-100) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("−0.1 秒") }
                OutlinedButton(onClick = { onSubtitleDelay(100) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("+0.1 秒") }
            }
            Text("大小", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSubtitleSize(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("缩小") }
                OutlinedButton(onClick = { onSubtitleSize(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("放大") }
            }
            Text(
                "默认大小在「设置 → 播放」里改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Panel.SPEED -> LazyColumn(contentPadding = itemPadding) {
            item { PanelNote("快捷键 [ / ]") }
            itemsIndexed(SPEEDS) { _, value ->
                PanelChoice(formatSpeed(value), chosen = kotlin.math.abs(value - speed) < 0.001f) { onSpeed(value) }
            }
        }
        Panel.CHAPTERS -> LazyColumn(contentPadding = itemPadding) {
            itemsIndexed(chapters) { index, item ->
                PanelChoice(
                    item.title.ifBlank { "第 ${index + 1} 章" },
                    chosen = index == chapter,
                    supporting = formatDuration(item.startMs)
                ) { onChapter(index) }
            }
        }
        Panel.OPTIONS -> Column(Modifier.verticalScroll(rememberScrollState()).padding(4.dp)) {
            PlaybackTimeDirection {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    VolumeButton(muted, volume, onToggleMute)
                    VolumeSlider(muted, volume, onVolume, Modifier.weight(1f))
                    Text("${if (muted) 0 else volume}%", Modifier.width(52.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            PanelChoice("播放速度 ${formatSpeed(speed)}", icon = Icons.Filled.Tune) { onPanel(Panel.SPEED) }
            if (chapters.isNotEmpty()) {
                PanelChoice("章节（PgUp / PgDn）", icon = Icons.AutoMirrored.Filled.Toc) { onPanel(Panel.CHAPTERS) }
            }
            PanelChoice("音轨（#）", icon = Icons.Filled.Audiotrack) { onPanel(Panel.AUDIO) }
            PanelChoice("字幕（J）", icon = Icons.Filled.ClosedCaption) { onPanel(Panel.SUBTITLE) }
            PanelChoice("字幕延迟与大小", icon = Icons.Filled.Tune) { onPanel(Panel.SUBTITLE_TUNING) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PanelChoice(
    label: String,
    chosen: Boolean? = null,
    supporting: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val action = if (chosen != null) Modifier.selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
        else Modifier.clickable(role = Role.Button, onClick = onClick)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (chosen == true) colors.secondaryContainer else Color.Transparent,
        contentColor = if (chosen == true) colors.onSecondaryContainer else colors.onSurface
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).then(action).pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val leading = if (chosen == true) Icons.Filled.Check else icon
            Box(Modifier.size(24.dp)) {
                if (leading != null) Icon(leading, contentDescription = null)
            }
            Text(
                label,
                Modifier.weight(1f),
                style = if (chosen == true) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge
            )
            supporting?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = if (chosen == true) colors.onSecondaryContainer else colors.tertiary)
            }
        }
    }
}

@Composable
private fun PanelNote(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun ControlButton(icon: ImageVector, tip: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    PlaybackControlTip(tip) {
        PlaybackToolButton(icon, tip, onClick, enabled = enabled, active = active)
    }
}

/** Keep labels above buttons and inside the separate, non-focusable controls window. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaybackControlTip(tip: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.small
            ) {
                Text(tip, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
        delayMillis = 400,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.TopCenter,
            alignment = Alignment.TopCenter,
            offset = DpOffset(0.dp, (-4).dp)
        )
    ) { content() }
}

/** A read-only status in the same register as the rest of the bar. */
@Composable
private fun EnhancementLabel(label: String, state: EnhancementState) {
    val suffix = enhancementSuffix(state) ?: return
    Text(
        "$label · $suffix",
        style = MaterialTheme.typography.labelSmall,
        color = if (state == EnhancementState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

/**
 * Waits for the canvas to have a native peer and returns its handle: an HWND on
 * Windows, an NSView on macOS, an X11 window id on Linux — which is what mpv's
 * `wid` option takes on each.
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

/** The show, the episode number and the episode, which is how the rest of the app names one too. */
private fun displayTitle(info: PlaybackInfoDto): String =
    info.item.seriesName?.let { series ->
        listOfNotNull(series, info.item.episodeLabel, info.item.name).joinToString(" · ")
    } ?: info.item.name

private fun formatSpeed(value: Float): String =
    if (value == 1f) "1.0×" else "${(value * 100).toInt() / 100.0}×"

private fun formatDelay(ms: Long): String =
    (if (ms > 0) "+" else "") + String.format(java.util.Locale.ROOT, "%.1f 秒", ms / 1000.0)

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
        ?.let { "mpv 未接受快捷键 ${it.joinToString(" ")}，请用控制条上的按钮" }
).takeIf { it.isNotEmpty() }?.joinToString("\n")

/** Null for a feature nobody turned on: there is nothing to say about it. */
private fun enhancementSuffix(state: EnhancementState): String? = when (state) {
    EnhancementState.ACTIVE -> "已启用"
    EnhancementState.REQUESTED -> "等待中"
    EnhancementState.UNSUPPORTED -> "不适用"
    EnhancementState.FAILED -> "失败"
    EnhancementState.OFF -> null
}

/** An invisible cursor, for a full-screen picture nobody is pointing at. */
private val blankCursor: Cursor by lazy {
    Toolkit.getDefaultToolkit().createCustomCursor(
        BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB),
        Point(0, 0),
        "daview-blank"
    )
}

/**
 * Names for the keys mpv hands back. They only have to be unique among the
 * messages this client listens for.
 */
private const val MSG_AUDIO = "daview-audio"
private const val MSG_SUBTITLE = "daview-subtitle"
private const val MSG_FULLSCREEN = "daview-fullscreen"
private const val MSG_ESCAPE = "daview-escape"
private const val MSG_PREVIOUS = "daview-previous"
private const val MSG_NEXT = "daview-next"
private const val MSG_SKIP = "daview-skip-intro"
private const val MSG_SUBTITLE_VISIBILITY = "daview-subtitle-visibility"

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** How often the controls read mpv. Four times a second reads as live. */
private const val TICK_MS = 250L

/** How long the controls stay after the pointer stops. */
private const val CHROME_LINGER_MS = 3000L

/** How long a first click waits to see whether it is the start of a double click. */
/** The system's double-click time; Windows' default is 500 ms, not the 250 once assumed here. */
private fun doubleClickMs(): Long =
    (java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int)?.toLong() ?: 500L

/** mpv's own step, so ← and → mean the same whichever side has the keyboard. */
private const val SEEK_STEP_SECONDS = 5

/** AWT uses logical pixels. These budgets resize only the overlay, never the native picture. */
private const val WIDE_TRANSPORT_WIDTH = 760
private const val BAR_HEIGHT = 184
private const val PANEL_HEIGHT = 300

private fun controlsOverlayHeight(width: Int, menuOpen: Boolean): Int =
    BAR_HEIGHT + (if (width < WIDE_TRANSPORT_WIDTH) 8 else 0) + (if (menuOpen) PANEL_HEIGHT else 0)

private const val OSD_MS = 3000
private const val FAILURE_MS = 8000
private const val PROGRESS_INTERVAL_MS = 5000L
private const val ENHANCEMENT_REPORT_MS = 2500L

private const val MAX_LUMA_NOTICE =
    "RTX Video HDR 的峰值亮度按 1000 nits 估算，与 NVIDIA App 里的设置不一定一致。"
