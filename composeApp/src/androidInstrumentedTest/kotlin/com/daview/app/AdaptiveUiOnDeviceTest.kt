package com.daview.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daview.app.AdaptiveCatalogueFixture.FILMS
import com.daview.app.AdaptiveCatalogueFixture.FILM_TITLE
import com.daview.app.AdaptiveCatalogueFixture.LAST_FILM_TITLE
import com.daview.app.AdaptiveCatalogueFixture.SEARCH_GENRE
import com.daview.app.AdaptiveCatalogueFixture.SERIES_TITLE
import com.daview.app.AdaptiveCatalogueFixture.SHOWS
import org.junit.Test
import org.junit.runner.RunWith

/** Baseline product checks on real Android semantics, with independently selectable window scenarios. */
@RunWith(AndroidJUnit4::class)
class AdaptiveUiOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Retains the offline fixture on the disposable debug app for physical-device checks. */
    @Test
    fun seedCatalogueAndOpenApp() = scenario("seed") {
        ActivityScenario.launch(MainActivity::class.java).use {
            AdaptiveDeviceSemantics().awaitAction("populated Home with fixture film") {
                it.hasLabel(FILM_TITLE) || it.hasLabel(AdaptiveCatalogueFixture.PLAYABLE_FILM_TITLE)
            }
        }
    }

    @Test fun smallBrowseDetail() = browseDetail(AdaptiveDeviceWindow.SMALL)
    @Test fun largePortraitBrowseDetail() = browseDetail(AdaptiveDeviceWindow.LARGE_PORTRAIT)
    @Test fun largeLandscapeBrowseDetail() = browseDetail(AdaptiveDeviceWindow.LARGE_LANDSCAPE)
    @Test fun bookBrowseDetail() = browseDetail(AdaptiveDeviceWindow.BOOK)
    @Test fun tabletopBrowseDetail() = browseDetail(AdaptiveDeviceWindow.TABLETOP)
    @Test fun splitBrowseDetail() = browseDetail(AdaptiveDeviceWindow.SPLIT)

    /** Preserve an actual search query and its result, without using settings ACTION_SET_TEXT or an IME. */
    @Test
    fun searchQuerySurvivesVerticalFoldAndUnfold() = onDevice("search-continuity", AdaptiveDeviceWindow.LARGE_LANDSCAPE) {
        openLibrary(FILMS, FILM_TITLE)
        openItem(FILM_TITLE)
        // The fixture's unique genre is itself an accessible "search for this" action. No test-only
        // callback sets the query, and results don't depend on IME/clipboard/device keyboard state.
        ui.reveal("genre link $SEARCH_GENRE") { it.hasLabel(SEARCH_GENRE) && it.clickableAncestor() != null }
        clickUsable(SEARCH_GENRE)
        fun horizontalEdgesInDp(): Pair<Float, Float> {
            val editor = assertSearchResult().screenBounds()
            val windowLeft = frame.shell.left
            return (editor.left - windowLeft) / frame.density to (editor.right - windowLeft) / frame.density
        }
        val tall = horizontalEdgesInDp()
        resize(AdaptiveDeviceWindow.SHORT_LANDSCAPE)
        val short = horizontalEdgesInDp()
        // A fitted harness can use a different pixel density after a height change.
        check(kotlin.math.abs(tall.first - short.first) < 2f && kotlin.math.abs(tall.second - short.second) < 2f) {
            "Search changed its horizontal bounds when only the height changed: $tall → $short dp"
        }
        resize(AdaptiveDeviceWindow.BOOK)
        assertSearchResult()
        resize(AdaptiveDeviceWindow.LARGE_LANDSCAPE)
        assertSearchResult()
    }

    @Test
    fun lastTitleAndBackStackSurviveWindowChanges() = onDevice("browse-continuity", AdaptiveDeviceWindow.LARGE_LANDSCAPE) {
        openLibrary(FILMS, FILM_TITLE)
        check(ui.findAction { it.hasLabel(LAST_FILM_TITLE) } == null) { "Fixture is already at the catalogue end" }
        clickUsable("按首字母跳转")
        ui.click("E") // Moves the real lazy grid to the last film by stable sort name.
        assertUsable(LAST_FILM_TITLE, ui.awaitAction(LAST_FILM_TITLE))
        openItem(LAST_FILM_TITLE)
        for (next in listOf(
            AdaptiveDeviceWindow.SMALL, AdaptiveDeviceWindow.LARGE_PORTRAIT, AdaptiveDeviceWindow.BOOK,
            AdaptiveDeviceWindow.TABLETOP, AdaptiveDeviceWindow.SPLIT, AdaptiveDeviceWindow.LARGE_LANDSCAPE
        )) {
            resize(next)
            assertDetail(LAST_FILM_TITLE)
        }
        clickUsable("返回")
        awaitLibraryHeader(FILMS)
        // No scrolling or re-opening of the destination: back returns to the list at the same title.
        assertUsable(LAST_FILM_TITLE, ui.awaitAction(LAST_FILM_TITLE))
    }

    @Test
    fun settingsRemainReachableWithoutEditing() = onDevice("settings-read-only", AdaptiveDeviceWindow.SMALL) {
        clickUsable("设置")
        for (next in listOf(AdaptiveDeviceWindow.SMALL, AdaptiveDeviceWindow.BOOK, AdaptiveDeviceWindow.TABLETOP)) {
            if (next != frame.profile) resize(next)
            assertUsable("WebDAV 存储", ui.awaitVisible("WebDAV 存储"))
            val address = ui.awaitVisible("non-password WebDAV editor") { it.isEditable && !it.isPassword }
            assertUsable("WebDAV editor", address)
            // Android exposes the current Material tab as selected, not clickable.
            val storage = ui.awaitVisible("selected storage category") { node ->
                node.hasLabel("存储与刮削") && generateSequence(node) { it.parent }.any { it.isSelected }
            }
            assertUsable("存储与刮削", storage)
            assertUsable("播放 category action", ui.awaitAction("播放"))
        }
    }

    @Test
    fun selectedSeasonAndEpisodeAnchorSurviveResize() = onDevice("detail-continuity", AdaptiveDeviceWindow.SMALL) {
        openLibrary(SHOWS, SERIES_TITLE)
        openItem(SERIES_TITLE)
        ui.reveal("second-season control") { it.hasLabel("Demo Season 2") && it.clickableAncestor() != null }
        clickUsable("Demo Season 2")
        val end = "播放 S02E18"
        fun episodeNumber(node: android.view.accessibility.AccessibilityNodeInfo): Int? =
            node.contentDescription?.toString()?.removePrefix("播放 S02E")?.toIntOrNull()
                ?: node.text?.toString()?.removePrefix("播放 S02E")?.toIntOrNull()
        assertUsable(end, ui.reveal("late Season 2 episode", maxScrolls = 16) { episodeNumber(it) == 18 })
        val top = ui.visibleNodes().mapNotNull(::episodeNumber).minOrNull() ?: error("No visible Season 2 episodes")
        check(top >= 10) { "The detail did not reach the last part of Season 2" }
        for (next in listOf(
            AdaptiveDeviceWindow.LARGE_PORTRAIT, AdaptiveDeviceWindow.LARGE_LANDSCAPE, AdaptiveDeviceWindow.BOOK,
            AdaptiveDeviceWindow.TABLETOP, AdaptiveDeviceWindow.SPLIT, AdaptiveDeviceWindow.SMALL
        )) {
            resize(next)
            // A shorter viewport need not keep a row that used to sit at the bottom. It must
            // retain the same reading neighborhood, not jump to the hero or the start of a season.
            val entry = ui.awaitVisible("Season 2 near episode $top without scrolling in $next") {
                episodeNumber(it)?.let { n -> n in (top - 2)..(top + 2) } == true
            }
            assertUsable("late Season 2 episode in $next", entry)
        }
    }

    @Test
    fun standaloneSeriesPairsOnlyWhenUseful() = onDevice("standalone-detail", AdaptiveDeviceWindow.ROOMY_DETAIL) {
        fun summaryPane() = ui.findVisible { android.os.Build.VERSION.SDK_INT >= 28 && it.paneTitle?.toString() == "媒体简介" }
        openItem(FILM_TITLE)
        check(summaryPane() == null) { "A standalone movie does not need a second column" }
        clickUsable("返回")
        // Next-up episodes also show the series name. The series tile's secondary line
        // is its year and total episodes, not a single episode's name.
        val seriesTile: DeviceNodeSelector = { node ->
            node.hasLabel(SERIES_TITLE) && generateSequence(node) { it.parent }.take(4).any { parent ->
                parent.text?.contains("36 集") == true || (0 until parent.childCount).any { index ->
                    parent.getChild(index)?.text?.contains("36 集") == true
                }
            }
        }
        ui.reveal("actual series tile on Home", matcher = seriesTile)
        val openSeries = ui.awaitAction("series rather than next-up episode", seriesTile)
        assertUsable("series tile", openSeries)
        ui.click(openSeries, SERIES_TITLE)
        assertDetail(SERIES_TITLE)
        val pane = ui.await("episode-rich series has a real second pane") { summaryPane() }
        assertUsable("series summary pane", pane)
        resize(AdaptiveDeviceWindow.SMALL)
        assertDetail(SERIES_TITLE)
        check(summaryPane() == null) { "The series split should collapse in a narrow window" }
        resize(AdaptiveDeviceWindow.ROOMY_DETAIL)
        assertUsable("restored series summary pane", ui.await("series split restored") { summaryPane() })
    }

    private fun browseDetail(profile: AdaptiveDeviceWindow) = onDevice(profile.name.lowercase(), profile) {
        openLibrary(FILMS, FILM_TITLE)
        openItem(FILM_TITLE)
        assertUsable("return from detail", ui.awaitAction("返回"))
        assertUsable("resume film", ui.awaitVisible("continue film") { node ->
            node.text?.startsWith("继续 ") == true && node.clickableAncestor() != null
        })
        // A source pane may or may not share this window with Detail. Either way, Back must
        // return to the real source and make its title and selection action accessible again.
        clickUsable("返回")
        awaitLibraryHeader(FILMS)
        assertUsable("$FILM_TITLE in source after Back", ui.awaitAction(FILM_TITLE))
    }

    private fun AdaptiveDeviceHarness.assertSearchResult(): android.view.accessibility.AccessibilityNodeInfo {
        val editor = ui.awaitVisible("unchanged query '$SEARCH_GENRE'") {
            it.isEditable && !it.isPassword && it.text?.toString() == SEARCH_GENRE
        }
        assertUsable("search editor", editor)
        assertUsable("result $FILM_TITLE", ui.awaitAction(FILM_TITLE))
        return editor
    }

    private fun AdaptiveDeviceHarness.openLibrary(library: String, firstTitle: String) {
        // On a short Home only the hero may be visible. Its identity legitimately changes
        // when an earlier playback test leaves the local video in Continue Watching.
        ui.awaitAction("populated Home with fixture film") {
            it.hasLabel(FILM_TITLE) || it.hasLabel(AdaptiveCatalogueFixture.PLAYABLE_FILM_TITLE)
        }
        val direct = ui.await("library shortcut '$library' or bottom library destination") {
            ui.findAction { it.hasLabel(library) } ?: ui.findAction { it.hasLabel("媒体库") }
        }
        assertUsable("library navigation", direct)
        val needsMenu = !direct.hasLabel(library) && ui.findAction { it.hasLabel(library) } == null
        ui.click(direct, "navigate to $library")
        if (needsMenu) clickUsable(library)
        awaitLibraryHeader(library)
        assertUsable("first catalogue title $firstTitle", ui.awaitAction(firstTitle))
    }

    private fun AdaptiveDeviceHarness.awaitLibraryHeader(library: String) {
        val header = ui.awaitVisible("non-navigation library heading '$library'") {
            it.hasLabel(library) && it.clickableAncestor() == null
        }
        assertUsable("library heading $library", header)
    }

    private fun AdaptiveDeviceHarness.openItem(title: String) {
        clickUsable(title)
        assertDetail(title)
    }

    private fun AdaptiveDeviceHarness.assertDetail(title: String) {
        assertUsable("detail heading $title", ui.awaitVisible("detail heading '$title'") { it.isDetailHeading(title) })
    }

    private fun AdaptiveDeviceHarness.clickUsable(label: String) {
        val action = ui.awaitAction(label)
        assertUsable(label, action)
        ui.click(action, label)
    }

    private fun onDevice(id: String, profile: AdaptiveDeviceWindow, check: AdaptiveDeviceHarness.() -> Unit) =
        scenario(id) { AdaptiveDeviceHarness.run(profile, it, check) }

    private fun scenario(id: String, block: (AdaptiveMatrixReceipt) -> Unit) {
        val receipt = AdaptiveMatrixReceipt(instrumentation, id)
        receipt.emit("BEGIN")
        try {
            AdaptiveCatalogueFixture.seed(instrumentation.targetContext, instrumentation.context)
            block(receipt)
            receipt.emit("PASS")
        } catch (failure: Throwable) {
            receipt.emit("FAIL", (failure.message ?: failure.javaClass.simpleName).lineSequence().firstOrNull().orEmpty())
            throw failure
        }
    }
}
