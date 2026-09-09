package com.daview.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.PlaybackInfoDto
import com.daview.shared.model.StreamType
import kotlinx.coroutines.delay

/**
 * ExoPlayer-backed player. Media3 handles Matroska with h264/hevc, AAC/AC3 and
 * embedded SSA/SRT natively, so the DAView stream URL can be played directly;
 * external subtitle files are attached as side-loaded subtitle configurations.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
actual fun InternalPlayer(
    info: PlaybackInfoDto,
    onProgress: (positionMs: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?) -> Unit,
    onClose: (positionMs: Long) -> Unit,
    onEnded: (positionMs: Long) -> Unit
) {
    val context = LocalContext.current
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnEnded by rememberUpdatedState(onEnded)
    var audioMenu by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var selectedAudio by remember { mutableStateOf(info.audioStreamIndex) }
    var selectedSubtitle by remember { mutableStateOf(info.subtitleStreamIndex) }

    var playbackError by remember { mutableStateOf<String?>(null) }

    // Whether the app's own overlay is on screen. media3 fades its transport
    // controls out after a few seconds; the track buttons and the title used to
    // ignore that and sit on the picture for the whole film.
    var controlsVisible by remember { mutableStateOf(true) }

    // Playback presentation: system bars hidden, screen kept awake, and the
    // display turned. Rotation stays on the sensor until the viewer locks it,
    // which is the case this exists for — watching lying down with the phone's
    // own auto-rotate off.
    var orientation by remember { mutableStateOf(ScreenOrientation.SENSOR) }
    PlaybackPresentation(orientation)

    val player = remember {
        // The stream URL points at the app's own server over http, and that
        // server answers with a 302 to the storage backend's signed https link.
        // ExoPlayer refuses http -> https redirects unless told otherwise, which
        // is why playback failed to start at all.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("DAView/1.0")
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
            )
            .build().apply {
            val subtitles = info.item.mediaStreams
                .filter { it.type == StreamType.SUBTITLE && it.isExternal }
                .mapNotNull { stream ->
                    val url = info.subtitleUrls[stream.index] ?: return@mapNotNull null
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
                        .setMimeType(mimeFor(stream))
                        .setLanguage(stream.language)
                        .setLabel(stream.displayTitle)
                        .setSelectionFlags(if (stream.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                }
            setMediaItem(
                MediaItem.Builder()
                    .setUri(info.streamUrl)
                    .setSubtitleConfigurations(subtitles)
                    .build()
            )
            android.util.Log.i("DAView", "play url=${info.streamUrl}")
            seekTo(info.startPositionMs)
            playWhenReady = true
            // Without this a failure is silent: a black surface and no clue.
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("DAView", "playback failed", error)
                    playbackError = "${error.errorCodeName}: ${error.message ?: "播放失败"}"
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) latestOnEnded(duration)
                }
            })
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val position = player.currentPosition
            player.release()
            onClose(position)
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.isPlaying || player.currentPosition > 0) {
                latestOnProgress(player.currentPosition, !player.isPlaying, selectedAudio, selectedSubtitle)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    // The app draws its own subtitle menu, and it is the only
                    // one that knows about side-loaded subtitle files; media3's
                    // button would list a different set under the same idea.
                    setShowSubtitleButton(false)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == android.view.View.VISIBLE
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Rides with media3's own controls so the picture is clean when they go.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            // A scrim under the white text: over a bright frame the title and
            // the buttons were white on near-white.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                        )
                    )
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    info.item.seriesName?.let { "$it · ${info.item.name}" } ?: info.item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp, end = 160.dp)
                )
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        orientation = if (orientation == ScreenOrientation.SENSOR) {
                            ScreenOrientation.LANDSCAPE
                        } else {
                            ScreenOrientation.SENSOR
                        }
                    }) {
                        Icon(
                            if (orientation == ScreenOrientation.LANDSCAPE) {
                                Icons.Filled.ScreenLockLandscape
                            } else {
                                Icons.Filled.ScreenRotation
                            },
                            contentDescription = if (orientation == ScreenOrientation.LANDSCAPE) {
                                "已锁定横屏，点按恢复自动旋转"
                            } else {
                                "锁定为横屏"
                            },
                            tint = Color.White
                        )
                    }
            Box {
                TextButton(onClick = { audioMenu = true }) { Text("音轨", color = Color.White) }
                DropdownMenu(audioMenu, onDismissRequest = { audioMenu = false }) {
                    info.item.mediaStreams.filter { it.type == StreamType.AUDIO }.forEach { stream ->
                        DropdownMenuItem(
                            text = { Text(stream.displayTitle) },
                            onClick = {
                                audioMenu = false
                                selectedAudio = stream.index
                                selectTrack(player, C.TRACK_TYPE_AUDIO, stream)
                                latestOnProgress(player.currentPosition, !player.isPlaying, selectedAudio, selectedSubtitle)
                            }
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { subtitleMenu = true }) { Text("字幕", color = Color.White) }
                DropdownMenu(subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("关闭字幕") },
                        onClick = {
                            subtitleMenu = false
                            selectedSubtitle = null
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                        }
                    )
                    info.item.mediaStreams.filter { it.type == StreamType.SUBTITLE }.forEach { stream ->
                        DropdownMenuItem(
                            text = { Text(stream.displayTitle) },
                            onClick = {
                                subtitleMenu = false
                                selectedSubtitle = stream.index
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .build()
                                selectTrack(player, C.TRACK_TYPE_TEXT, stream)
                                latestOnProgress(player.currentPosition, !player.isPlaying, selectedAudio, selectedSubtitle)
                            }
                        )
                    }
                }
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
                    // A failure used to leave a card floating over a black
                    // screen with no way off it but the system back gesture.
                    TextButton(onClick = { onClose(player.currentPosition) }) {
                        Text("返回")
                    }
                }
            }
        }
    }
}

private fun mimeFor(stream: MediaStreamDto): String = when (stream.codec?.lowercase()) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt" -> MimeTypes.TEXT_VTT
    else -> MimeTypes.APPLICATION_SUBRIP
}

/**
 * Maps a DAView stream index onto an ExoPlayer track. Embedded indices are the
 * container's own track numbers, so they are matched by order within the type;
 * external subtitles are matched by their label.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun selectTrack(player: ExoPlayer, trackType: Int, stream: MediaStreamDto) {
    val groups: List<Tracks.Group> = player.currentTracks.groups.filter { it.type == trackType }
    val target = if (stream.isExternal) {
        groups.firstOrNull { group ->
            (0 until group.length).any { group.getTrackFormat(it).label == stream.displayTitle }
        }
    } else {
        groups.firstOrNull { group ->
            (0 until group.length).any { group.getTrackFormat(it).id == stream.index.toString() }
        } ?: groups.getOrNull(
            player.currentTracks.groups
                .filter { it.type == trackType }
                .indexOfFirst { it === groups.firstOrNull() }
                .coerceAtLeast(0)
        )
    } ?: return

    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0))
        .build()
}
