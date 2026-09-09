package com.daview.app.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer

/**
 * What the activity needs to know to put the video into a corner of the screen
 * when the user leaves.
 *
 * The player lives in the composition and the "user is leaving" callback is on
 * the activity, so the two meet here. Only the aspect ratio and a flag: the
 * activity does not need to know anything else about what is playing.
 */
object PipRequest {

    /** True while a player is on screen and would like the corner treatment. */
    @Volatile
    var wanted: Boolean = false

    /** The video's shape, so the window is not letterboxed inside its own tile. */
    @Volatile
    var aspect: Rational? = null

    /** Whether the platform can do it at all, and the user has not forbidden it. */
    fun supported(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
            )

    fun params(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val builder = PictureInPictureParams.Builder()
        // Android refuses anything narrower than 1:2.39 or wider than 2.39:1.
        aspect?.takeIf { it.toFloat() in 0.42f..2.39f }?.let { builder.setAspectRatio(it) }
        return builder.build()
    }

    /** Asks the platform for it. False when it declined, or cannot. */
    fun enter(activity: Activity): Boolean {
        if (!wanted || !supported(activity)) return false
        val params = params() ?: return false
        return runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
    }
}

/**
 * Whether the activity is currently a small floating window.
 *
 * The player uses it to strip its own furniture: at a couple of hundred pixels
 * across, a title and three buttons are the whole tile.
 */
@Composable
fun rememberInPictureInPicture(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    var inPip by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                activity?.isInPictureInPictureMode == true
        )
    }

    DisposableEffect(activity) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return@DisposableEffect onDispose { }
        }
        val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> {
            inPip = it.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

private tailrec fun android.content.Context.findComponentActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is android.content.ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
