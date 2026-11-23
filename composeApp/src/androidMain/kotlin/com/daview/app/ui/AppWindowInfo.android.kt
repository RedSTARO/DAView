package com.daview.app.ui

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
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

@Composable
internal fun AndroidAppWindowInfo(activity: ComponentActivity, content: @Composable () -> Unit) {
    var feature by remember(activity) { mutableStateOf<FoldingFeature?>(null) }
    var multiWindow by remember(activity) { mutableStateOf(activity.isInMultiWindowMode) }
    var pictureInPicture by remember(activity) { mutableStateOf(activity.isInPictureInPictureMode) }
    LaunchedEffect(activity) {
        val tracker = WindowInfoTracker.getOrCreate(activity)
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            tracker.windowLayoutInfo(activity).collect { info ->
                val folds = info.displayFeatures.filterIsInstance<FoldingFeature>()
                feature = folds.firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
                    ?: folds.firstOrNull()
            }
        }
    }
    DisposableEffect(activity) {
        val multiWindowListener = Consumer<MultiWindowModeChangedInfo> { multiWindow = it.isInMultiWindowMode }
        val pipListener = Consumer<PictureInPictureModeChangedInfo> { pictureInPicture = it.isInPictureInPictureMode }
        activity.addOnMultiWindowModeChangedListener(multiWindowListener)
        activity.addOnPictureInPictureModeChangedListener(pipListener)
        onDispose {
            activity.removeOnMultiWindowModeChangedListener(multiWindowListener)
            activity.removeOnPictureInPictureModeChangedListener(pipListener)
        }
    }
    val density = LocalDensity.current
    val fold = if (pictureInPicture) null else with(density) { feature?.let { current ->
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
    CompositionLocalProvider(LocalAppWindowInfo provides AppWindowInfo(fold, multiWindow, pictureInPicture), content = content)
}
