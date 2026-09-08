package com.daview.app.ui

import androidx.compose.runtime.Composable
import com.daview.shared.model.PlaybackInfoDto

/**
 * Browsers cannot decode the Matroska files in these libraries, so the web
 * build offers the native tab player and the PotPlayer hand-off instead of an
 * in-canvas player.
 */
@Composable
actual fun InternalPlayer(
    info: PlaybackInfoDto,
    onProgress: (positionMs: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?) -> Unit,
    onClose: (positionMs: Long) -> Unit
) {
    onClose(info.startPositionMs)
}
