package com.daview.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** How a playing video would like the display turned. */
enum class ScreenOrientation {
    /** Follow the device, the way the rest of the app does. */
    SENSOR,

    /** Hold landscape, for someone watching lying down with rotation off. */
    LANDSCAPE
}

/**
 * Puts the platform into playback presentation for as long as this composable
 * is in the tree, and puts it back on the way out.
 *
 * What that means is per platform — Android hides the system bars, keeps the
 * screen awake and can turn the display; a desktop window only goes full
 * screen, and only when [fullscreen] asks it to — so the shared player screen
 * says what it wants rather than how to get it. It sits on the player screen
 * rather than in the player, so the hand-over from one episode to the next
 * does not drop out of full screen and back in.
 */
@Composable
expect fun PlaybackPresentation(orientation: ScreenOrientation, fullscreen: Boolean)

/**
 * Keeps the system bars' icons legible against the app's own background.
 *
 * The app carries its own light/dark switch, and nothing about the system's
 * night setting follows it, so the platform has to be told which one is on:
 * left alone, a phone in light mode draws black status bar icons over this
 * app's near-black background.
 */
@Composable
expect fun SystemBarAppearance(darkTheme: Boolean)

/**
 * Routes the platform's own "go back" gesture into the app's back stack while
 * [enabled]. On Android that is the system back gesture, which otherwise
 * finishes the activity from any screen; elsewhere there is nothing to route.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * Lets a horizontal wheel — or a vertical one with Shift held — move a
 * horizontal list, and leaves a plain vertical turn to the page.
 *
 * The earlier version turned every vertical notch over a row into sideways
 * movement without consuming it, so the row and the page it sat in both moved.
 * Rows carry their own arrow buttons for anyone without a sideways wheel.
 */
@Composable
expect fun Modifier.wheelScrollsHorizontally(state: LazyListState): Modifier

/**
 * A short label shown when the pointer rests on [content]. Icon-only buttons
 * said what they did only to a screen reader; on the desktop there was no way
 * to find out short of pressing them.
 */
@Composable
expect fun Tooltip(text: String, content: @Composable () -> Unit)

/** A scrollbar beside a long list, where the platform draws one. */
@Composable
expect fun VerticalScrollbarFor(state: LazyGridState, modifier: Modifier)

@Composable
expect fun VerticalScrollbarFor(state: LazyListState, modifier: Modifier)

@Composable
expect fun VerticalScrollbarFor(state: ScrollState, modifier: Modifier)

/** True where a pointer can hover, which is where hover-only affordances make sense. */
expect val hasHoverPointer: Boolean
