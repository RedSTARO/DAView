package com.daview.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.TimeSource

private const val ArrivalMillis = 260
private const val ReportPairMillis = 220L

private data class FoldSignal(val axis: FoldAxis, val posture: FoldPosture, val dividesWindow: Boolean)
private data class FoldLayout(val window: AppWindowInfo, val plan: Any, val size: DpSize?, val enabled: Boolean)

/** A significant fullscreen resize can be a cover/inner handoff with no FoldingFeature at all. */
internal fun isLargeWindowReflow(before: DpSize, after: DpSize): Boolean {
    if (before == after || minOf(before.width, before.height, after.width, after.height) <= 0.dp) return false
    fun similar(a: Float, b: Float) = abs(a - b) <= maxOf(32f, minOf(a, b) * 0.08f)
    val (w1, h1) = before.width.value to before.height.value
    val (w2, h2) = after.width.value to after.height.value
    if (similar(w1, h2) && similar(h1, w2)) return false // ordinary rotation
    fun large(a: Float, b: Float) = abs(a - b) >= 144f && maxOf(a, b) >= minOf(a, b) * 1.25f
    // Height-only changes can be an IME or transient system chrome on some devices.
    return large(w1, w2)
}

internal class FoldArrival {
    val progress = Animatable(1f)
}

/**
 * Animate only the new live panes, after safe measurement and placement. A make/break
 * fold and Android's measurement can come in either order. Never replay ordinary navigation.
 */
@Composable
internal fun rememberFoldArrival(
    owner: Any,
    plan: Any,
    size: DpSize? = null,
    enabled: Boolean = true
): FoldArrival? {
    val window = LocalAppWindowInfo.current
    if (!window.foldLayoutObserved && !window.animateWindowReflow) return null
    val allowed = enabled && !window.isInMultiWindow && !window.isInPictureInPicture
    val latest = rememberUpdatedState(FoldLayout(window, plan, size, allowed))
    val arrival = remember(owner, window.windowMotionEpoch) { FoldArrival() }
    LaunchedEffect(arrival) {
        val motionScale = currentCoroutineContext()[MotionDurationScale]
        val clock = TimeSource.Monotonic.markNow()
        var animation: Job? = null
        var previous: FoldLayout? = null
        var signal: FoldSignal? = null
        var hasObservedSignal = false
        var resizedPlanAt: Long? = null
        var foldAt: Long? = null

        snapshotFlow { latest.value }.collect { current ->
            if (!current.enabled || !current.window.isWindowVisible) {
                animation?.cancelAndJoin()
                arrival.progress.snapTo(1f)
                // A brief Activity stop can be the hinge waking the other display. Keep
                // the last visible measurement, but not a pending visual effect.
                if (!current.enabled) {
                    previous = null
                    hasObservedSignal = false
                }
                resizedPlanAt = null
                foldAt = null
                return@collect
            }
            val now = clock.elapsedNow().inWholeMilliseconds
            val before = previous
            val planChanged = before != null && before.plan != current.plan
            val oldSize = before?.size
            val newSize = current.size
            val resizedPlan = planChanged && oldSize != null && newSize != null && oldSize != newSize
            val observed = current.window.fold?.let { FoldSignal(it.axis, it.posture, it.dividesWindow) }
            val foldChanged = current.window.foldLayoutObserved && hasObservedSignal && signal != observed
            if (current.window.foldLayoutObserved) {
                signal = observed
                hasObservedSignal = true
            }
            previous = current

            val largeReflow = current.window.animateWindowReflow && planChanged && oldSize != null && newSize != null &&
                isLargeWindowReflow(oldSize, newSize)
            // Only pair separate callbacks across an actual window reflow. A previous
            // plan-only update (insets, data, hinge coordinates) must not make a later
            // posture report with unchanged layout animate.
            fun recent(at: Long?) = at != null && now - at <= ReportPairMillis
            val pairedFold = foldChanged && (planChanged || recent(resizedPlanAt)) || resizedPlan && recent(foldAt)
            if (!largeReflow && !pairedFold) {
                if (foldChanged) foldAt = now
                if (resizedPlan) resizedPlanAt = now
                return@collect
            }
            resizedPlanAt = null
            foldAt = null
            if (motionScale?.scaleFactor == 0f) return@collect

            // A quick reversal takes the new geometry immediately and restarts the cue,
            // never keeping or moving the outgoing content through the divider.
            animation?.cancelAndJoin()
            animation = launch(start = CoroutineStart.UNDISPATCHED) {
                if (motionScale?.scaleFactor == 0f) return@launch
                arrival.progress.snapTo(0f)
                arrival.progress.animateTo(1f, tween(ArrivalMillis, easing = FastOutSlowInEasing))
            }
        }
    }
    return arrival.takeIf { allowed && window.isWindowVisible }
}

/** Paint only the live Compose content inside its final pane. Never apply this to an AndroidView. */
internal fun Modifier.clipWithFoldArrival(arrival: FoldArrival?, fromAlpha: Float = 0.58f): Modifier =
    if (arrival == null) clipToBounds() else graphicsLayer {
        clip = true
        alpha = fromAlpha + (1f - fromAlpha) * arrival.progress.value
    }
