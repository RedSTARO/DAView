package com.daview.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.daview.app.platform.AndroidFilePicker
import com.daview.app.subtitle.AssScript
import com.daview.app.subtitle.AssSource
import com.daview.app.subtitle.AssSubtitleView
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.PlaybackInfoDto
import com.daview.shared.model.SUBTITLE_OFF
import com.daview.shared.model.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ExoPlayer-backed player. Media3 handles Matroska with h264/hevc, AAC/AC3 and
 * SRT natively, so the DAView stream URL can be played directly and external
 * subtitle files are attached as side-loaded subtitle configurations — except
 * ASS, which DAView renders itself over the picture. See [AssSubtitleView].
 *
 * The screen composes one of these per session. What the viewer set that should
 * outlive one file — the rotation lock, the subtitle delay, the speed — lives in
 * [PlayerScreenState].
 */
@androidx.annotation.OptIn(UnstableApi::class)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnEnded by rememberUpdatedState(onEnded)
    val latestOnClose by rememberUpdatedState(onClose)
    val latestOnSkip by rememberUpdatedState(onSkip)

    val audioStreams = remember(info) { info.item.mediaStreams.filter { it.type == StreamType.AUDIO } }
    val subtitleStreams = remember(info) { info.item.mediaStreams.filter { it.type == StreamType.SUBTITLE } }

    var audioMenu by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var tuningMenu by remember { mutableStateOf(false) }
    var chapterMenu by remember { mutableStateOf(false) }
    var selectedAudio by remember { mutableStateOf(info.audioStreamIndex) }
    var selectedSubtitle by remember { mutableStateOf(info.subtitleStreamIndex?.takeIf { it != SUBTITLE_OFF }) }
    // Whether the viewer asked for no subtitles, as opposed to not having asked
    // for any yet. Only the first of those should stop media3 selecting a track
    // of its own — a container can carry subtitles the scan never recorded.
    var subtitlesOff by remember { mutableStateOf(info.subtitleStreamIndex == SUBTITLE_OFF) }
    /** A subtitle file picked from the phone for this session only. */
    var mountedSubtitle by remember { mutableStateOf<String?>(null) }
    /** The label of a just-mounted text subtitle, selected once media3 lists it. */
    var pendingMount by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var locked by screenLocked(screen)
    /** What a gesture is doing, shown briefly in the middle of the picture. */
    var hint by remember { mutableStateOf<String?>(null) }
    var boosting by remember { mutableStateOf(false) }

    val activity = remember(context) { context.findActivityOrNull() }
    val pipSupported = remember(activity) { activity != null && PipRequest.supported(activity) }

    // Whether the app's own overlay is on screen. It rides with media3's own
    // transport controls, so the picture is clean when they go.
    var controlsVisible by remember { mutableStateOf(true) }
    val inPictureInPicture = rememberInPictureInPicture()

    // ASS files are not handed to media3 at all — they are drawn by
    // AssSubtitleView instead, which keeps the typesetting media3's parser strips.
    val sideLoaded = remember(info) {
        subtitleStreams
            .filter { it.isExternal && !it.isAss }
            .mapNotNull { stream ->
                val url = info.subtitleUrls[stream.index] ?: return@mapNotNull null
                subtitleConfiguration(Uri.parse(url), mimeFor(stream.codec), stream.language, stream.displayTitle, stream.isDefault)
            }
    }

    val player = remember {
        // The stream URL is the app's own server over http, which may answer
        // with a redirect to the storage's https link; ExoPlayer refuses
        // http -> https redirects unless told otherwise.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("DAView/1.0")
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
            )
            // Audio focus is what makes a phone call, an alarm or another app
            // pause the film instead of talking over it.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build().apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(info.streamUrl)
                        .setSubtitleConfigurations(sideLoaded)
                        .build()
                )
                seekTo(info.startPositionMs)
                playbackParameters = PlaybackParameters(screen.speed)
                if (subtitlesOff) {
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
                playWhenReady = true
                prepare()
            }
    }

    /** Subtitle choice as the server records it: off is a choice, a file from the phone is not. */
    fun report() {
        val subtitle = when {
            subtitlesOff -> SUBTITLE_OFF
            mountedSubtitle != null -> null
            else -> selectedSubtitle
        }
        latestOnProgress(player.currentPosition, !player.isPlaying, selectedAudio, subtitle)
    }

    DisposableEffect(player) {
        var initialApplied = false
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("DAView", "playback failed", error)
                playbackError = describePlaybackError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) latestOnEnded(player.duration.coerceAtLeast(0))
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isEmpty()) return
                // The server's pick — the language preference, or what was
                // chosen on the episode before — applied once media3 has listed
                // the tracks. Media3 would otherwise play its own default.
                if (!initialApplied) {
                    initialApplied = true
                    val audioApplied = audioStreams.firstOrNull { it.index == selectedAudio }
                        ?.let { selectTrack(player, C.TRACK_TYPE_AUDIO, it, audioStreams) } ?: false
                    if (!subtitlesOff) {
                        subtitleStreams.firstOrNull { it.index == selectedSubtitle }
                            ?.takeIf { !(it.isExternal && it.isAss) }
                            ?.let { selectTrack(player, C.TRACK_TYPE_TEXT, it, subtitleStreams) }
                    }
                    // Applied, the change comes back through here; not applied,
                    // what media3 picked is what plays, and the tick in the menu
                    // has to say so.
                    if (audioApplied) return
                }
                pendingMount?.let { label ->
                    val group = tracks.groups.firstOrNull { group ->
                        group.type == C.TRACK_TYPE_TEXT &&
                            (0 until group.length).any { group.getTrackFormat(it).label == label }
                    }
                    if (group != null) {
                        pendingMount = null
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                            .build()
                    }
                }
                // Media3's own settings menu can change the audio track too;
                // read it back so the choice is recorded whichever menu made it.
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val playing = audioGroups.indexOfFirst { it.isSelected }
                if (playing >= 0) {
                    val stream = streamForGroup(audioGroups, playing, audioStreams)
                    if (stream != null && stream.index != selectedAudio) {
                        selectedAudio = stream.index
                        report()
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // A session publishes what is playing to the platform, which is what makes
    // a headset button or a Bluetooth remote reach this player at all.
    DisposableEffect(player) {
        val session = runCatching {
            MediaSession.Builder(context, player)
                .setId("daview-" + info.sessionId)
                .build()
        }.getOrNull()
        onDispose { session?.release() }
    }

    // The video shape the corner window should take, and the size the subtitle
    // overlay needs: script coordinates are relative to the picture.
    var videoSize by remember { mutableStateOf(0 to 0) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        PipRequest.wanted = true
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    PipRequest.aspect = Rational(size.width, size.height)
                    videoSize = size.width to size.height
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            // Media3's own settings menu has a speed list too. Its choice is
            // kept like the app's own; the long press's double speed is not.
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                if (!boosting && abs(parameters.speed - screen.speed) > 0.001f) screen.speed = parameters.speed
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            PipRequest.wanted = false
            PipRequest.aspect = null
        }
    }

    // The ASS script on screen, if the chosen subtitle is one DAView draws
    // itself, and the view that draws it.
    var assScript by remember { mutableStateOf<AssScript?>(null) }
    var assView by remember { mutableStateOf<AssSubtitleView?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(selectedSubtitle, mountedSubtitle, subtitlesOff) {
        // A file from the phone set its own renderer up when it was picked.
        if (mountedSubtitle != null) return@LaunchedEffect
        val stream = subtitleStreams.firstOrNull { it.index == selectedSubtitle }
        val url = stream?.takeIf { it.isExternal && it.isAss && !subtitlesOff }?.let { info.subtitleUrls[it.index] }
        assScript = if (url == null) {
            null
        } else {
            AssSource.load(url)
                .onFailure { android.util.Log.w("DAView", "subtitle $url", it) }
                .getOrNull()
        }
        // Two renderers drawing at once would double every line.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, assScript != null || subtitlesOff)
            .build()
    }

    // Leaving the app pauses the film — unless it went to the corner, which is
    // the one case where leaving means "keep it running".
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !inPictureInPicture) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            val position = player.currentPosition
            player.release()
            latestOnClose(position)
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.isPlaying || player.currentPosition > 0) report()
        }
    }

    // Size of media3's own text subtitles. An ASS script keeps the sizes its
    // author typeset and is not scaled.
    val effectiveScale = screen.subtitleScale ?: subtitleScale
    LaunchedEffect(playerView, effectiveScale) {
        playerView?.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * effectiveScale)
    }

    // Locked, the back gesture does nothing either: it is the easiest touch of
    // all to make by accident.
    androidx.activity.compose.BackHandler(enabled = locked && !inPictureInPicture) {
        hint = "屏幕已锁定，点左侧的锁解锁"
    }
    LaunchedEffect(assView, screen.subtitleDelayMs) {
        assView?.delayMs = screen.subtitleDelayMs
    }

    // Chapters, where the file has them, and the opening among them if the
    // release marks one — that is what the skip button acts on.
    val chapters = info.item.chapters
    var positionMs by remember { mutableLongStateOf(info.startPositionMs) }
    if (chapters.isNotEmpty()) {
        LaunchedEffect(player) {
            while (true) {
                positionMs = player.currentPosition
                delay(500)
            }
        }
    }
    val currentChapter = chapters.indexOfLast { it.startMs <= positionMs }.takeIf { it >= 0 }
    val introEnd = currentChapter?.let { index ->
        chapters[index].takeIf { it.isIntro }?.let { chapters.getOrNull(index + 1)?.startMs }
    }

    fun mountSubtitleFile() {
        scope.launch {
            val picked = AndroidFilePicker.pickBytes(arrayOf("*/*")) ?: return@launch
            when (picked.extension) {
                "ass", "ssa" -> {
                    // Drawn by DAView, like the scripts that come with the file.
                    val script = withContext(Dispatchers.Default) {
                        runCatching { AssScript.parse(AssSource.decode(picked.bytes)) }.getOrNull()
                    }
                    if (script == null) {
                        hint = "无法读取这个字幕文件"
                        return@launch
                    }
                    assScript = script
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
                "srt", "vtt", "webvtt" -> {
                    // Media3 takes side-loaded subtitles as part of the media
                    // item, so the item is set again, at the same position, with
                    // the file added. It is decoded here so a GBK file reads.
                    val file = withContext(Dispatchers.IO) {
                        File(context.cacheDir, "subtitle-${System.currentTimeMillis()}.${picked.extension}").apply {
                            writeText(AssSource.decode(picked.bytes), Charsets.UTF_8)
                        }
                    }
                    val added = subtitleConfiguration(Uri.fromFile(file), mimeFor(picked.extension), null, picked.name, false)
                    assScript = null
                    pendingMount = picked.name
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                    player.setMediaItem(
                        MediaItem.Builder()
                            .setUri(info.streamUrl)
                            .setSubtitleConfigurations(sideLoaded + added)
                            .build(),
                        player.currentPosition
                    )
                    player.prepare()
                }
                else -> {
                    hint = "只支持 ASS、SSA、SRT、VTT 字幕文件"
                    return@launch
                }
            }
            mountedSubtitle = picked.name
            subtitlesOff = false
            hint = "已加载 ${picked.name}"
        }
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1200)
            hint = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    // Without this a stalled network stream is a still frame
                    // with nothing to say it is loading.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    // Media3's previous / next act on a playlist of one; the
                    // episode buttons in the top bar take their place.
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    // The app draws its own subtitle menu, and it is the only
                    // one that knows about side-loaded subtitle files.
                    setShowSubtitleButton(false)
                    // PlayerView keeps a frame between the picture and the
                    // controls for exactly this.
                    overlayFrameLayout?.addView(
                        AssSubtitleView(ctx)
                            .apply { position = { player.currentPosition } }
                            .also { assView = it }
                    )
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == android.view.View.VISIBLE
                        }
                    )
                    installGestures(
                        view = this,
                        player = player,
                        isLocked = { locked },
                        onHint = { hint = it },
                        onBoost = { boosting = it }
                    )
                    playerView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.useController = !inPictureInPicture && !locked
                assView?.apply {
                    script = assScript
                    videoWidth = videoSize.first
                    videoHeight = videoSize.second
                    driving = playing
                    // While paused nothing asks for frames, so a change of
                    // subtitle or of size needs one repaint of its own.
                    if (!playing) invalidate()
                }
            }
        )

        if (!inPictureInPicture) {
            (if (boosting) "2× 快进中" else hint)?.let { text ->
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(if (boosting) Alignment.TopCenter else Alignment.Center)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(top = if (boosting) 24.dp else 0.dp)
                ) {
                    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }

        // Stays for as long as the opening plays, with the controls or without.
        if (introEnd != null && !inPictureInPicture && !locked) {
            Button(
                onClick = { player.seekTo(introEnd) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(end = 24.dp, bottom = if (controlsVisible) 120.dp else 32.dp)
            ) { Text("跳过片头") }
        }

        // Locked, the one thing on screen is the way back.
        if (locked && !inPictureInPicture) {
            IconButton(
                onClick = { locked = false },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(start = 16.dp)
                    .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.extraLarge)
            ) {
                Icon(Icons.Filled.Lock, contentDescription = "已锁定，点按解锁", tint = Color.White)
            }
        }

        // Rides with media3's own controls so the picture is clean when they go.
        AnimatedVisibility(
            visible = controlsVisible && !inPictureInPicture && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            val title = info.item.seriesName?.let { series ->
                listOfNotNull(series, info.item.episodeLabel, info.item.name).distinct().joinToString(" · ")
            } ?: info.item.name

            // A scrim under the white text: over a bright frame the title and
            // the buttons were white on near-white.
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                val buttons: @Composable RowScope.() -> Unit = {
                    info.previousItemId?.let { previous ->
                        IconButton(onClick = { latestOnSkip(previous, player.currentPosition) }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "上一集", tint = Color.White)
                        }
                    }
                    info.nextItemId?.let { next ->
                        IconButton(onClick = { latestOnSkip(next, player.currentPosition) }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "下一集", tint = Color.White)
                        }
                    }
                    Box {
                        IconButton(onClick = { audioMenu = true }) {
                            Icon(Icons.Filled.Audiotrack, contentDescription = "音轨", tint = Color.White)
                        }
                        DropdownMenu(audioMenu, onDismissRequest = { audioMenu = false }) {
                            if (audioStreams.isEmpty()) {
                                DropdownMenuItem(text = { Text("没有可选的音轨") }, onClick = { audioMenu = false }, enabled = false)
                            }
                            audioStreams.forEach { stream ->
                                DropdownMenuItem(
                                    leadingIcon = { ChosenMark(stream.index == selectedAudio) },
                                    text = { Text(stream.displayTitle) },
                                    onClick = {
                                        audioMenu = false
                                        selectedAudio = stream.index
                                        selectTrack(player, C.TRACK_TYPE_AUDIO, stream, audioStreams)
                                        report()
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { subtitleMenu = true }) {
                            Icon(Icons.Filled.ClosedCaption, contentDescription = "字幕", tint = Color.White)
                        }
                        DropdownMenu(subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                            DropdownMenuItem(
                                leadingIcon = { ChosenMark(subtitlesOff) },
                                text = { Text("关闭字幕") },
                                onClick = {
                                    subtitleMenu = false
                                    subtitlesOff = true
                                    mountedSubtitle = null
                                    pendingMount = null
                                    report()
                                }
                            )
                            subtitleStreams.forEach { stream ->
                                DropdownMenuItem(
                                    leadingIcon = {
                                        ChosenMark(!subtitlesOff && mountedSubtitle == null && stream.index == selectedSubtitle)
                                    },
                                    text = { Text(stream.displayTitle) },
                                    onClick = {
                                        subtitleMenu = false
                                        selectedSubtitle = stream.index
                                        subtitlesOff = false
                                        mountedSubtitle = null
                                        pendingMount = null
                                        // An ASS file has no media3 track to select —
                                        // the overlay picks it up from selectedSubtitle.
                                        if (!(stream.isExternal && stream.isAss)) {
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                .build()
                                            selectTrack(player, C.TRACK_TYPE_TEXT, stream, subtitleStreams)
                                        }
                                        report()
                                    }
                                )
                            }
                            mountedSubtitle?.let { name ->
                                DropdownMenuItem(
                                    leadingIcon = { ChosenMark(!subtitlesOff) },
                                    text = { Text("$name（手机上的文件）") },
                                    onClick = { subtitleMenu = false }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("从手机加载字幕文件…") },
                                onClick = {
                                    subtitleMenu = false
                                    mountSubtitleFile()
                                }
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { tuningMenu = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = "字幕延迟、大小与播放速度", tint = Color.White)
                        }
                        DropdownMenu(tuningMenu, onDismissRequest = { tuningMenu = false }) {
                            val assShowing = assScript != null
                            Text(
                                if (assShowing) "字幕延迟 ${formatDelay(screen.subtitleDelayMs)}" else "字幕延迟只能调 ASS / SSA 字幕",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            if (assShowing) {
                                Row(Modifier.padding(horizontal = 8.dp)) {
                                    TextButton(onClick = { screen.subtitleDelayMs -= 100 }) { Text("−0.1 秒") }
                                    TextButton(onClick = { screen.subtitleDelayMs += 100 }) { Text("+0.1 秒") }
                                    TextButton(onClick = { screen.subtitleDelayMs = 0 }, enabled = screen.subtitleDelayMs != 0L) { Text("归零") }
                                }
                            }
                            HorizontalDivider()
                            Text(
                                if (assShowing) "字幕大小：ASS / SSA 按原样显示" else "字幕大小 ${(effectiveScale * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            if (!assShowing) {
                                Row(Modifier.padding(horizontal = 8.dp)) {
                                    TextButton(onClick = { screen.subtitleScale = (effectiveScale - 0.1f).coerceIn(0.5f, 3f) }) { Text("小一点") }
                                    TextButton(onClick = { screen.subtitleScale = (effectiveScale + 0.1f).coerceIn(0.5f, 3f) }) { Text("大一点") }
                                    TextButton(onClick = { screen.subtitleScale = null }, enabled = screen.subtitleScale != null) { Text("按设置") }
                                }
                            }
                            HorizontalDivider()
                            Text(
                                "播放速度",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            SPEEDS.forEach { value ->
                                DropdownMenuItem(
                                    leadingIcon = { ChosenMark(abs(screen.speed - value) < 0.001f) },
                                    text = { Text(if (value == 1f) "正常" else "$value×") },
                                    onClick = {
                                        tuningMenu = false
                                        screen.speed = value
                                        player.playbackParameters = PlaybackParameters(value)
                                    }
                                )
                            }
                        }
                    }
                    if (chapters.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { chapterMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Toc, contentDescription = "章节", tint = Color.White)
                            }
                            DropdownMenu(chapterMenu, onDismissRequest = { chapterMenu = false }) {
                                chapters.forEachIndexed { index, chapter ->
                                    DropdownMenuItem(
                                        leadingIcon = { ChosenMark(index == currentChapter) },
                                        text = { Text("${formatDuration(chapter.startMs)}  ${chapter.title.ifBlank { "第 ${index + 1} 章" }}") },
                                        onClick = {
                                            chapterMenu = false
                                            player.seekTo(chapter.startMs)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (pipSupported) {
                        IconButton(onClick = { activity?.let { PipRequest.enter(it) } }) {
                            Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "画中画", tint = Color.White)
                        }
                    }
                    val landscapeLocked = screen.orientation == ScreenOrientation.LANDSCAPE
                    IconButton(onClick = {
                        screen.orientation = if (landscapeLocked) ScreenOrientation.SENSOR else ScreenOrientation.LANDSCAPE
                    }) {
                        Icon(
                            if (landscapeLocked) Icons.Filled.ScreenLockLandscape else Icons.Filled.ScreenRotation,
                            contentDescription = if (landscapeLocked) "已锁定横屏，点按恢复自动旋转" else "锁定为横屏",
                            tint = Color.White
                        )
                    }
                    // Against a pocket, a child or a cheek: touches do nothing
                    // until it is unlocked.
                    IconButton(onClick = {
                        locked = true
                        playerView?.hideController()
                    }) {
                        Icon(Icons.Filled.LockOpen, contentDescription = "锁定屏幕，防止误触", tint = Color.White)
                    }
                }

                // A phone held upright has no room for the title and nine
                // buttons on one line; the buttons go under it and scroll.
                if (maxWidth >= 600.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp, end = 8.dp)
                        )
                        buttons()
                    }
                } else {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            content = buttons
                        )
                    }
                }
            }
        }

        playbackError?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "无法播放",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(12.dp))
                    Row {
                        // Opens a fresh session for the same file, from where
                        // it stopped: an expired link is the usual cause.
                        Button(onClick = { latestOnSkip(info.item.id, player.currentPosition) }) { Text("重试") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { latestOnClose(player.currentPosition) }) { Text("返回") }
                    }
                }
            }
        }
    }
}

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** The tick on a menu's current choice. The menus used to list tracks with nothing to say which was playing. */
@Composable
private fun ChosenMark(chosen: Boolean) {
    Box(Modifier.size(24.dp)) {
        if (chosen) Icon(Icons.Filled.Check, contentDescription = "当前", tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Touch on the picture the way phone video players have it: a tap shows or
 * hides the controls, a double tap on either third jumps ten seconds and in
 * the middle pauses, a sideways drag seeks, an upright drag changes brightness
 * on the left half and volume on the right, and a long press plays at double
 * speed until let go.
 *
 * The listener sits on the PlayerView itself, so the controller's own buttons
 * and time bar, being children, still get their touches first.
 */
@SuppressLint("ClickableViewAccessibility")
@androidx.annotation.OptIn(UnstableApi::class)
private fun installGestures(
    view: PlayerView,
    player: ExoPlayer,
    isLocked: () -> Boolean,
    onHint: (String?) -> Unit,
    onBoost: (Boolean) -> Unit
) {
    val context = view.context
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val window = context.findActivityOrNull()?.window
    var mode = Drag.NONE
    var seekFrom = 0L
    var seekTarget = 0L
    var startVolume = 0
    var startBrightness = 0.5f
    var boostedFrom: Float? = null

    val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            mode = Drag.NONE
            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            startBrightness = window?.attributes?.screenBrightness?.takeIf { it >= 0 }
                ?: runCatching {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                }.getOrDefault(0.5f).coerceIn(0f, 1f)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (view.isControllerFullyVisible) view.hideController() else view.showController()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val third = view.width / 3f
            when {
                e.x < third -> {
                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    onHint("−10 秒")
                }
                e.x > third * 2 -> {
                    val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 10_000).coerceAtMost(end))
                    onHint("+10 秒")
                }
                else -> if (player.isPlaying) player.pause() else player.play()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!player.isPlaying) return
            val speed = player.playbackParameters.speed
            boostedFrom = speed
            player.playbackParameters = PlaybackParameters(speed * 2)
            onBoost(true)
        }

        override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
            val start = first ?: return false
            val totalX = current.x - start.x
            val totalY = current.y - start.y
            if (mode == Drag.NONE) {
                mode = when {
                    abs(totalX) > abs(totalY) -> Drag.SEEK
                    start.x < view.width / 2f -> Drag.BRIGHTNESS
                    else -> Drag.VOLUME
                }
                seekFrom = player.currentPosition
                seekTarget = seekFrom
            }
            when (mode) {
                // A full width of drag is ninety seconds either way.
                Drag.SEEK -> {
                    val deltaMs = (totalX / view.width.coerceAtLeast(1) * 90_000).toLong()
                    val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    seekTarget = (seekFrom + deltaMs).coerceIn(0, end)
                    val sign = if (seekTarget >= seekFrom) "+" else "−"
                    onHint("${formatDuration(seekTarget)}  $sign${abs(seekTarget - seekFrom) / 1000} 秒")
                }
                Drag.BRIGHTNESS -> window?.let { w ->
                    val value = (startBrightness - totalY / view.height.coerceAtLeast(1)).coerceIn(0.01f, 1f)
                    w.attributes = w.attributes.apply { screenBrightness = value }
                    onHint("亮度 ${(value * 100).roundToInt()}%")
                }
                Drag.VOLUME -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val value = (startVolume - totalY / view.height.coerceAtLeast(1) * max).roundToInt().coerceIn(0, max)
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0) }
                    onHint("音量 ${value * 100 / max}%")
                }
                Drag.NONE -> Unit
            }
            return true
        }
    })

    // Every touch that reaches the view itself is consumed: letting one through
    // would have PlayerView toggle the controller on its own, and then the tap
    // handler toggle it back.
    view.setOnTouchListener { _, event ->
        if (isLocked()) return@setOnTouchListener true
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            // Cancelled is the system taking the touch — the back gesture from
            // the edge, most often — not the viewer letting go where they meant.
            if (mode == Drag.SEEK && event.actionMasked == MotionEvent.ACTION_UP) player.seekTo(seekTarget)
            if (mode == Drag.SEEK && event.actionMasked == MotionEvent.ACTION_CANCEL) onHint(null)
            boostedFrom?.let { speed ->
                boostedFrom = null
                player.playbackParameters = PlaybackParameters(speed)
                onBoost(false)
            }
            mode = Drag.NONE
        }
        true
    }
}

private enum class Drag { NONE, SEEK, BRIGHTNESS, VOLUME }

@androidx.annotation.OptIn(UnstableApi::class)
private fun subtitleConfiguration(uri: Uri, mime: String, language: String?, label: String, isDefault: Boolean) =
    MediaItem.SubtitleConfiguration.Builder(uri)
        .setMimeType(mime)
        .setLanguage(language)
        .setLabel(label)
        .setSelectionFlags(if (isDefault) C.SELECTION_FLAG_DEFAULT else 0)
        .build()

private fun mimeFor(codec: String?): String = when (codec?.lowercase()) {
    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
    else -> MimeTypes.APPLICATION_SUBRIP
}

/** The failure in words rather than media3's constant name, which stays on the end for a report. */
private fun describePlaybackError(error: PlaybackException): String {
    val reason = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接失败或超时。"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "网盘拒绝了请求，链接可能已过期，重试通常能恢复。"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在，可能已被移动或删除。"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "这台设备解不了这个视频格式，可以在设置里换外部播放器。"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "文件格式无法识别，或文件已损坏。"
        else -> "播放出错。"
    }
    return "$reason（${error.errorCodeName}）"
}

private fun formatDelay(ms: Long): String =
    (if (ms > 0) "+" else "") + String.format(java.util.Locale.ROOT, "%.1f 秒", ms / 1000.0)

/** Whether this is a subtitle DAView draws itself rather than handing to media3. */
private val MediaStreamDto.isAss: Boolean
    get() = codec?.lowercase() in setOf("ass", "ssa")

/**
 * Maps a DAView stream onto an ExoPlayer track. Embedded streams carry the
 * container's track number, which Matroska's extractor uses as the format id
 * (prefixed when side-loaded subtitles merge sources); failing that, the n-th
 * embedded stream of the type is the n-th group, since the file's own tracks
 * come before side-loaded ones. External subtitles are matched by label.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun selectTrack(player: ExoPlayer, trackType: Int, stream: MediaStreamDto, streamsOfType: List<MediaStreamDto>): Boolean {
    val groups: List<Tracks.Group> = player.currentTracks.groups.filter { it.type == trackType }
    val target = if (stream.isExternal) {
        groups.firstOrNull { group ->
            (0 until group.length).any { group.getTrackFormat(it).label == stream.displayTitle }
        }
    } else {
        val number = stream.index.toString()
        groups.firstOrNull { group ->
            (0 until group.length).any {
                val id = group.getTrackFormat(it).id
                id == number || id?.endsWith(":$number") == true
            }
        } ?: groups.getOrNull(streamsOfType.filter { !it.isExternal }.indexOfFirst { it.index == stream.index })
    } ?: return false

    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0))
        .build()
    return true
}

/** The lock lives on the screen, so it holds from one episode to the next. */
@Composable
private fun screenLocked(screen: PlayerScreenState): androidx.compose.runtime.MutableState<Boolean> =
    remember(screen) {
        object : androidx.compose.runtime.MutableState<Boolean> {
            override var value: Boolean
                get() = screen.locked
                set(value) { screen.locked = value }
            override fun component1() = value
            override fun component2(): (Boolean) -> Unit = { value = it }
        }
    }

/** The DAView stream behind the n-th media3 group of a type — the inverse of [selectTrack]. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun streamForGroup(groups: List<Tracks.Group>, position: Int, streamsOfType: List<MediaStreamDto>): MediaStreamDto? {
    val group = groups[position]
    val embedded = streamsOfType.filter { !it.isExternal }
    (0 until group.length).forEach { track ->
        val id = group.getTrackFormat(track).id ?: return@forEach
        embedded.firstOrNull { id == it.index.toString() || id.endsWith(":${it.index}") }?.let { return it }
    }
    return embedded.getOrNull(position)
}

/** The activity behind the composition, for the platform calls that need one. */
private tailrec fun android.content.Context.findActivityOrNull(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivityOrNull()
        else -> null
    }
