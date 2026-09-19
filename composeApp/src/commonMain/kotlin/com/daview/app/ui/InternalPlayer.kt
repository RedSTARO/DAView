package com.daview.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.daview.shared.model.PlaybackInfoDto

/**
 * What the player screen keeps across the episodes it plays. The player itself
 * is rebuilt for every session — reusing one across episodes is what left the
 * Android player showing the end of the last one — so anything the viewer set
 * on it has to live one level up.
 */
class PlayerScreenState {
    var orientation by mutableStateOf(ScreenOrientation.SENSOR)

    /** Subtitle delay the viewer dialled in, in milliseconds. */
    var subtitleDelayMs by mutableStateOf(0L)

    /** Playback speed. */
    var speed by mutableStateOf(1f)

    /**
     * Touch locked against pockets and small hands (Android). Kept across
     * episodes: the next one rolling in is exactly when nobody is holding it.
     */
    var locked by mutableStateOf(false)

    /** Subtitle size set from inside the player, over the app's setting. */
    var subtitleScale by mutableStateOf<Float?>(null)
}

/**
 * In-app playback surface: libmpv on the desktop, media3 on Android. One call
 * plays one session; the screen composes a fresh one for the next.
 */
@Composable
expect fun InternalPlayer(
    info: PlaybackInfoDto,
    screen: PlayerScreenState,
    subtitleScale: Float,
    onProgress: (positionMs: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?) -> Unit,
    onClose: (positionMs: Long) -> Unit,
    /**
     * The file played to its end, as opposed to the screen being left. The
     * position is passed so the session can still be closed at the right place
     * when there is nothing to play next.
     */
    onEnded: (positionMs: Long) -> Unit,
    /** Previous or next episode, asked for from the player's own controls. */
    onSkip: (itemId: String, positionMs: Long) -> Unit
)
