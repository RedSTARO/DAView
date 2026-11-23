package com.daview.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
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
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.daview.app.platform.AndroidFilePicker
import com.daview.app.player.BluRayExtractors
import com.daview.app.subtitle.AssScript
import com.daview.app.subtitle.AssSource
import com.daview.app.subtitle.AssSubtitleView
import com.daview.app.subtitle.EmbeddedAssExtractors
import com.daview.app.subtitle.EmbeddedAssTracks
import com.daview.server.library.TsProbe
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
 * ASS, which DAView renders itself over the picture, from a file beside the
 * video or from inside it alike. See [AssSubtitleView] and [EmbeddedAssExtractors].
 *
 * The screen composes one of these per session. What the viewer set that should
 * outlive one file — the rotation lock, the subtitle delay, the speed — lives in
 * [PlayerScreenState].
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
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

    var openMenu by remember { mutableStateOf<PlayerMenu?>(null) }
    var selectedAudio by remember { mutableStateOf(info.audioStreamIndex) }
    // The viewer's audio choice when this phone cannot play it and media3
    // plays another track instead. The stand-in is what the menu ticks, but
    // not what is recorded: the choice is synced and carried to the next
    // episode, and another device may well play it.
    var audioStandIn by remember { mutableStateOf<Int?>(null) }
    // A track picked from the menu while media3 has no track list — the file
    // from the phone being mounted reloads the media — put in place once the
    // list is back.
    var pendingAudio by remember { mutableStateOf<Int?>(null) }
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

    val activity = remember(context) { context.findPlayerActivity() }
    val pipSupported = remember(activity) { activity != null && PipRequest.supported(activity) }

    // Whether the app's own overlay is on screen. It rides with media3's own
    // transport controls, so the picture is clean when they go.
    var controlsVisible by remember { mutableStateOf(true) }
    val appWindow = LocalAppWindowInfo.current
    val inPictureInPicture = rememberInPictureInPicture() || appWindow.isInPictureInPicture
    val chromeHidden = inPictureInPicture || PipRequest.isTransitioning(activity)

    // ASS files are not handed to media3 at all — they are drawn by
    // AssSubtitleView instead, which keeps the typesetting media3's parser strips.
    // The ASS inside the video goes the same way: the extractor keeps its lines
    // here as it reads them, and media3 never learns the track is there. Only
    // the tracks the server listed, which are the ones the menu can offer.
    val embeddedAss = remember { EmbeddedAssTracks() }
    val embeddedAssListed = remember(info) {
        subtitleStreams.filter { !it.isExternal && it.isAss }.map { it.index.toString() }.toSet()
    }
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

        // What the phone cannot decode itself — DTS, which most Blu-rays carry,
        // TrueHD, and AC-3 where there is no Dolby decoder — goes to the FFmpeg
        // built into the app, after the phone's own decoders and any
        // passthrough have had the first say.
        ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        )
            .setMediaSourceFactory(
                // A Blu-ray's .m2ts is read by an extractor of DAView's own:
                // media3's transport stream extractor takes neither its packet
                // size nor its audio and subtitle stream types.
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(context, httpFactory),
                    BluRayExtractors(EmbeddedAssExtractors(embeddedAss, embeddedAssListed))
                )
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
        latestOnProgress(player.currentPosition, !player.isPlaying, audioStandIn ?: selectedAudio, subtitle)
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
                    pendingAudio = null
                    val chosen = audioStreams.firstOrNull { it.index == selectedAudio }
                    val audioApplied = chosen?.let { selectTrack(player, C.TRACK_TYPE_AUDIO, it, audioStreams) } ?: false
                    if (chosen != null && !audioApplied) audioStandIn = chosen.index
                    // Picture with no sound, or another track than the one
                    // chosen, and no word why, reads as a broken file.
                    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audio.none { it.isSupported } && (audio.isNotEmpty() || audioStreams.isNotEmpty())) {
                        hint = "这台设备解不了这个文件的音轨"
                    } else if (chosen != null && !audioApplied) {
                        hint = "这台设备放不了选定的音轨，先播另一条"
                    }
                    if (!subtitlesOff) {
                        subtitleStreams.firstOrNull { it.index == selectedSubtitle }
                            ?.takeIf { !it.isAss }
                            ?.let { selectTrack(player, C.TRACK_TYPE_TEXT, it, subtitleStreams) }
                    }
                    // Applied, the change comes back through here; not applied,
                    // what media3 picked is what plays, and the tick in the menu
                    // has to say so.
                    if (audioApplied) return
                }
                pendingAudio?.let { index ->
                    pendingAudio = null
                    val stream = audioStreams.firstOrNull { it.index == index }
                    if (stream != null && selectTrack(player, C.TRACK_TYPE_AUDIO, stream, audioStreams)) return
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
                    // A choice can play as another stream's track — a Blu-ray
                    // TrueHD as the AC-3 core beside it — and is still the choice.
                    val chosen = audioStreams.firstOrNull { it.index == selectedAudio }
                    val asChosen = chosen != null &&
                        trackFor(player, C.TRACK_TYPE_AUDIO, chosen, audioStreams)?.mediaTrackGroup == audioGroups[playing].mediaTrackGroup
                    val stream = streamForGroup(audioGroups, playing, audioStreams)
                    // While a stand-in plays this app sets no audio track of its
                    // own, so one set now was picked in media3's own menu, and
                    // that is a choice to record.
                    if (audioStandIn != null && player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_AUDIO }) {
                        audioStandIn = null
                    }
                    if (!asChosen && stream != null && stream.index != selectedAudio) {
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

    var videoSize by remember { mutableStateOf(player.videoSize) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var playWhenReady by remember { mutableStateOf(player.playWhenReady) }
    var playbackState by remember { mutableStateOf(player.playbackState) }
    var playbackSuppressed by remember { mutableStateOf(player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                videoSize = player.videoSize
                playing = player.isPlaying
                playWhenReady = player.playWhenReady
                playbackState = player.playbackState
                playbackSuppressed = player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
            }

            // The native settings menu's choice persists; temporary gesture boost does not.
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                if (!boosting && abs(parameters.speed - screen.speed) > 0.001f) screen.speed = parameters.speed
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // The ASS script on screen, if the chosen subtitle is one DAView draws
    // itself, and the view that draws it.
    var assScript by remember { mutableStateOf<AssScript?>(null) }
    var assView by remember { mutableStateOf<AssSubtitleView?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(selectedSubtitle, mountedSubtitle, subtitlesOff) {
        // A file from the phone set its own renderer up when it was picked.
        if (mountedSubtitle != null) return@LaunchedEffect
        val stream = subtitleStreams.firstOrNull { it.index == selectedSubtitle }?.takeIf { it.isAss && !subtitlesOff }
        val url = stream?.takeIf { it.isExternal }?.let { info.subtitleUrls[it.index] }
        val inside = stream?.takeIf { !it.isExternal }
        // The ASS inside the video is put together below, off this thread.
        assScript = when {
            url != null -> AssSource.load(url)
                .onFailure { android.util.Log.w("DAView", "subtitle $url", it) }
                .getOrNull()
            else -> null
        }
        // Two renderers drawing at once would double every line. The ASS inside
        // the video is not a media3 track at all, so choosing it has to turn
        // media3's off even before the first of its lines has been read.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, assScript != null || inside != null || subtitlesOff)
            .build()

        // The lines inside the video arrive as the player reads ahead, so the
        // script on screen is brought up to date while this track is chosen.
        // A snapshot taken while the viewer chose something else — a file from
        // the phone, most likely, which does not restart this until the next
        // frame — is dropped rather than drawn over it.
        if (inside != null) {
            var seen = -1
            while (true) {
                val track = embeddedAss[inside.index.toString()]
                if (track != null && track.version != seen) {
                    seen = track.version
                    val script = withContext(Dispatchers.Default) { track.snapshot() }
                    if (mountedSubtitle == null && !subtitlesOff && selectedSubtitle == inside.index) {
                        assScript = script
                    }
                }
                delay(EMBEDDED_ASS_REFRESH_MS)
            }
        }
    }

    // Visible PiP reaches ON_PAUSE, not ON_STOP. Once stopped (including screen
    // lock or dismissing PiP), playback must pause regardless of how this player
    // was created. Do not capture the initial PiP state in this observer.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                report()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(player) {
        onDispose {
            val position = player.currentPosition
            playerView?.player = null
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
    androidx.activity.compose.BackHandler(enabled = !chromeHidden && (locked || openMenu != null)) {
        if (openMenu != null) openMenu = null else hint = "屏幕已锁定，点左侧的锁解锁"
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

    val title = info.item.seriesName?.let { series ->
        listOfNotNull(series, info.item.episodeLabel, info.item.name).distinct().joinToString(" · ")
    } ?: info.item.name
    val videoAspect = videoSize.displayAspect()
    val ratio = videoAspect?.pipRatio()
    val goBack: () -> Unit = {
        val dispatcher = (activity as? ComponentActivity)?.onBackPressedDispatcher
        if (dispatcher != null) dispatcher.onBackPressed() else latestOnClose(player.currentPosition)
    }
    val canEnterPip = pipSupported && ratio != null && playbackError == null

    val menuContent: @Composable ColumnScope.(PlayerMenu) -> Unit = { menu ->
        when (menu) {
            PlayerMenu.More -> {
                listOfNotNull(
                    PlayerMenu.Audio.takeIf { audioStreams.isNotEmpty() }, PlayerMenu.Subtitle, PlayerMenu.Tuning,
                    PlayerMenu.Chapter.takeIf { chapters.isNotEmpty() }
                ).forEach { target ->
                    DropdownMenuItem(text = { Text(target.title) }, onClick = { openMenu = target })
                }
                info.previousItemId?.let { previous ->
                    DropdownMenuItem(text = { Text("上一集") }, onClick = {
                        openMenu = null
                        latestOnSkip(previous, player.currentPosition)
                    })
                }
                info.nextItemId?.let { next ->
                    DropdownMenuItem(text = { Text("下一集") }, onClick = {
                        openMenu = null
                        latestOnSkip(next, player.currentPosition)
                    })
                }
                introEnd?.let { end ->
                    DropdownMenuItem(text = { Text("跳过片头") }, onClick = {
                        player.seekTo(end)
                        openMenu = null
                    })
                }
                if (canEnterPip) {
                    DropdownMenuItem(text = { Text("画中画") }, onClick = {
                        openMenu = null
                        activity?.let { PipRequest.enter(it) }
                    })
                }
            }
            PlayerMenu.Audio -> {
                audioStreams.forEach { stream ->
                    // A track the phone cannot decode, or one the
                    // player does not offer at all — a Blu-ray's
                    // TrueHD — is listed but cannot be chosen:
                    // forcing it would stop playback.
                    val playable = trackFor(player, C.TRACK_TYPE_AUDIO, stream, audioStreams)?.isTrackSupported(0)
                        ?: player.currentTracks.groups.isEmpty()
                    DropdownMenuItem(
                        leadingIcon = { ChosenMark(stream.index == selectedAudio) },
                        text = { Text(if (playable) stream.displayTitle else "${stream.displayTitle}（无法播放）") },
                        enabled = playable,
                        onClick = {
                            openMenu = null
                            // While media3 has no track list the
                            // choice waits for it, as the server's
                            // own pick does.
                            if (player.currentTracks.groups.isEmpty()) {
                                selectedAudio = stream.index
                                audioStandIn = null
                                pendingAudio = stream.index
                            } else if (selectTrack(player, C.TRACK_TYPE_AUDIO, stream, audioStreams)) {
                                selectedAudio = stream.index
                                audioStandIn = null
                            }
                            report()
                        }
                    )
                }
            }
            PlayerMenu.Subtitle -> {
                DropdownMenuItem(
                    leadingIcon = { ChosenMark(subtitlesOff) },
                    text = { Text("关闭字幕") },
                    onClick = {
                        openMenu = null
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
                            openMenu = null
                            selectedSubtitle = stream.index
                            subtitlesOff = false
                            mountedSubtitle = null
                            pendingMount = null
                            // ASS, beside the video or inside it, has no
                            // media3 track to select — the overlay picks
                            // it up from selectedSubtitle.
                            if (!stream.isAss) {
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
                        onClick = { openMenu = null }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("从手机加载字幕文件…") },
                    onClick = {
                        openMenu = null
                        mountSubtitleFile()
                    }
                )
            }
            PlayerMenu.Tuning -> {
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
                            openMenu = null
                            screen.speed = value
                            player.playbackParameters = PlaybackParameters(value)
                        }
                    )
                }
            }
            PlayerMenu.Chapter -> {
                chapters.forEachIndexed { index, chapter ->
                    DropdownMenuItem(
                        leadingIcon = { ChosenMark(index == currentChapter) },
                        text = { Text("${formatDuration(chapter.startMs)}  ${chapter.title.ifBlank { "第 ${index + 1} 章" }}") },
                        onClick = {
                            openMenu = null
                            player.seekTo(chapter.startMs)
                        }
                    )
                }
            }
        }
    }

    PublishPlaybackPip(
        activity = activity,
        source = playerView?.videoSurfaceView,
        aspect = ratio,
        autoEnter = canEnterPip && !appWindow.isInMultiWindow && playWhenReady && !playbackSuppressed &&
            (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING),
        hideNativeControls = { playerView?.hideNativeControls() }
    )
    LaunchedEffect(chromeHidden, locked) {
        if (chromeHidden || locked) openMenu = null
    }

    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safeDrawing = WindowInsets.safeDrawing
    val stableControlInsets = safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility)
    val cutout = WindowInsets.displayCutout
    var origin by remember { mutableStateOf(Offset.Zero) }
    var decorSize by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).onGloballyPositioned { coordinates ->
            origin = coordinates.localToWindow(Offset.Zero)
            val decor = activity?.window?.decorView
            decorSize = if (decor == null) coordinates.size else IntSize(decor.width, decor.height)
        },
        contentAlignment = AbsoluteAlignment.TopLeft
    ) {
        val viewport = with(density) {
            val left = origin.x.toDp()
            val top = origin.y.toDp()
            WindowPane(left, top, left + maxWidth, top + maxHeight)
        }
        val windowWidth = with(density) { decorSize.width.takeIf { it > 0 }?.toDp() ?: viewport.right }
        val windowHeight = with(density) { decorSize.height.takeIf { it > 0 }?.toDp() ?: viewport.bottom }
        val window = WindowPane(0.dp, 0.dp, windowWidth, windowHeight)
        val pictureBounds = window.inset(cutout, density, direction)
        val touchBounds = window.inset(safeDrawing, density, direction)
        // Transient system bars may move controls, not select another pane or HUD.
        val stableTouchBounds = window.inset(stableControlInsets, density, direction)
        val panes = playerPanes(
            viewport, appWindow.fold.takeUnless { inPictureInPicture }, direction == LayoutDirection.Rtl,
            pictureBounds, stableTouchBounds, videoAspect
        )
        val tabletop = panes.tabletopControls != null
        val picturePane = if (inPictureInPicture) viewport else panes.picture.clampTo(pictureBounds)
        val controlsPane = panes.controls
        // Split-screen is still a normal player when the task has room. Only reduce the
        // controls for an actually cramped pane, not for the windowing-mode flag itself.
        val compact = !tabletop && !controlsPane.clampTo(stableTouchBounds).fitsPlayerHud()
        val nativeHud = !compact && !tabletop && panes.picture == viewport
        val popupMenus = nativeHud && picturePane == viewport
        val nativeInsets = panePadding(picturePane, touchBounds, direction)
        val showPause = playWhenReady && !playbackSuppressed
        val useNativeControls = nativeHud && !chromeHidden && !locked
        var foldHudVisible by remember(nativeHud, tabletop, compact) { mutableStateOf(true) }
        var controlTouches by remember { mutableIntStateOf(0) }
        var scrubbing by remember { mutableStateOf(false) }
        val showHud = when {
            chromeHidden || locked -> false
            tabletop || compact -> true
            nativeHud -> controlsVisible
            else -> foldHudVisible || !playWhenReady || openMenu != null
        }
        LaunchedEffect(nativeHud, tabletop, compact, foldHudVisible, playing, openMenu, controlTouches, scrubbing) {
            if (!nativeHud && !tabletop && !compact && foldHudVisible && playing && openMenu == null && !scrubbing) {
                delay(5_000)
                foldHudVisible = false
            }
        }
        LaunchedEffect(controlsPane, tabletop, compact, nativeHud) { openMenu = null }
        var cancelPictureGesture by remember { mutableStateOf<(() -> Unit)?>(null) }
        val suppressGestures by rememberUpdatedState(chromeHidden || locked || openMenu != null)
        val tapPicture by rememberUpdatedState<() -> Unit>({
            when {
                tabletop || compact -> Unit
                nativeHud -> playerView?.performClick()
                else -> foldHudVisible = !showHud
            }
        })
        // A pointer captured before folding must not commit a seek against the new geometry
        // or carry a speed boost into another pane or into PiP.
        LaunchedEffect(picturePane, suppressGestures, tabletop, compact) { cancelPictureGesture?.invoke() }

        // No posture/PiP key or conditional parent around this AndroidView. Media3 keeps the
        // same SurfaceView, subtitles and decoder; we only remeasure and place them.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = useNativeControls
                    this.player = player
                    setEnableComposeSurfaceSyncWorkaround(true) // Media3 applies it on API 34 only.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    setShowSubtitleButton(false)
                    subtitleView?.setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            /* typeface = */ null
                        )
                    )
                    overlayFrameLayout?.addView(
                        AssSubtitleView(ctx)
                            .apply { position = { player.currentPosition } }
                            .also { assView = it }
                    )
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = visibility == View.VISIBLE
                    })
                    if (useController) showController()
                    cancelPictureGesture = installGestures(
                        view = this,
                        player = player,
                        canHandle = { !suppressGestures },
                        onTap = { tapPicture() },
                        onHint = { hint = it },
                        onBoost = { boosting = it }
                    )
                    playerView = this
                }
            },
            modifier = Modifier.playerPane(picturePane, viewport),
            onRelease = { view ->
                cancelPictureGesture?.invoke()
                view.setOnTouchListener(null)
                view.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
                view.player = null
            },
            update = { view ->
                if (view.useController != useNativeControls) {
                    if (useNativeControls) {
                        view.useController = true
                        view.showController()
                    } else {
                        view.hideNativeControls()
                    }
                }
                view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)?.let { controller ->
                    with(density) {
                        val left = nativeInsets.calculateLeftPadding(direction).roundToPx()
                        val top = nativeInsets.calculateTopPadding().roundToPx()
                        val right = nativeInsets.calculateRightPadding(direction).roundToPx()
                        val bottom = nativeInsets.calculateBottomPadding().roundToPx()
                        if (controller.paddingLeft != left || controller.paddingTop != top ||
                            controller.paddingRight != right || controller.paddingBottom != bottom
                        ) controller.setPadding(left, top, right, bottom)
                    }
                }
                assView?.apply {
                    script = assScript
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                    driving = playing
                    if (!playing) invalidate()
                }
            }
        )

        if (!chromeHidden) {
            Box(Modifier.playerPane(picturePane, viewport).padding(nativeInsets)) {
                (if (boosting) "2× 快进中" else hint)?.let { text ->
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.align(if (boosting) Alignment.TopCenter else Alignment.Center)
                            .padding(top = if (boosting) 24.dp else 0.dp)
                    ) {
                        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                    }
                }
            }

            Box(
                Modifier.playerPane(controlsPane, viewport)
                    .then(if (tabletop) Modifier.background(Color(0xFF17171B)) else Modifier)
                    .padding(panePadding(controlsPane, touchBounds, direction))
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
                    if (audioStreams.isNotEmpty()) {
                        PlayerMenuButton(PlayerMenu.Audio, Icons.Filled.Audiotrack, popupMenus, openMenu, { openMenu = it }, menuContent)
                    }
                    PlayerMenuButton(PlayerMenu.Subtitle, Icons.Filled.ClosedCaption, popupMenus, openMenu, { openMenu = it }, menuContent)
                    PlayerMenuButton(PlayerMenu.Tuning, Icons.Filled.Tune, popupMenus, openMenu, { openMenu = it }, menuContent)
                    if (chapters.isNotEmpty()) {
                        PlayerMenuButton(PlayerMenu.Chapter, Icons.AutoMirrored.Filled.Toc, popupMenus, openMenu, { openMenu = it }, menuContent)
                    }
                    if (canEnterPip) {
                        IconButton(onClick = { activity?.let { PipRequest.enter(it) } }) {
                            Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "画中画", tint = Color.White)
                        }
                    }
                    if (nativeHud && !appWindow.isInMultiWindow) {
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
                    }
                    IconButton(onClick = { locked = true }) {
                        Icon(Icons.Filled.LockOpen, contentDescription = "锁定屏幕，防止误触", tint = Color.White)
                    }
                }
                if (locked) {
                    IconButton(
                        onClick = { locked = false },
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = if (compact) 0.dp else 16.dp)
                            .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.extraLarge)
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = "已锁定，点按解锁", tint = Color.White)
                    }
                } else if (tabletop) {
                    Column(
                        Modifier.align(Alignment.Center).widthIn(max = 720.dp).fillMaxWidth().testTag("tabletop-player-controls")
                            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp)
                    ) {
                        FoldTransport(player, showPause, { controlTouches++ }, { scrubbing = it }, onBack = goBack)
                        PlayerToolbar(title, buttons = buttons)
                        if (introEnd != null) {
                            TextButton(onClick = { player.seekTo(introEnd) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Text("跳过片头")
                            }
                        }
                    }
                } else if (compact) {
                    CompactPlayerActions(
                        player, showPause, goBack,
                        modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 320.dp).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                    ) {
                        PlayerMenuButton(PlayerMenu.More, Icons.Filled.MoreVert, false, openMenu, { openMenu = it }, menuContent)
                    }
                } else {
                    if (introEnd != null) {
                        Button(
                            onClick = { player.seekTo(introEnd) },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (showHud) 120.dp else 24.dp)
                        ) { Text("跳过片头") }
                    }
                    AnimatedVisibility(
                        visible = showHud,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) { PlayerToolbar(title, onBack = goBack, buttons = buttons) }
                    if (!nativeHud && showHud) {
                        FoldTransport(
                            player, showPause,
                            onInteraction = { controlTouches++ }, onScrubbing = { scrubbing = it },
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        )
                    }
                }
                val menu = openMenu
                if (!popupMenus && menu != null && !locked) {
                    PanePlayerMenu(menu, onDismiss = { openMenu = null }, content = menuContent)
                }
                playbackError?.let { message ->
                    Surface(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text("无法播放", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Row {
                                Button(onClick = { latestOnSkip(info.item.id, player.currentPosition) }) { Text("重试") }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { latestOnClose(player.currentPosition) }) { Text("返回") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerView.hideNativeControls() {
    useController = false
    findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)?.hideImmediately()
}

private fun VideoSize.displayAspect(): Float? = if (width <= 0 || height <= 0) null
else (width * pixelWidthHeightRatio / height).takeIf { it.isFinite() && it > 0f }

// Restrict the PiP tile only; never use this clamped ratio to budget the real picture pane.
private fun Float.pipRatio(): Rational = Rational((coerceIn(0.42f, 2.39f) * 10_000).roundToInt(), 10_000)

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * How often the script of an ASS track inside the video takes in the lines read
 * since. Short, because after a seek the line on screen may be one just read.
 */
private const val EMBEDDED_ASS_REFRESH_MS = 100L

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
    canHandle: () -> Boolean,
    onTap: () -> Unit,
    onHint: (String?) -> Unit,
    onBoost: (Boolean) -> Unit
): () -> Unit {
    val context = view.context
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val window = context.findPlayerActivity()?.window
    var mode = Drag.NONE
    var seekFrom = 0L
    var seekTarget = 0L
    var startVolume = 0
    var startBrightness = 0.5f
    var boostedFrom: PlaybackParameters? = null
    var touchActive = false

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
            if (canHandle()) onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!canHandle()) return false
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
                else -> if (player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) player.pause() else player.play()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!canHandle() || !touchActive || !player.isPlaying) return
            val parameters = player.playbackParameters
            boostedFrom = parameters
            onBoost(true)
            player.playbackParameters = PlaybackParameters(parameters.speed * 2, parameters.pitch)
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

    fun finishGesture(commit: Boolean) {
        if (mode == Drag.SEEK) {
            if (commit) player.seekTo(seekTarget) else onHint(null)
        }
        boostedFrom?.let { parameters ->
            boostedFrom = null
            player.playbackParameters = parameters
            onBoost(false)
        }
        mode = Drag.NONE
        touchActive = false
    }

    fun cancelGesture() {
        // Also cancel GestureDetector's pending tap/long-press messages (including a tap
        // confirmed after the pointer was released just before a posture change).
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        detector.onTouchEvent(cancel)
        cancel.recycle()
        finishGesture(commit = false)
    }

    // The native controller's child buttons and time bar get their touches first. A tabletop
    // transport is a sibling in a different pane, so it never starts a picture gesture.
    view.setOnTouchListener { _, event ->
        val action = event.actionMasked
        if (!canHandle()) {
            if (touchActive) cancelGesture()
            return@setOnTouchListener true
        }
        if (action == MotionEvent.ACTION_DOWN) touchActive = true
        if (!touchActive) return@setOnTouchListener true
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            // Multi-touch must not unexpectedly seek by using the wrong finger's coordinates.
            cancelGesture()
            return@setOnTouchListener true
        }
        detector.onTouchEvent(event)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            finishGesture(commit = action == MotionEvent.ACTION_UP)
        }
        true
    }
    return ::cancelGesture
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
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "这台设备解不了这个格式，可以在详情页用外部播放器打开。"
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
 * Puts the player on a DAView stream. A track this phone cannot decode is
 * never forced: media3 would stop with a decoder error where, left to choose,
 * it plays what it can.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun selectTrack(player: ExoPlayer, trackType: Int, stream: MediaStreamDto, streamsOfType: List<MediaStreamDto>): Boolean {
    val target = trackFor(player, trackType, stream, streamsOfType) ?: return false
    if (!target.isTrackSupported(0)) return false

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

/**
 * The media3 group that plays a DAView stream, or null when the player offers
 * none. Embedded streams carry the container's own number for the track:
 * Matroska's track number, which its extractor uses as the format id, or a
 * transport stream's PID, which follows the program number ("1/4352"); either
 * is prefixed when side-loaded subtitles merge sources. Failing that, the n-th
 * embedded stream of the type is the n-th group, since the file's own tracks
 * come before side-loaded ones — where [positionsAgree]. External subtitles are
 * matched by label.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun trackFor(player: ExoPlayer, trackType: Int, stream: MediaStreamDto, streamsOfType: List<MediaStreamDto>): Tracks.Group? {
    val groups: List<Tracks.Group> = player.currentTracks.groups.filter { it.type == trackType }
    if (stream.isExternal) {
        return groups.firstOrNull { group ->
            (0 until group.length).any { group.getTrackFormat(it).label == stream.displayTitle }
        }
    }
    val number = stream.index.toString()
    val embedded = streamsOfType.filter { !it.isExternal }
    val byNumber = { wanted: String ->
        groups.firstOrNull { group -> (0 until group.length).any { isContainerTrack(group.getTrackFormat(it).id, wanted) } }
    }
    return byNumber(number)
        // A Blu-ray TrueHD plays as the AC-3 core beside it, the same audio:
        // the phone gets no TrueHD track of its own.
        ?: (stream.index + TsProbe.TRUEHD_CORE_OFFSET).takeIf { core -> stream.codec == "truehd" && embedded.any { it.index == core } }
            ?.let { byNumber(it.toString()) }
        ?: groups.takeIf { positionsAgree(it, embedded) }?.getOrNull(embedded.indexOfFirst { it.index == stream.index })
}

/** The DAView stream behind the n-th media3 group of a type — the inverse of [trackFor]. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun streamForGroup(groups: List<Tracks.Group>, position: Int, streamsOfType: List<MediaStreamDto>): MediaStreamDto? {
    val group = groups[position]
    val embedded = streamsOfType.filter { !it.isExternal }
    (0 until group.length).forEach { track ->
        val id = group.getTrackFormat(track).id ?: return@forEach
        embedded.firstOrNull { isContainerTrack(id, it.index.toString()) }?.let { return it }
    }
    return if (positionsAgree(groups, embedded)) embedded.getOrNull(position) else null
}

/** Whether a media3 format id names the container's track [number]. */
private fun isContainerTrack(id: String?, number: String): Boolean =
    id != null && (id == number || id.endsWith(":$number") || id.substringAfterLast('/') == number)

/**
 * Whether the n-th group stands for the n-th embedded stream: only when there
 * are as many of one as of the other. media3 leaves out what it cannot read —
 * a Blu-ray's TrueHD or second view, a Matroska track with a codec id it does
 * not know — and counting past a gap lands on the wrong track. The tracks it
 * does read are found by number anyway.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun positionsAgree(groups: List<Tracks.Group>, embedded: List<MediaStreamDto>): Boolean =
    groups.size == embedded.size
