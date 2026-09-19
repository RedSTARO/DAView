package com.daview.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
 * Whether it goes full screen at all is the viewer's setting.
 */
@Composable
actual fun PlaybackPresentation(orientation: ScreenOrientation, fullscreen: Boolean) {
    val window = LocalWindowFullscreen.current
    DisposableEffect(window, fullscreen) {
        val wasFullscreen = window?.value
        if (fullscreen) window?.value = true
        onDispose {
            // Only give the window back if nobody changed it meanwhile: the
            // user may have left full screen by hand while the film ran.
            if (fullscreen && wasFullscreen == false) window.value = false
        }
    }
}

/** Desktop windows have no system bars whose icons could go unreadable. */
@Composable
actual fun SystemBarAppearance(darkTheme: Boolean) = Unit

/** No system-level back gesture on the desktop; the keyboard and mouse have their own. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.wheelScrollsHorizontally(state: LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    // The initial pass, so the row sees the event before the page does and can
    // keep it when it is the row's to take.
    return onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        val delta = change.scrollDelta
        val amount = when {
            delta.x != 0f -> delta.x
            // Shift turns the vertical wheel sideways, as it does in every
            // desktop list that scrolls both ways.
            event.keyboardModifiers.isShiftPressed && delta.y != 0f -> delta.y
            // A plain vertical turn belongs to the page.
            else -> return@onPointerEvent
        }
        change.consume()
        scope.launch { state.scrollBy(amount * WHEEL_STEP) }
    }
}

/** How far one wheel notch moves a poster row: about two thirds of a tile. */
private const val WHEEL_STEP = 100f

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Tooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 4.dp
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 20.dp))
    ) {
        content()
    }
}

@Composable
private fun themedScrollbarStyle() = defaultScrollbarStyle().copy(
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
    hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
)

@Composable
actual fun VerticalScrollbarFor(state: LazyGridState, modifier: Modifier) {
    CompositionLocalProvider(LocalScrollbarStyle provides themedScrollbarStyle()) {
        VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier)
    }
}

@Composable
actual fun VerticalScrollbarFor(state: LazyListState, modifier: Modifier) {
    CompositionLocalProvider(LocalScrollbarStyle provides themedScrollbarStyle()) {
        VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier)
    }
}

@Composable
actual fun VerticalScrollbarFor(state: ScrollState, modifier: Modifier) {
    CompositionLocalProvider(LocalScrollbarStyle provides themedScrollbarStyle()) {
        VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier)
    }
}

actual val hasHoverPointer: Boolean = true
