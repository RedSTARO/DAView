package com.daview.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen playback: system bars hidden but swipeable back, the screen kept
 * awake, and the display turned when the player asks for it.
 *
 * All three are window state rather than composition state, so they are undone
 * in [onDispose] — leaving the player screen by any route puts the bars, the
 * timeout and the rotation back the way they were. A phone's player is always
 * full screen, so [fullscreen] has nothing to decide here.
 */
@Composable
actual fun PlaybackPresentation(orientation: ScreenOrientation, fullscreen: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity) {
        val window = activity?.window
        if (window == null) return@DisposableEffect onDispose { }

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousOrientation = activity.requestedOrientation

        // Transient rather than sticky: a swipe brings the bars back for a few
        // seconds, which is how someone checks the time without leaving the film.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = previousOrientation
            // The player's brightness gesture sets it on the window; the rest
            // of the app goes back to the system's.
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    // Turned separately, so changing the lock does not flash the system bars.
    DisposableEffect(activity, orientation) {
        activity?.requestedOrientation = when (orientation) {
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ScreenOrientation.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }
}

@Composable
actual fun SystemBarAppearance(darkTheme: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity, darkTheme) {
        val window = activity?.window
        if (window != null) {
            // Light icons over a dark background and the other way round. The
            // flags name the icons, not the background, which is why they read
            // inverted here.
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
        onDispose { }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}

/**
 * The activity behind a composable's context. Compose hands out the context it
 * was created with, which for an activity-hosted composition is the activity
 * itself, but a themed wrapper in between is legal and does happen.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Touch already drags a row; there is no wheel to translate. */
@Composable
actual fun Modifier.wheelScrollsHorizontally(state: LazyListState): Modifier = this

/** Touch has no hover to show a tooltip on, and every button carries its own label for TalkBack. */
@Composable
actual fun Tooltip(text: String, content: @Composable () -> Unit) = content()

/** Android draws its own fast-scroll affordance in the lists that need one; none here. */
@Composable
actual fun VerticalScrollbarFor(state: LazyGridState, modifier: Modifier) = Unit

@Composable
actual fun VerticalScrollbarFor(state: LazyListState, modifier: Modifier) = Unit

@Composable
actual fun VerticalScrollbarFor(state: ScrollState, modifier: Modifier) = Unit

actual val hasHoverPointer: Boolean = false
