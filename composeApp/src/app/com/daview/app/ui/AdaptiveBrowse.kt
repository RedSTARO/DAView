@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.daview.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.daview.app.data.BackStackEntry
import com.daview.app.data.Screen
import com.daview.app.data.isBrowseSource

/** Only the nearest catalogue destination can accompany a selected detail. */
internal fun browseSource(stack: List<BackStackEntry>): BackStackEntry? =
    if (stack.lastOrNull()?.screen is Screen.Detail) {
        stack.lastOrNull { it.screen !is Screen.Detail }?.takeIf { it.screen.isBrowseSource() }
    } else null

/** Non-null only when a popup must stay on its side of a divider in the viewport. */
internal val LocalBrowsePane = compositionLocalOf<WindowPane?> { null }
internal val LocalSelectedBrowseItem = compositionLocalOf<String?> { null }
internal val LocalBrowseCompanionVisible = compositionLocalOf { false }

private val LocalBrowseEditorFocus = compositionLocalOf<(Boolean) -> Unit> { {} }

/** Keep a focused catalogue editor visible even if IME insets make the pair too short. */
internal fun Modifier.trackBrowseEditorFocus(): Modifier = composed {
    val report = LocalBrowseEditorFocus.current
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(report) { onDispose { if (focused) report(false) } }
    onFocusChanged {
        if (focused != it.isFocused) {
            focused = it.isFocused
            report(focused)
        }
    }
}

private enum class BrowseSlot { Navigation, Messages, Blocking }

/** Changes in pane geometry only remeasure and place the keyed entries that are still visible. */
@Composable
internal fun AdaptiveBrowse(
    current: BackStackEntry,
    source: BackStackEntry?,
    preferExpandedRail: Boolean,
    onToggleRail: () -> Unit,
    page: @Composable (BackStackEntry) -> Unit,
    navigation: @Composable (rail: Boolean, expanded: Boolean, toggleRail: (() -> Unit)?, sourceVisible: Boolean) -> Unit,
    messages: @Composable () -> Unit,
    blocking: @Composable () -> Unit,
    dialogs: @Composable () -> Unit
) {
    val fold = LocalAppWindowInfo.current.fold
    var origin by remember { mutableStateOf(Offset.Zero) }
    var focusedEditor by remember { mutableStateOf<Long?>(null) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val rtl = direction == LayoutDirection.Rtl
    val ime = WindowInsets.ime
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    // A side system bar can change visibility while the keyboard animates. Keep its
    // horizontal clearance so the browse pane doesn't resize with that visibility.
    val safeHorizontal = safe.union(platformStableBrowseInsets())
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { origin = it.positionInWindow() }) {
        val x = with(density) { origin.x.toDp() }
        val y = with(density) { origin.y.toDp() }
        val leftInset = with(density) { safeHorizontal.getLeft(this, direction).toDp() }
        val rightInset = with(density) { safeHorizontal.getRight(this, direction).toDp() }
        val topInset = with(density) { safe.getTop(this).toDp() }
        val safeBottom = with(density) { safe.getBottom(this).toDp() }
        val imeBottom = with(density) { ime.getBottom(this).toDp() }
        val right = x + maxWidth
        val bottom = y + maxHeight
        val left = (x + leftInset).coerceAtMost(right)
        val top = (y + topInset).coerceAtMost(bottom)
        val viewport = WindowPane(left, top, (right - rightInset).coerceAtLeast(left), (bottom - safeBottom).coerceAtLeast(top))
        val imeTop = (bottom - imeBottom).takeIf { imeBottom > safeBottom }
        val panes = browsePanes(
            viewport, fold, source != null, rtl, density.fontScale, imeTop,
            preferRail = hasHoverPointer, expandRail = preferExpandedRail
        )

        val detailPane = panes.detail
        val sourceVisible = source != null && (detailPane != null || (imeTop != null && focusedEditor == source.key))
        val mainEntry = if (sourceVisible) checkNotNull(source) else current
        val detailEntry = current.takeIf { detailPane != null }
        val selectedId = (current.screen as? Screen.Detail)?.itemId
        val rail = panes.navigation == BrowseNavigation.Rail

        val main = panes.main
        val barHeight = browseBarHeight(density.fontScale)
        // Animate explicit rail toggles only. Breakpoints and hinge changes take their
        // safe geometry immediately, never moving live content across a fold.
        val railWidth = key(panes.canExpandRail) {
            if (panes.canExpandRail) {
                val width by animateDpAsState(panes.railWidth, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "browse-rail-width")
                width.coerceIn(BrowseRailWidth, ExpandedBrowseRailWidth)
            } else panes.railWidth
        }.coerceAtMost(main.width)
        val navPane = when (panes.navigation) {
            BrowseNavigation.Rail -> if (rtl) main.copy(left = main.right - railWidth)
                else main.copy(right = main.left + railWidth)
            BrowseNavigation.Bar -> main.copy(top = (main.bottom - barHeight).coerceAtLeast(main.top))
            BrowseNavigation.Hidden -> null
        }
        val mainPane = when {
            navPane == null -> main
            !rail -> main.copy(bottom = navPane.top)
            rtl -> main.copy(right = navPane.left)
            else -> main.copy(left = navPane.right)
        }
        val activePane = detailPane ?: mainPane
        val arrival = rememberFoldArrival(
            owner = current.key to source?.key,
            plan = panes,
            size = DpSize(maxWidth, maxHeight),
            enabled = imeTop == null
        )

        // Pages (including their artwork) stay in safe panes. Only navigation
        // backgrounds extend into the system-bar strips at exterior edges.
        fun WindowPane.allowDecoration(candidate: WindowPane): WindowPane = candidate.takeUnless {
            fold != null && fold.dividesWindow && fold.left < it.right && fold.right > it.left &&
                fold.top < it.bottom && fold.bottom > it.top
        } ?: this

        fun WindowPane.atStatusEdge(): WindowPane =
            if (height > 0.dp && top == viewport.top) allowDecoration(copy(top = y)) else this

        val navPaint = navPane?.let { pane ->
            val paint = if (rail) pane.atStatusEdge() else pane
            if (imeTop == null && pane.height > 0.dp && pane.bottom == viewport.bottom) {
                paint.allowDecoration(paint.copy(bottom = bottom))
            } else paint
        }
        val navColor = MaterialTheme.colorScheme.surfaceContainer
        val expanded = rail && panes.canExpandRail && railWidth >= (BrowseRailWidth + ExpandedBrowseRailWidth) / 2
        val toggleRail = onToggleRail.takeIf { panes.canExpandRail }
        fun consumed() = PaddingValues(
            start = if (rtl) rightInset else leftInset,
            end = if (rtl) leftInset else rightInset,
            top = topInset,
            bottom = maxOf(safeBottom, imeBottom)
        )

        Layout(content = {
            if (navPane != null && navPaint != null) {
                Box(
                    Modifier.layoutId(BrowseSlot.Navigation).clipWithFoldArrival(arrival, fromAlpha = 0.76f)
                        .background(navColor)
                        .padding(top = navPane.top - navPaint.top, bottom = navPaint.bottom - navPane.bottom)
                        .consumeWindowInsets(consumed())
                ) {
                    // A navigation menu is anchored near an edge. Keep it in the
                    // measured safe pane even when its host is a smaller split task
                    // inside a larger platform window.
                    CompositionLocalProvider(LocalBrowsePane provides main) {
                        navigation(rail, expanded, toggleRail, sourceVisible)
                    }
                }
            }
            // Never key a page by slot, orientation, or presence of navigation. When a page is
            // covered, App's SaveableStateProvider retains its state under this same entry key.
            listOfNotNull(mainEntry, detailEntry).forEach { entry ->
                key(entry.key) {
                    val companion = entry == detailEntry
                    val pane = if (companion) checkNotNull(detailPane) else mainPane
                    val reportFocus: (Boolean) -> Unit = remember(entry.key) {
                        { focused ->
                            if (focused) focusedEditor = entry.key
                            else if (focusedEditor == entry.key) focusedEditor = null
                        }
                    }
                    Box(
                        Modifier.layoutId(entry.key).clipWithFoldArrival(arrival)
                            .consumeWindowInsets(consumed())
                    ) {
                        CompositionLocalProvider(
                            LocalBrowsePane provides pane.takeIf { panes.divided },
                            LocalBrowseCompanionVisible provides companion,
                            LocalSelectedBrowseItem provides selectedId.takeIf { sourceVisible && entry == source },
                            LocalBrowseEditorFocus provides reportFocus
                        ) {
                            page(entry)
                        }
                    }
                }
            }
            Box(Modifier.layoutId(BrowseSlot.Messages).clipToBounds()) { messages() }
            Box(Modifier.layoutId(BrowseSlot.Blocking).clipToBounds().consumeWindowInsets(consumed())) {
                CompositionLocalProvider(LocalBrowsePane provides activePane.takeIf { panes.divided }) {
                    blocking()
                    dialogs()
                }
            }
        }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            fun width(pane: WindowPane) = pane.right.roundToPx() - pane.left.roundToPx()
            fun height(pane: WindowPane) = pane.bottom.roundToPx() - pane.top.roundToPx()
            fun left(pane: WindowPane) = pane.left.roundToPx() - x.roundToPx()
            fun top(pane: WindowPane) = pane.top.roundToPx() - y.roundToPx()
            fun fixed(pane: WindowPane) = Constraints.fixed(width(pane), height(pane))

            val nav = navPaint?.let { pane ->
                measurables.first { it.layoutId == BrowseSlot.Navigation }.measure(fixed(pane))
            }
            val mainPage = measurables.first { it.layoutId == mainEntry.key }.measure(fixed(mainPane))
            val detailPage = detailPane?.let { pane ->
                measurables.first { it.layoutId == detailEntry?.key }.measure(fixed(pane))
            }
            val message = measurables.first { it.layoutId == BrowseSlot.Messages }.measure(
                Constraints(maxWidth = width(activePane), maxHeight = height(activePane))
            )
            val overlay = measurables.first { it.layoutId == BrowseSlot.Blocking }.measure(fixed(activePane))
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (nav != null && navPaint != null) nav.place(left(navPaint), top(navPaint))
                mainPage.place(left(mainPane), top(mainPane))
                if (detailPage != null && detailPane != null) detailPage.place(left(detailPane), top(detailPane))
                message.place(left(activePane) + (width(activePane) - message.width) / 2, top(activePane) + height(activePane) - message.height)
                overlay.place(left(activePane), top(activePane))
            }
        }
    }
}
