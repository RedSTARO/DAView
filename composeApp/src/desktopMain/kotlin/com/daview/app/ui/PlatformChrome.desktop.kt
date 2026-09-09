package com.daview.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the window is currently full screen, published by the window itself
 * so anything inside the composition can ask for it without being handed the
 * `WindowState` through every layer between.
 *
 * Null when nothing is providing it, which is the case in previews and tests.
 */
val LocalWindowFullscreen = staticCompositionLocalOf<MutableState<Boolean>?> { null }

/**
 * Full-screen playback on the desktop is just the window going full screen —
 * there are no system bars to hide, the screen does not sleep under a running
 * video, and nothing rotates — so [orientation] has nothing to act on here.
 */
@Composable
actual fun PlaybackPresentation(orientation: ScreenOrientation) {
    val fullscreen = LocalWindowFullscreen.current
    DisposableEffect(fullscreen) {
        val wasFullscreen = fullscreen?.value
        fullscreen?.value = true
        onDispose {
            // Only give the window back if nobody changed it meanwhile: the
            // user may have left full screen by hand while the film ran.
            if (wasFullscreen == false) fullscreen.value = false
        }
    }
}

/** Desktop windows have no system bars whose icons could go unreadable. */
@Composable
actual fun SystemBarAppearance(darkTheme: Boolean) = Unit

/** No system-level back gesture on the desktop; the on-screen arrow is it. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.wheelScrollsHorizontally(state: LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    return onPointerEvent(PointerEventType.Scroll) { event ->
        val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
        // A horizontal wheel, where there is one, takes precedence; otherwise
        // the vertical one is what the hand actually turned.
        val amount = if (delta.x != 0f) delta.x else delta.y
        if (amount == 0f) return@onPointerEvent
        scope.launch { state.scrollBy(amount * WHEEL_STEP) }
    }
}

/** How far one wheel notch moves a poster row: about two thirds of a tile. */
private const val WHEEL_STEP = 100f
