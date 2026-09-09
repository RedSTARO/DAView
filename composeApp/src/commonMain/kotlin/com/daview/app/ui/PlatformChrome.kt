package com.daview.app.ui

import androidx.compose.runtime.Composable

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
 * screen awake and can turn the display; a desktop window has none of those
 * problems and only needs to go full screen — so the shared player says what it
 * wants rather than how to get it.
 */
@Composable
expect fun PlaybackPresentation(orientation: ScreenOrientation)

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
