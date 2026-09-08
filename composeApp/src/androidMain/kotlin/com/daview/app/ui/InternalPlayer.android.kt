package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
    onClose: (positionMs: Long) -> Unit
) {
    val context = LocalContext.current
    val latestOnProgress by rememberUpdatedState(onProgress)
    var audioMenu by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var selectedAudio by remember { mutableStateOf(info.audioStreamIndex) }
    var selectedSubtitle by remember { mutableStateOf(info.subtitleStreamIndex) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
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
            seekTo(info.startPositionMs)
            playWhenReady = true
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

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowSubtitleButton(true)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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

        Column(
            Modifier.align(Alignment.TopStart).padding(12.dp).fillMaxWidth(0.6f)
        ) {
            Text(
                info.item.seriesName?.let { "$it · ${info.item.name}" } ?: info.item.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
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
