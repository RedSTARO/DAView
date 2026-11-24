package com.daview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daview.app.ui.AppFold
import com.daview.app.ui.AppWindowInfo
import com.daview.app.ui.FoldAxis
import com.daview.app.ui.FoldPosture
import com.daview.app.ui.LocalAppWindowInfo
import com.daview.app.ui.clipWithFoldArrival
import com.daview.app.ui.rememberFoldArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PixelCopy samples the live layer on Android without advancing its paused Compose clock. */
@RunWith(AndroidJUnit4::class)
class FoldArrivalOnDeviceTest {
    private val durationScale = object : MotionDurationScale {
        @Volatile override var scaleFactor = 1f
    }

    @get:Rule
    val compose = createEmptyComposeRule(effectContext = durationScale)

    @Test
    fun foldFadesOneEntryInPlaceAtFinalBounds() = withPane(Scene()) { scene ->
        val baseline = sample(LeftPane, "initial unfolded frame")
        assertOpaque(baseline, "initial unfolded frame")
        assertArrival(scene, Scene(Book, RightPane), baseline.entryId, "book fold")
        assertArrival(scene, Scene(plan = LeftPane), baseline.entryId, "unfold")
    }

    @Test
    fun substantialFullscreenReflowFadesWithoutEverReportingAHinge() = withPane(
        Scene(window = SizeOnly, size = OuterSize)
    ) { scene ->
        val baseline = sample(LeftPane, "initial cover-sized window")
        assertOpaque(baseline, "initial cover-sized window")
        assertArrival(scene, Scene(SizeOnly, RightPane, size = InnerSize), baseline.entryId, "open-sized reflow")
        assertArrival(scene, Scene(SizeOnly, LeftPane, size = OuterSize), baseline.entryId, "cover-sized reflow")
    }

    @Test
    fun smallResizeRotationAndBackgroundBaselineDoNotFade() = withPane(Scene(window = SizeOnly, size = OuterSize)) { scene ->
        assertOpaque(sample(LeftPane, "initial window"), "initial window")
        assertNoCue(scene, Scene(SizeOnly, RightPane, size = DpSize(430.dp, 800.dp)), "small resize")
        assertNoCue(scene, Scene(SizeOnly, LeftPane, size = DpSize(800.dp, 430.dp)), "ordinary rotation")
        assertNoCue(scene, Scene(SizeOnly.copy(isWindowVisible = false), LeftPane, size = InnerSize), "hidden window")
        assertNoCue(scene, Scene(SizeOnly.copy(windowMotionEpoch = 1), RightPane, size = InnerSize), "new visible baseline")
    }

    private fun assertArrival(scene: MutableState<Scene>, next: Scene, entryId: Int, reason: String) {
        compose.runOnUiThread { scene.value = next }
        // The new bounds are authoritative on the commit frame, even before the cue starts.
        compose.mainClock.advanceTimeByFrame()
        var first = sample(next.plan, "$reason commit", entryId)
        repeat(3) {
            if (first.red >= 0.97f) {
                compose.mainClock.advanceTimeByFrame()
                first = sample(next.plan, "$reason frame ${it + 2}", entryId)
            }
        }
        assertEquals("$reason first dimmed pixel on black", 0.58f, first.red, 0.04f)
        compose.mainClock.advanceTimeBy(80)
        val mid = sample(next.plan, "$reason intermediate frame", entryId)
        assertTrue("$reason should lighten before settling: ${first.red} -> ${mid.red}",
            mid.red > first.red + 0.04f && mid.red < 0.985f)
        compose.mainClock.advanceTimeBy(208)
        assertOpaque(sample(next.plan, "$reason settled", entryId), "$reason settled")
    }

    @Test
    fun zeroDurationScaleNeverDimsEvenOnTheCommitFrame() {
        durationScale.scaleFactor = 0f
        withPane(Scene()) { scene ->
            val baseline = sample(LeftPane, "zero-scale baseline")
            assertOpaque(baseline, "zero-scale baseline")
            compose.runOnUiThread { scene.value = Scene(Book, RightPane) }

            // Observe each frame, including the frame that installs the new plan, not just the end.
            repeat(16) {
                compose.mainClock.advanceTimeByFrame()
                val phase = "zero-scale fold frame ${it + 1}"
                assertOpaque(sample(RightPane, phase, baseline.entryId), phase)
            }
        }
    }

    @Test
    fun baselineAndNonFoldReflowsDoNotDim() = withPane(
        Scene(window = AppWindowInfo(foldLayoutObserved = false))
    ) { scene ->
        assertOpaque(sample(LeftPane, "awaiting first layout report"), "awaiting first layout report")
        assertNoCue(scene, Scene(Book, RightPane), "first observed layout is already folded")

        val shifted = Book.copy(fold = checkNotNull(Book.fold).copy(left = 55.dp, right = 61.dp))
        assertNoCue(scene, Scene(shifted, LeftPane), "same-signature hinge geometry change")
        assertNoCue(scene, Scene(plan = LeftPane), "unfold without a new plan")
        compose.mainClock.advanceTimeBy(192)
        assertNoCue(scene, Scene(plan = RightPane), "ordinary resize after the fold has settled")
        assertNoCue(scene, Scene(Book, LeftPane, owner = "next"), "navigation coincides with fold")
        assertNoCue(
            scene, Scene(AppWindowInfo(isInMultiWindow = true), RightPane, owner = "next"), "split loses the hinge"
        )
        assertNoCue(scene, Scene(Book, LeftPane, owner = "next"), "return from split")
        assertNoCue(
            scene, Scene(AppWindowInfo(isInPictureInPicture = true), RightPane, owner = "next"), "PiP loses the hinge"
        )
    }

    private fun assertNoCue(scene: MutableState<Scene>, next: Scene, reason: String) {
        compose.runOnUiThread { scene.value = next }
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            val phase = "$reason, frame ${it + 1}"
            assertOpaque(sample(next.plan, phase), phase)
        }
    }

    private fun withPane(initial: Scene, test: (MutableState<Scene>) -> Unit) {
        compose.mainClock.autoAdvance = false
        val scene = mutableStateOf(initial)
        // Reuse the declared activity; the empty rule needs no ui-test-manifest dependency.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Use a fresh ComposeView so the baseline is an initial composition, not a recompose.
                activity.setContentView(ComposeView(activity).apply { setContent { Pane(scene.value) } })
            }
            test(scene)
        }
    }

    private fun sample(plan: IntRect, phase: String, expectedEntryId: Int? = null): Sample {
        // A single-node fetch fails on both missing and duplicate tagged entries.
        val entry = compose.onNodeWithTag(EntryTag, useUnmergedTree = true).fetchSemanticsNode(phase)
        if (expectedEntryId != null) assertEquals("$phase: replaced entry", expectedEntryId, entry.id)
        val fixture = compose.onNodeWithTag(FixtureTag)
        val bounds = fixture.fetchSemanticsNode().boundsInRoot
        assertEquals("$phase: entry bounds", Rect(
            bounds.left + plan.left, bounds.top + plan.top, bounds.left + plan.right, bounds.top + plan.bottom
        ), entry.boundsInRoot)

        // Capture the opaque backdrop too, so the white pane's red channel measures its layer alpha.
        val image = fixture.captureToImage()
        assertEquals("$phase: fixture width", FixtureWidth, image.width)
        assertEquals("$phase: fixture height", FixtureHeight, image.height)
        val pixels = image.toPixelMap()
        val center = plan.center
        val empty = (if (plan == LeftPane) RightPane else LeftPane).center
        val red = pixels[center.x, center.y].red
        assertEquals("$phase: uniform fill", red, pixels[plan.left + 1, plan.top + 1].red, 0.01f)
        assertEquals("$phase: old/unused pane", Color.Black, pixels[empty.x, empty.y])
        assertEquals("$phase: clipped left edge", Color.Black, pixels[plan.left - 1, center.y])
        assertEquals("$phase: clipped right edge", Color.Black, pixels[plan.right, center.y])
        return Sample(entry.id, red)
    }

    private fun assertOpaque(sample: Sample, phase: String) = assertEquals(phase, 1f, sample.red, 0.01f)

    @Composable
    private fun Pane(scene: Scene) {
        CompositionLocalProvider(
            LocalAppWindowInfo provides scene.window,
            LocalDensity provides Density(1f),
            LocalLayoutDirection provides LayoutDirection.Ltr
        ) {
            val arrival = rememberFoldArrival(scene.owner, scene.plan, size = scene.size)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(FixtureWidth.dp, FixtureHeight.dp).background(Color.Black).testTag(FixtureTag)) {
                    val plan = scene.plan
                    Box(Modifier.absoluteOffset(plan.left.dp, plan.top.dp)
                        .size(plan.width.dp, plan.height.dp)
                        .clipWithFoldArrival(arrival)
                        .drawBehind {
                            // Deliberate uniform overdraw: only the production modifier clips it.
                            drawRect(Color.White, Offset(-3f, -3f), Size(size.width + 6f, size.height + 6f))
                        }
                        .testTag(EntryTag))
                }
            }
        }
    }

    private data class Scene(
        val window: AppWindowInfo = AppWindowInfo(),
        val plan: IntRect = LeftPane,
        val owner: String = "current",
        val size: DpSize? = null
    )

    private data class Sample(val entryId: Int, val red: Float)

    private companion object {
        const val FixtureTag = "fold fixture"
        const val EntryTag = "live entry"
        const val FixtureWidth = 108
        const val FixtureHeight = 56
        val LeftPane = IntRect(8, 12, 38, 44)
        val RightPane = IntRect(70, 12, 100, 44)
        val SizeOnly = AppWindowInfo(foldLayoutObserved = false, animateWindowReflow = true)
        val OuterSize = DpSize(400.dp, 800.dp)
        val InnerSize = DpSize(840.dp, 880.dp)
        val Book = AppWindowInfo(fold = AppFold(
            left = 51.dp, top = 0.dp, right = 57.dp, bottom = FixtureHeight.dp,
            axis = FoldAxis.Vertical, posture = FoldPosture.HalfOpened,
            isSeparating = true, isOccluding = true
        ))
    }
}
