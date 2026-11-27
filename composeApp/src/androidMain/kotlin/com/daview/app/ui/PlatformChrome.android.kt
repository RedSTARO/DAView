package com.daview.app.ui

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
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

/** Android playback is fullscreen (the startup fullscreen preference is desktop-only). */
@Composable
actual fun PlaybackPresentation(orientation: ScreenOrientation, fullscreen: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findPlayerActivity() }
    val window = activity?.window

    // Keep these independent of geometry and episode; restore only upon leaving playback.
    DisposableEffect(window) {
        if (window == null) return@DisposableEffect onDispose { }
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val wasKeepingScreenOn = window.attributes.flags and flag != 0
        val previousBrightness = window.attributes.screenBrightness
        window.addFlags(flag)
        onDispose {
            if (!wasKeepingScreenOn) window.clearFlags(flag)
            window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
        }
    }

    val windowInfo = LocalAppWindowInfo.current
    val floating = windowInfo.isInMultiWindow || windowInfo.isInPictureInPicture
    val immersive = !floating
    DisposableEffect(window, immersive) {
        if (window == null || !immersive) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val appBars = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        controller.hide(appBars)
        onDispose {
            controller.systemBarsBehavior = previousBehavior
            if (activity?.isInPictureInPictureMode != true) controller.show(appBars)
        }
    }
    DisposableEffect(window) {
        if (window == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }

    // Only an explicit request may turn the display; never rotate a split task or a hinge posture.
    val lockLandscape = orientation == ScreenOrientation.LANDSCAPE && !floating && windowInfo.fold?.dividesWindow != true
    DisposableEffect(activity, lockLandscape) {
        if (activity == null || !lockLandscape) return@DisposableEffect onDispose { }
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity.requestedOrientation = previousOrientation }
    }
}

@Composable
actual fun SystemBarAppearance(darkTheme: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findPlayerActivity() }

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal actual fun platformStableBrowseInsets(): WindowInsets = WindowInsets.systemBarsIgnoringVisibility

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
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
