package com.daview.app.ui

import androidx.compose.runtime.Composable
import com.daview.shared.model.PlaybackInfoDto

/**
 * In-app playback surface. Only Android ships one today; desktop hands off to
 * an external player and shows [ExternalPlaybackPanel] instead.
 */
@Composable
expect fun InternalPlayer(
    info: PlaybackInfoDto,
    onProgress: (positionMs: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?) -> Unit,
    onClose: (positionMs: Long) -> Unit
)
