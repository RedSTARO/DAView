package com.daview.app.ui

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

private data class FoldObservation(
    val feature: FoldingFeature? = null,
    val observed: Boolean = false
)

@Composable
internal fun AndroidAppWindowInfo(activity: ComponentActivity, content: @Composable () -> Unit) {
    var observation by remember(activity) { mutableStateOf(FoldObservation()) }
    var multiWindow by remember(activity) { mutableStateOf(activity.isInMultiWindowMode) }
    var pictureInPicture by remember(activity) { mutableStateOf(activity.isInPictureInPictureMode) }
    var visible by remember(activity) { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var motionEpoch by remember(activity) { mutableStateOf(0) }
    LaunchedEffect(activity) {
        val tracker = WindowInfoTracker.getOrCreate(activity)
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                tracker.windowLayoutInfo(activity).collect { info ->
                    val folds = info.displayFeatures.filterIsInstance<FoldingFeature>()
                    val feature = folds.firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
                        ?: folds.firstOrNull()
                    observation = FoldObservation(feature, observed = true)
                }
            } finally {
                // Keep the latest safe geometry while awaiting a new observation on resume.
                observation = observation.copy(observed = false)
            }
        }
    }
    DisposableEffect(activity) {
        var stoppedAt: Long? = null
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stoppedAt = SystemClock.elapsedRealtime()
                    visible = false
                }
                Lifecycle.Event.ON_START -> {
                    // Keep the departure size across a brief cover/display handoff, not
                    // when returning to the app after ordinary backgrounding.
                    if (stoppedAt?.let { SystemClock.elapsedRealtime() - it > 1_500 } == true) motionEpoch++
                    stoppedAt = null
                    visible = true
                }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(lifecycleObserver)
        val multiWindowListener = Consumer<MultiWindowModeChangedInfo> {
            if (multiWindow != it.isInMultiWindowMode) motionEpoch++
            multiWindow = it.isInMultiWindowMode
        }
        val pipListener = Consumer<PictureInPictureModeChangedInfo> {
            if (pictureInPicture != it.isInPictureInPictureMode) motionEpoch++
            pictureInPicture = it.isInPictureInPictureMode
        }
        activity.addOnMultiWindowModeChangedListener(multiWindowListener)
        activity.addOnPictureInPictureModeChangedListener(pipListener)
        onDispose {
            activity.lifecycle.removeObserver(lifecycleObserver)
            activity.removeOnMultiWindowModeChangedListener(multiWindowListener)
            activity.removeOnPictureInPictureModeChangedListener(pipListener)
        }
    }
    val density = LocalDensity.current
    val fold = if (pictureInPicture) null else with(density) { observation.feature?.let { current ->
        val bounds = current.bounds
        AppFold(
            left = bounds.left.toDp(), top = bounds.top.toDp(),
            right = bounds.right.toDp(), bottom = bounds.bottom.toDp(),
            axis = if (current.orientation == FoldingFeature.Orientation.VERTICAL) FoldAxis.Vertical else FoldAxis.Horizontal,
            posture = if (current.state == FoldingFeature.State.HALF_OPENED) FoldPosture.HalfOpened else FoldPosture.Flat,
            isSeparating = current.isSeparating,
            isOccluding = current.occlusionType == FoldingFeature.OcclusionType.FULL
        )
    } }
    CompositionLocalProvider(
        LocalAppWindowInfo provides AppWindowInfo(
            fold, multiWindow, pictureInPicture,
            foldLayoutObserved = observation.observed,
            isWindowVisible = visible,
            windowMotionEpoch = motionEpoch,
            animateWindowReflow = true
        ),
        content = content
    )
}
