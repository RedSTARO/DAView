package com.daview.app.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailLayoutTest {
    private fun split(
        width: Int,
        height: Int,
        scale: Float = 1f,
        companion: Boolean = false,
        episodes: Int = 18,
        seasonChoices: Boolean = false,
        related: Int = 0
    ) = detailUsesTwoPanes(width.dp, height.dp, scale, companion, episodes, seasonChoices, related)

    @Test
    fun `internal split uses the safe pane after Back not the display dimensions`() {
        assertFalse(split(270, 740)) // book half
        assertFalse(split(1200, 360)) // tabletop half
        assertFalse(split(879, 600))
        assertFalse(split(880, 499))
        assertTrue(split(880, 500))
        assertFalse(split(1200, 900, companion = true)) // never three squeezed columns
    }

    @Test
    fun `large text needs more width and height even on wide displays`() {
        assertFalse(split(989, 540, scale = 1.5f))
        assertFalse(split(990, 539, scale = 1.5f))
        assertTrue(split(990, 540, scale = 1.5f))
        assertFalse(split(1100, 579, scale = 2f))
        assertTrue(split(1100, 580, scale = 2f))
        assertFalse(split(2000, 900, scale = 3.1f)) // the two reading columns themselves are capped
    }

    @Test
    fun `only meaningful episode or related navigation warrants a second list`() {
        assertFalse(split(1200, 900, episodes = 0)) // one movie, files only, or an empty season
        assertFalse(split(1200, 900, episodes = 1, related = 1))
        assertTrue(split(1200, 900, episodes = 2))
        assertTrue(split(1200, 900, episodes = 0, seasonChoices = true)) // multi-season read in flight
        assertTrue(split(1200, 900, episodes = 0, related = 2))
    }

    @Test
    fun `row keys drive both layouts and resolve a reordered episode without using its old index`() {
        val rows = detailRows(true, listOf("e2", "e1"), related = true, people = true)
        assertEquals(listOf("hero", "people"), rows.summary)
        assertEquals(listOf("seasons", "episode:e2", "episode:e1", "related", "files"), rows.contents)
        assertEquals(listOf("hero", "seasons", "episode:e2", "episode:e1", "related", "people", "files"), rows.stacked)
        val bookmark = DetailBookmark().record(DetailAnchor("episode:e1", 27))
        assertEquals(3 to 27, resolveDetailAnchor(rows.stacked, bookmark.primaryAnchor(split = false, currentEpisode = null)))
        assertEquals(2 to 27, resolveDetailAnchor(rows.contents, bookmark.primaryAnchor(split = true, currentEpisode = null)))
    }

    @Test
    fun `empty or failed season reads leave a missing key available for restoration`() {
        val saved = DetailBookmark().record(DetailAnchor("episode:e8", 35))
        val absent = detailRows(true, emptyList(), related = false, people = true).stacked
        assertEquals(1 to 0, resolveDetailAnchor(absent, saved.primaryAnchor(split = false, currentEpisode = null)))
        val loaded = detailRows(true, listOf("e8", "e9"), related = false, people = true).stacked
        assertEquals(2 to 35, resolveDetailAnchor(loaded, saved.primaryAnchor(split = false, currentEpisode = null)))
    }

    @Test
    fun `switching seasons resets contents but not a saved summary or reading focus`() {
        val saved = DetailBookmark().record(DetailAnchor("episode:e8", 35)).record(DetailAnchor("people", 5))
        val split = saved.changeSeason(split = true)
        val stacked = saved.changeSeason(split = false)
        assertEquals(DetailAnchor("people", 5), split.summary)
        assertEquals(DetailAnchor("seasons"), split.primaryAnchor(split = false, currentEpisode = null))
        assertEquals(DetailAnchor("people", 5), stacked.primaryAnchor(split = false, currentEpisode = null))
        assertEquals(DetailAnchor("seasons"), stacked.primaryAnchor(split = true, currentEpisode = null))
    }

    @Test
    fun `episode shelf and split rows translate the focused sibling without swapping axis offsets`() {
        val current = "e9"
        val episodes = listOf("e8", current, "e10")
        val shelf = detailRows(true, episodes, related = false, people = false, shelf = true)
        assertEquals(listOf("hero", "seasons", "files"), shelf.stacked)
        assertEquals(DetailAnchor(current), DetailBookmark().siblingAnchor(current))
        assertEquals(DetailAnchor("episode:e9"), DetailBookmark().primaryAnchor(split = true, currentEpisode = current))

        val fromShelf = DetailBookmark().recordSibling(DetailAnchor("e8", 40))
        assertEquals(DetailAnchor("episode:e8"), fromShelf.primaryAnchor(split = true, currentEpisode = current))
        val fromSplit = fromShelf.record(DetailAnchor("episode:e10", 33))
        assertEquals(1 to 0, resolveDetailAnchor(shelf.stacked, fromSplit.primaryAnchor(split = false, currentEpisode = current)))
        assertEquals(DetailAnchor("e10"), fromSplit.siblingAnchor(current))
    }
}
