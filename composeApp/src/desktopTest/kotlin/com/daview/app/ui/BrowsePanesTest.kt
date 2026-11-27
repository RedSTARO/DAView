package com.daview.app.ui

import androidx.compose.ui.unit.dp
import com.daview.app.data.BackStackEntry
import com.daview.app.data.Screen
import com.daview.app.data.ShelfKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowsePanesTest {
    private val expanded = WindowPane(0.dp, 24.dp, 848.dp, 824.dp)

    @Test
    fun flatLayoutNeedsASelectionAndTwoUsablePanesAfterNavigation() {
        assertEquals(expanded, browsePanes(expanded, null, pair = false, rtl = false).main)
        assertNull(browsePanes(expanded, null, pair = false, rtl = false).detail)
        for (size in listOf(expanded.copy(right = 608.dp), expanded.copy(bottom = 400.dp))) {
            assertEquals(size, browsePanes(size, null, pair = true, rtl = false).main)
            assertNull(browsePanes(size, null, pair = true, rtl = false).detail)
        }
        assertNull(browsePanes(expanded, null, pair = true, rtl = false, fontScale = 2f).detail)
        assertNull(browsePanes(expanded, null, pair = true, rtl = false, imeTop = 410.dp).detail)
        val pair = browsePanes(expanded, null, pair = true, rtl = false)
        val detail = assertNotNull(pair.detail)
        assertEquals(BrowseNavigation.Rail, pair.navigation)
        assertTrue(pair.main.width - BrowseRailWidth >= 320.dp)
        assertTrue(detail.width >= 400.dp && pair.main.right < detail.left)
        val rtl = browsePanes(expanded, null, pair = true, rtl = true)
        assertEquals(pair.main.width, rtl.main.width)
        assertTrue(assertNotNull(rtl.detail).right < rtl.main.left)
    }

    @Test
    fun bookUsesRealPanesWithOneNavigationSurfaceAndAccountsForRtl() {
        val book = AppFold(400.dp, 0.dp, 420.dp, 860.dp, FoldAxis.Vertical, FoldPosture.HalfOpened, true, false)
        val ltr = browsePanes(expanded, book, pair = true, rtl = false)
        assertEquals(392.dp, ltr.main.right)
        assertEquals(428.dp, assertNotNull(ltr.detail).left)
        assertEquals(BrowseNavigation.Bar, ltr.navigation)
        val rtl = browsePanes(expanded, book.copy(isSeparating = false, isOccluding = true), pair = true, rtl = true)
        assertEquals(428.dp, rtl.main.left)
        assertEquals(392.dp, assertNotNull(rtl.detail).right)
        assertEquals(BrowseNavigation.Rail, rtl.navigation)
        assertNull(browsePanes(expanded, book, pair = true, rtl = false, fontScale = 2f).detail)
        // A single page chooses one side, even with plenty of width overall.
        val single = browsePanes(expanded, book, pair = false, rtl = false)
        assertEquals(428.dp, single.main.left)
        assertNull(single.detail)
    }

    @Test
    fun zeroWidthDividersMatterButCreasesAndOutOfViewportHingesDoNot() {
        val fold = AppFold(424.dp, 0.dp, 424.dp, 860.dp, FoldAxis.Vertical, FoldPosture.Flat, true, false)
        val book = browsePanes(expanded, fold, pair = true, rtl = false)
        assertEquals(416.dp, book.main.right)
        assertEquals(432.dp, assertNotNull(book.detail).left)
        val flat = browsePanes(expanded, null, pair = true, rtl = false)
        assertEquals(flat, browsePanes(expanded, fold.copy(isSeparating = false), pair = true, rtl = false))
        assertEquals(flat, browsePanes(expanded, fold.copy(top = 900.dp, bottom = 950.dp), pair = true, rtl = false))
    }

    @Test
    fun tabletopUsesOneUninterruptedVisiblePaneAndRespectsTheWindowOrigin() {
        val pane = WindowPane(18.dp, 22.dp, 768.dp, 622.dp)
        val fold = AppFold(0.dp, 246.dp, 800.dp, 250.dp, FoldAxis.Horizontal, FoldPosture.HalfOpened, true, true)
        val usable = browsePanes(pane, fold, pair = true, rtl = false)
        assertEquals(pane.copy(top = 258.dp), usable.main)
        assertNull(usable.detail)
        val typing = browsePanes(pane, fold, pair = true, rtl = false, imeTop = 362.dp)
        assertEquals(pane.copy(bottom = 238.dp), typing.main)
        assertNull(typing.detail)
        val splitTask = pane.copy(top = 260.dp)
        val outside = browsePanes(splitTask, fold, pair = false, rtl = false)
        assertEquals(splitTask, outside.main)
        assertEquals(false, outside.divided)
    }

    @Test
    fun smallBookSideNeverForcesAnUnusablePair() {
        val fold = AppFold(144.dp, 0.dp, 152.dp, 860.dp, FoldAxis.Vertical, FoldPosture.HalfOpened, true, true)
        val usable = browsePanes(expanded, fold, pair = true, rtl = false)
        assertEquals(160.dp, usable.main.left)
        assertNull(usable.detail)
    }

    @Test
    fun expressiveRailExpandsOnlyWhereItDoesNotTakeSpaceFromACompanion() {
        val large = expanded.copy(right = 1280.dp)
        val default = browsePanes(large, null, pair = false, rtl = false)
        assertTrue(default.canExpandRail)
        assertEquals(ExpandedBrowseRailWidth, default.railWidth)
        val collapsed = browsePanes(large, null, pair = false, rtl = false, expandRail = false)
        assertTrue(collapsed.canExpandRail)
        assertEquals(BrowseRailWidth, collapsed.railWidth)

        val paired = browsePanes(large, null, pair = true, rtl = false)
        assertEquals(false, paired.canExpandRail)
        assertEquals(BrowseRailWidth, paired.railWidth)
        assertEquals(24.dp, assertNotNull(paired.detail).left - paired.main.right)
    }

    @Test
    fun compactNavigationUsesPlatformAppropriateComponentAndGrowsWithText() {
        val compact = expanded.copy(right = 400.dp)
        assertEquals(BrowseNavigation.Bar, browsePanes(compact, null, pair = false, rtl = false).navigation)
        assertEquals(BrowseNavigation.Rail, browsePanes(compact, null, pair = false, rtl = false, preferRail = true).navigation)
        assertEquals(BrowseNavigation.Hidden, browsePanes(compact, null, pair = false, rtl = false, imeTop = 360.dp).navigation)
        assertEquals(BrowseBarHeight, browseBarHeight(1f))
        assertTrue(browseBarHeight(2f) > BrowseBarHeight)
    }

    @Test
    fun imeChangesHeightWithoutIntroducingOrRemovingASideRail() {
        val portrait = expanded.copy(right = 500.dp)
        val typing = browsePanes(portrait, null, pair = false, rtl = false, imeTop = 400.dp)
        assertEquals(BrowseNavigation.Bar, browsePanes(portrait, null, pair = false, rtl = false).navigation)
        assertEquals(BrowseNavigation.Hidden, typing.navigation)
        assertEquals(portrait.copy(bottom = 400.dp), typing.main)

        val landscape = portrait.copy(bottom = 420.dp)
        assertEquals(BrowseNavigation.Rail, browsePanes(landscape, null, pair = false, rtl = false).navigation)
        assertEquals(BrowseNavigation.Rail, browsePanes(landscape, null, pair = false, rtl = false, imeTop = 280.dp).navigation)
    }

    @Test
    fun onlyTheNearestCatalogueDestinationCanAccompanySelectedDetail() {
        val home = BackStackEntry(Screen.Home, 1)
        val shelf = BackStackEntry(Screen.Shelf(ShelfKind.FAVOURITES), 2)
        val one = BackStackEntry(Screen.Detail("one"), 3)
        val two = BackStackEntry(Screen.Detail("two"), 4)
        assertNull(browseSource(listOf(home, shelf)))
        assertEquals(shelf, browseSource(listOf(home, shelf, one, two)))
        assertNull(browseSource(listOf(home, one)))
        assertNull(browseSource(listOf(home, shelf, one, BackStackEntry(Screen.Player("one"), 5))))
    }
}
