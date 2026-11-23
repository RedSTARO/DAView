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
    val isInPictureInPicture: Boolean = false
)

internal val LocalAppWindowInfo = compositionLocalOf { AppWindowInfo() }

/** A rectangle in dp. Coordinates are absolute (not start/end), so RTL cannot mirror a hinge. */
internal data class WindowPane(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp) {
    val width: Dp get() = right - left
    val height: Dp get() = bottom - top
}

internal enum class BrowseNavigation { Rail, Bar, Hidden }

internal val BrowseRailWidth = 88.dp
internal val BrowseBarHeight = 80.dp
internal val CompactBrowseBarHeight = 64.dp

internal data class BrowsePanes(
    val main: WindowPane,
    val navigation: BrowseNavigation,
    val detail: WindowPane? = null,
    val divided: Boolean = false
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
    imeTop: Dp? = null
): BrowsePanes {
    val visible = viewport.copy(bottom = imeTop?.coerceIn(viewport.top, viewport.bottom) ?: viewport.bottom)
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
        val rail = pane.width >= (600f + 100f * extraText).dp ||
            (pane.width >= listMin + BrowseRailWidth && pane.height < 430.dp)
        return BrowsePanes(pane, navigation(pane, rail), divided = divided)
    }

    // Strict on an edge: a zero-width/height fold inside the viewport still divides it,
    // but stale geometry lying outside this activity's viewport (e.g. system split) does not.
    if (fold?.dividesWindow == true && fold.left < viewport.right && fold.right > viewport.left &&
        fold.top < viewport.bottom && fold.bottom > viewport.top
    ) {
        val clearance = 8.dp
        return when (fold.axis) {
            FoldAxis.Vertical -> {
                val left = visible.copy(right = (fold.left - clearance).coerceIn(visible.left, visible.right))
                val right = visible.copy(left = (fold.right + clearance).coerceIn(visible.left, visible.right))
                val start = if (rtl) right else left
                val end = if (rtl) left else right
                val nav = navigation(start, rail = start.width >= listMin + BrowseRailWidth)
                val listWidth = start.width - if (nav == BrowseNavigation.Rail) BrowseRailWidth else 0.dp
                val listHeight = start.height - if (nav == BrowseNavigation.Bar) BrowseBarHeight else 0.dp
                val detailMin = (352f + 140f * extraText).dp
                if (pair && listWidth >= listMin && end.width >= detailMin && listHeight >= pairMinHeight) {
                    BrowsePanes(start, nav, detail = end, divided = true)
                } else {
                    single(if (start.width >= end.width) start else end, divided = true)
                }
            }
            FoldAxis.Horizontal -> {
                val top = visible.copy(bottom = (fold.top - clearance).coerceIn(visible.top, visible.bottom))
                val bottom = visible.copy(top = (fold.bottom + clearance).coerceIn(visible.top, visible.bottom))
                // Prefer the touch-facing half when it has room. The IME can take that
                // half away: choose from what is actually visible, not the hinge midpoint.
                val lowerEnough = (320f + 120f * extraText).dp
                single(if (bottom.height >= lowerEnough || bottom.height >= top.height) bottom else top, divided = true)
            }
        }
    }

    val gap = 12.dp
    val usableWidth = visible.width - BrowseRailWidth - gap
    val detailMin = (400f + 160f * extraText).dp
    if (!pair || visible.width < 840.dp || visible.height < pairMinHeight || usableWidth < listMin + detailMin) {
        return single(visible)
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
