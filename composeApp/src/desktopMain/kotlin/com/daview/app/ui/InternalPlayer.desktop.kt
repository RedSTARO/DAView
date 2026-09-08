package com.daview.app.ui

import androidx.compose.runtime.Composable
import com.daview.shared.model.PlaybackInfoDto

/**
 * The desktop build has no embedded video engine on purpose: PotPlayer, VLC and
 * mpv are already installed and handle every codec in these libraries, so the
 * app hands the stream to them and follows the progress the server derives.
 */
@Composable
actual fun InternalPlayer(
    info: PlaybackInfoDto,
    onProgress: (positionMs: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?) -> Unit,
    onClose: (positionMs: Long) -> Unit
) {
    // Never reached: PlatformInfo.hasInternalPlayer is false on desktop.
    onClose(info.startPositionMs)
}
