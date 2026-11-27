package com.daview.app.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class FoldAxis { Vertical, Horizontal }
internal enum class FoldPosture { Flat, HalfOpened }

/** Jetpack WindowManager geometry, relative to this activity window (not the display). */
internal data class AppFold(
    val left: Dp,
    val top: Dp,
    val right: Dp,
    val bottom: Dp,
    val axis: FoldAxis,
    val posture: FoldPosture,
    val isSeparating: Boolean,
    val isOccluding: Boolean
) {
    val dividesWindow: Boolean get() = isSeparating || isOccluding
}

internal data class AppWindowInfo(
    val fold: AppFold? = null,
    val isInMultiWindow: Boolean = false,
    val isInPictureInPicture: Boolean = false,
    // A provisional observation must not look like an unfolded window.
    val foldLayoutObserved: Boolean = true,
    val isWindowVisible: Boolean = true,
    val windowMotionEpoch: Int = 0,
    // Android cover/inner handoffs may report only a fullscreen resize.
    val animateWindowReflow: Boolean = false
)

internal val LocalAppWindowInfo = compositionLocalOf { AppWindowInfo(foldLayoutObserved = false) }

/** A rectangle in dp. Coordinates are absolute (not start/end), so RTL cannot mirror a hinge. */
internal data class WindowPane(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp) {
    val width: Dp get() = right - left
    val height: Dp get() = bottom - top
}

internal enum class BrowseNavigation { Rail, Bar, Hidden }

internal val BrowseRailWidth = 96.dp
internal val ExpandedBrowseRailWidth = 220.dp
internal val BrowseBarHeight = 64.dp
private val ExpandedRailBreakpoint = 1200.dp

internal fun browseBarHeight(fontScale: Float) = BrowseBarHeight + (24f * (fontScale - 1f).coerceAtLeast(0f)).dp

internal data class BrowsePanes(
    val main: WindowPane,
    val navigation: BrowseNavigation,
    val detail: WindowPane? = null,
    val divided: Boolean = false,
    val railWidth: Dp = BrowseRailWidth,
    val canExpandRail: Boolean = false
)

/**
 * [viewport] is the system-safe part of this layout in window coordinates, before the IME.
 * A split is a list and a selected detail, not a window-size class. Budget the navigation
 * before deciding whether the list actually fits, and test the two hinge sides independently.
 */
internal fun browsePanes(
    viewport: WindowPane,
    fold: AppFold?,
    pair: Boolean,
    rtl: Boolean,
    fontScale: Float = 1f,
    imeTop: Dp? = null,
    preferRail: Boolean = false,
    expandRail: Boolean = true
): BrowsePanes {
    fun WindowPane.aboveIme() = copy(bottom = imeTop?.coerceIn(top, bottom) ?: bottom)

    val visible = viewport.aboveIme()
    val extraText = (fontScale - 1f).coerceAtLeast(0f)
    val listMin = (320f + 120f * extraText).dp
    val pairMinHeight = (420f + 160f * extraText).dp
    val imeVisible = imeTop != null

    fun navigation(pane: WindowPane, rail: Boolean): BrowseNavigation = when {
        pane.width <= 0.dp || pane.height <= 0.dp -> BrowseNavigation.Hidden
        rail -> BrowseNavigation.Rail
        imeVisible -> BrowseNavigation.Hidden
        else -> BrowseNavigation.Bar
    }

    fun single(pane: WindowPane, divided: Boolean = false): BrowsePanes {
        // Use the pane before IME clipping to pick a rail or bar. Hiding a bottom
        // bar for typing must not turn it into a rail and steal the editor's width.
        val rail = preferRail || pane.width >= (600f + 100f * extraText).dp ||
            (pane.width >= listMin + BrowseRailWidth && pane.height < 430.dp)
        val expandable = !divided && pane.width >= ExpandedRailBreakpoint
        val available = pane.aboveIme()
        return BrowsePanes(
            available, navigation(available, rail), divided = divided,
            railWidth = if (expandable && expandRail) ExpandedBrowseRailWidth else BrowseRailWidth,
            canExpandRail = expandable
        )
    }

    // Strict on an edge: a zero-width/height fold inside the viewport still divides it,
    // but stale geometry lying outside this activity's viewport (e.g. system split) does not.
    if (fold?.dividesWindow == true && fold.left < viewport.right && fold.right > viewport.left &&
        fold.top < viewport.bottom && fold.bottom > viewport.top
    ) {
        val clearance = 8.dp
        return when (fold.axis) {
            FoldAxis.Vertical -> {
                val left = viewport.copy(right = (fold.left - clearance).coerceIn(viewport.left, viewport.right))
                val right = viewport.copy(left = (fold.right + clearance).coerceIn(viewport.left, viewport.right))
                val start = if (rtl) right else left
                val end = if (rtl) left else right
                val availableStart = start.aboveIme()
                val nav = navigation(availableStart, rail = start.width >= listMin + BrowseRailWidth)
                val listWidth = start.width - if (nav == BrowseNavigation.Rail) BrowseRailWidth else 0.dp
                val listHeight = availableStart.height - if (nav == BrowseNavigation.Bar) browseBarHeight(fontScale) else 0.dp
                val detailMin = (352f + 140f * extraText).dp
                if (pair && listWidth >= listMin && end.width >= detailMin && listHeight >= pairMinHeight) {
                    BrowsePanes(availableStart, nav, detail = end.aboveIme(), divided = true)
                } else {
                    single(if (start.width >= end.width) start else end, divided = true)
                }
            }
            FoldAxis.Horizontal -> {
                val top = viewport.copy(bottom = (fold.top - clearance).coerceIn(viewport.top, viewport.bottom))
                val bottom = viewport.copy(top = (fold.bottom + clearance).coerceIn(viewport.top, viewport.bottom))
                // Prefer the touch-facing half when it has room. The IME can take that
                // half away: choose from what is actually visible, not the hinge midpoint.
                val lowerEnough = (320f + 120f * extraText).dp
                val bottomHeight = bottom.aboveIme().height
                single(if (bottomHeight >= lowerEnough || bottomHeight >= top.aboveIme().height) bottom else top, divided = true)
            }
        }
    }

    val gap = 24.dp
    val usableWidth = visible.width - BrowseRailWidth - gap
    val detailMin = (400f + 160f * extraText).dp
    if (!pair || visible.width < 840.dp || visible.height < pairMinHeight || usableWidth < listMin + detailMin) {
        return single(viewport)
    }

    // The reading-side rectangle owns the rail and the list. The gap is empty, not a slot.
    val listWidth = (usableWidth * 0.43f).coerceIn(listMin, minOf(480.dp.coerceAtLeast(listMin), usableWidth - detailMin))
    val mainWidth = BrowseRailWidth + listWidth
    return if (rtl) {
        val split = visible.right - mainWidth
        BrowsePanes(visible.copy(left = split), BrowseNavigation.Rail, visible.copy(right = split - gap))
    } else {
        val split = visible.left + mainWidth
        BrowsePanes(visible.copy(right = split), BrowseNavigation.Rail, visible.copy(left = split + gap))
    }
}
