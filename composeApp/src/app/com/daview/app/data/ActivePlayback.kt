package com.daview.app.data

/**
 * Whether an external player is currently being fed by this process.
 *
 * The desktop window needs to know before it closes: the bytes that player is
 * showing come from a socket inside this process, so quitting cuts the film off
 * mid-frame. The window is created outside the composition and has no route to
 * [PlaybackController], hence a plain flag rather than state passed down.
 */
object ActivePlayback {
    @Volatile
    var externalRunning: Boolean = false
}
