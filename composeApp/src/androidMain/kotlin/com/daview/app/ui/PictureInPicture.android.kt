package com.daview.app.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

/** Bridges the foreground activity and its current player without sharing PiP requests between tasks. */
object PipRequest {
    // Everything here runs on the main thread. An old player disposing must not clear the request
    // from a different window (or from its own replacement).
    private val requests = mutableMapOf<Activity, PipPlayback>()
    private val transitions = mutableStateMapOf<Activity, Boolean>()

    val wanted: Boolean get() = requests.isNotEmpty()
    val aspect: Rational? get() = requests.values.singleOrNull()?.aspect

    /** Feature support; the user or the platform can still decline a request. */
    fun supported(activity: Activity): Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    internal fun register(activity: Activity, playback: PipPlayback) {
        requests[activity] = playback
    }

    internal fun unregister(activity: Activity, playback: PipPlayback) {
        if (requests[activity] !== playback) return
        requests.remove(activity)
        onTransition(activity, false)
        // PictureInPictureParams are merged by the platform; omitting this flag would leave
        // auto-entry armed on a non-player screen after Back or an episode hand-over.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }
    }

    /** Manual button (also works when the viewer has deliberately paused). */
    fun enter(activity: Activity): Boolean {
        val playback = requests[activity] ?: return false
        if (playback.aspect == null || activity.isFinishing || activity.isDestroyed ||
            activity.isInPictureInPictureMode || isTransitioning(activity)
        ) return false
        val params = playback.params(activity)
        val entered = runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
        if (entered) {
            if (activity.isInPictureInPictureMode) playback.hideNativeControls() else onTransition(activity, true)
        }
        return entered
    }

    /** Legacy automatic entry. API 31+ uses the params published before Home is pressed. */
    fun onUserLeaveHint(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S && requests[activity]?.autoEnter == true && enter(activity)

    /** API 35 notifies us before the animation starts, not only at its end. */
    fun onTransition(activity: Activity, entering: Boolean) {
        if (entering) {
            val playback = requests[activity] ?: return
            // Hide the native HUD synchronously; Compose removes its own chrome on this state change.
            playback.hideNativeControls()
            transitions[activity] = true
        } else {
            transitions.remove(activity)
        }
    }

    internal fun isTransitioning(activity: Activity?): Boolean =
        activity != null && transitions[activity] == true
}

/** The video is the hint, not the PlayerView's letterbox, controls, or the entire activity. */
internal class PipPlayback(
    private val source: View?,
    val hideNativeControls: () -> Unit
) {
    var aspect: Rational? = null
    var autoEnter: Boolean = false
    private var published: PictureInPictureParams? = null

    fun params(activity: Activity): PictureInPictureParams {
        val ratio = aspect ?: DEFAULT_PIP_ASPECT
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        val rect = Rect()
        // Like Activity's trackPipAnimationHintView, use the visible bounds in root/window
        // coordinates; getLocationOnScreen would add a task offset in multi-window mode.
        if (aspect != null && source?.getGlobalVisibleRect(rect) == true && !rect.isEmpty) {
            // Crop the animation hint, not the actual video, for very wide or tall sources whose
            // PiP ratio must be constrained (or when the platform has clipped the window).
            val targetWidth = (rect.height() * ratio.toDouble()).toInt().coerceAtLeast(1)
            if (targetWidth < rect.width()) {
                rect.left += (rect.width() - targetWidth) / 2
                rect.right = rect.left + targetWidth
            } else {
                val targetHeight = (rect.width() / ratio.toDouble()).toInt().coerceAtLeast(1)
                if (targetHeight < rect.height()) {
                    rect.top += (rect.height() - targetHeight) / 2
                    rect.bottom = rect.top + targetHeight
                }
            }
            builder.setSourceRectHint(rect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter && !activity.isInPictureInPictureMode && !activity.isFinishing)
                .setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    fun publish(activity: Activity) {
        val next = params(activity)
        if (published == next) return
        runCatching { activity.setPictureInPictureParams(next) }
            .onSuccess { published = next }
    }

    private companion object {
        val DEFAULT_PIP_ASPECT = Rational(16, 9)
    }
}

/** Watch placement as well as size: the picture can move to a different foldable pane. */
@Composable
internal fun PublishPlaybackPip(
    activity: Activity?,
    source: View?,
    aspect: Rational?,
    autoEnter: Boolean,
    hideNativeControls: () -> Unit
) {
    val playback = remember(activity, source) { PipPlayback(source, hideNativeControls) }
    DisposableEffect(activity, playback) {
        if (activity == null || !PipRequest.supported(activity)) return@DisposableEffect onDispose { }
        PipRequest.register(activity, playback)
        val observer = activity.window.decorView.viewTreeObserver
        val onLayout = ViewTreeObserver.OnGlobalLayoutListener { playback.publish(activity) }
        observer.addOnGlobalLayoutListener(onLayout)
        onDispose {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(onLayout)
            PipRequest.unregister(activity, playback)
        }
    }
    // Also publish when playback starts/pauses or video aspect changes without a layout pass.
    SideEffect {
        if (activity != null && PipRequest.supported(activity)) {
            playback.aspect = aspect
            playback.autoEnter = autoEnter
            playback.publish(activity)
        }
    }
}

/** PiP mode and the enter transition are separate: only the former changes the picture's layout. */
@Composable
internal fun rememberInPictureInPicture(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findPlayerActivity() as? ComponentActivity }
    var inPip by remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }
        val listener = Consumer<PictureInPictureModeChangedInfo> {
            inPip = it.isInPictureInPictureMode
            PipRequest.onTransition(activity, false)
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

internal tailrec fun Context.findPlayerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findPlayerActivity()
    else -> null
}
