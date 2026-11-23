package com.daview.app.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerPanesTest {
    @Test
    fun separatingTabletopSplitsAtTheActualHingeNotWindowCenter() {
        val viewport = WindowPane(17.dp, 24.dp, 737.dp, 724.dp)
        val fold = AppFold(0.dp, 342.dp, 800.dp, 358.dp, FoldAxis.Horizontal, FoldPosture.HalfOpened,
            isSeparating = true, isOccluding = true)

        val panes = playerPanes(viewport, fold, videoAspect = 16f / 9f)
        assertEquals(WindowPane(17.dp, 24.dp, 737.dp, 342.dp), panes.picture)
        assertEquals(WindowPane(17.dp, 358.dp, 737.dp, 724.dp), panes.tabletopControls)
        assertEquals(panes.tabletopControls, panes.controls)
    }

    @Test
    fun tabletopWithOnlyASmallBottomAreaUsesContiguousTheater() {
        val viewport = WindowPane(0.dp, 0.dp, 800.dp, 480.dp)
        val fold = AppFold(0.dp, 396.dp, 800.dp, 396.dp, FoldAxis.Horizontal, FoldPosture.HalfOpened,
            isSeparating = true, isOccluding = false)

        val panes = playerPanes(viewport, fold, videoAspect = 16f / 9f)
        assertEquals(viewport.copy(bottom = 396.dp), panes.picture)
        assertEquals(panes.picture, panes.controls)
        assertNull(panes.tabletopControls)
    }

    @Test
    fun tabletopUsesFittedVideoAndSafeControlBoundsNotRawPanelDimensions() {
        val viewport = WindowPane(0.dp, 0.dp, 360.dp, 338.dp)
        val hinge = AppFold(0.dp, 160.dp, 360.dp, 170.dp, FoldAxis.Horizontal, FoldPosture.HalfOpened,
            isSeparating = true, isOccluding = true)
        val top = viewport.copy(bottom = 160.dp)
        val deck = viewport.copy(top = 170.dp)
        val aspect = 16f / 9f

        assertEquals(PlayerPanes(top, deck), playerPanes(viewport, hinge, videoAspect = aspect))
        assertEquals(PlayerPanes(top, deck), playerPanes(viewport, hinge,
            pictureBounds = viewport.copy(top = 64.dp), controlBounds = viewport.copy(bottom = 314.dp),
            videoAspect = aspect)) // 96dp picture height, 144dp usable deck.
        assertNull(playerPanes(viewport, hinge, pictureBounds = viewport.copy(top = 65.dp),
            videoAspect = aspect).tabletopControls)
        assertNull(playerPanes(viewport, hinge, controlBounds = viewport.copy(bottom = 313.dp),
            videoAspect = aspect).tabletopControls)
        assertNull(playerPanes(viewport, hinge, controlBounds = viewport.copy(right = 223.dp),
            videoAspect = aspect).tabletopControls)
        assertNull(playerPanes(viewport, hinge, videoAspect = 4f).tabletopControls) // 360×90 after fit.
        assertNull(playerPanes(viewport, hinge, videoAspect = 9f / 16f).tabletopControls) // 90×160 after fit.
        assertNull(playerPanes(viewport, hinge, videoAspect = null).tabletopControls)
        assertNull(playerPanes(viewport, hinge.copy(posture = FoldPosture.Flat), videoAspect = aspect).tabletopControls)
    }

    @Test
    fun singlePanePrefersUsablePictureInsteadOfWidestRawSide() {
        val viewport = WindowPane(0.dp, 0.dp, 500.dp, 360.dp)
        val hinge = AppFold(260.dp, 0.dp, 280.dp, 360.dp, FoldAxis.Vertical, FoldPosture.Flat,
            isSeparating = false, isOccluding = true)
        assertEquals(viewport.copy(right = 260.dp), playerPanes(viewport, hinge, videoAspect = 16f / 9f).picture)
        val safe = viewport.copy(left = 80.dp)
        assertEquals(viewport.copy(left = 280.dp), playerPanes(viewport, hinge,
            pictureBounds = safe, controlBounds = safe, videoAspect = 16f / 9f).picture)
    }

    @Test
    fun usableActionsWinEvenWhenTheOtherPaneFitsALargerPicture() {
        val viewport = WindowPane(0.dp, 0.dp, 360.dp, 220.dp)
        val hinge = AppFold(0.dp, 150.dp, 360.dp, 160.dp, FoldAxis.Horizontal, FoldPosture.Flat,
            isSeparating = false, isOccluding = true)
        val controls = viewport.copy(top = 104.dp)
        assertEquals(viewport.copy(top = 160.dp), playerPanes(viewport, hinge,
            controlBounds = controls, videoAspect = 16f / 9f).picture)
    }

    @Test
    fun bookAndFlatDualPanelChooseOneSafeSideWithStableRtlTieBreak() {
        val viewport = WindowPane(0.dp, 0.dp, 820.dp, 700.dp)
        val hinge = AppFold(400.dp, 0.dp, 420.dp, 700.dp, FoldAxis.Vertical, FoldPosture.Flat,
            isSeparating = true, isOccluding = true)

        assertEquals(viewport.copy(right = 400.dp), playerPanes(viewport, hinge).picture)
        assertEquals(viewport.copy(left = 420.dp), playerPanes(viewport, hinge, rtl = true).picture)
        val book = hinge.copy(left = 320.dp, right = 320.dp, posture = FoldPosture.HalfOpened, isOccluding = false)
        assertEquals(viewport.copy(left = 320.dp), playerPanes(viewport, book).picture)
        assertNull(playerPanes(viewport, book).tabletopControls)
    }

    @Test
    fun flatFlexibleCreaseAndFeatureOutsideSplitScreenDoNotConstrainPlayer() {
        val viewport = WindowPane(0.dp, 50.dp, 340.dp, 700.dp)
        val crease = AppFold(160.dp, 0.dp, 160.dp, 700.dp, FoldAxis.Vertical, FoldPosture.Flat,
            isSeparating = false, isOccluding = false)
        val outside = crease.copy(left = 380.dp, right = 400.dp, isSeparating = true, isOccluding = true)

        assertEquals(PlayerPanes(viewport), playerPanes(viewport, crease))
        assertEquals(PlayerPanes(viewport), playerPanes(viewport, outside))
        assertEquals(PlayerPanes(viewport), playerPanes(viewport, null))
    }

    @Test
    fun partiallyIntersectingOcclusionAtViewportEdgeIsClamped() {
        val viewport = WindowPane(400.dp, 24.dp, 820.dp, 720.dp)
        val hinge = AppFold(390.dp, 0.dp, 420.dp, 720.dp, FoldAxis.Vertical, FoldPosture.Flat,
            isSeparating = false, isOccluding = true)

        assertEquals(viewport.copy(left = 420.dp), playerPanes(viewport, hinge).picture)
    }
}
