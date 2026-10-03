package com.daview.app.data

import com.daview.shared.model.ItemKind
import com.daview.shared.model.ItemPage
import com.daview.shared.model.MediaItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStateRaceTest {
    @Test
    fun `older detail finishing last cannot replace the current detail`() = StateFixture().use { f ->
        val old = PendingRead<MediaItemDto>()
        f.catalog.readItem = { if (it == "old") old.await() else media(it) }
        f.state.switchTo(Screen.Search)
        f.state.navigate(Screen.Detail("old"))
        f.drain()
        f.state.back()
        f.state.navigate(Screen.Detail("new"))
        f.drain()
        old.complete(media("old"))
        f.drain()
        assertEquals("new", f.state.detailItem?.id)
        assertEquals(Screen.Detail("new"), f.state.current)
        assertFalse(f.state.detailLoading)
        assertNull(f.state.detailError)
    }

    @Test
    fun `obsolete detail failure and finally do not change newer loading state`() = StateFixture().use { f ->
        val old = PendingRead<MediaItemDto>()
        val new = PendingRead<MediaItemDto>()
        f.catalog.readItem = { if (it == "old") old.await() else new.await() }
        f.state.loadDetail("old")
        f.drain()
        f.state.loadDetail("new")
        f.drain()
        old.fail(IllegalStateException("late failure"))
        f.drain()
        assertTrue(f.state.detailLoading)
        assertNull(f.state.detailError)
        new.complete(media("new"))
        f.drain()
        assertEquals("new", f.state.detailItem?.id)
    }

    @Test
    fun `next episode cancellation is not converted into a successful detail`() = StateFixture().use { f ->
        f.catalog.readItem = { media(it, ItemKind.SERIES) }
        f.catalog.readNext = { throw CancellationException("cancel next episode") }
        val job = f.state.loadDetail("series")
        f.drain()
        assertTrue(job.isCancelled)
        assertNull(f.state.detailItem)
        assertNull(f.state.detailError)
        assertFalse(f.state.detailLoading)
    }

    @Test
    fun `season selection cannot be overwritten by an older season response`() = StateFixture().use { f ->
        val old = PendingRead<List<MediaItemDto>>()
        f.state.loadDetail("series")
        f.drain()
        f.catalog.readChildren = { if (it == "one") old.await() else listOf(media("two-episode")) }
        f.state.selectSeason("one")
        f.drain()
        f.state.selectSeason("two")
        f.drain()
        old.complete(listOf(media("one-episode")))
        f.drain()
        assertEquals("two", f.state.detailSeasonId)
        assertEquals(listOf("two-episode"), f.state.detailEpisodes.map { it.id })
    }

    @Test
    fun `filter generation rejects an older append even for the same library`() = StateFixture().use { f ->
        val oldPage = PendingRead<ItemPage>()
        val initial = List(200) { media("first-$it") }
        f.catalog.readPage = { query, offset, _ ->
            when {
                query.view.onlyFavourite -> ItemPage(listOf(media("favourite")), 1, 0)
                offset == 0 -> ItemPage(initial, 205, 0)
                else -> oldPage.await()
            }
        }
        f.state.loadLibrary("library")
        f.drain()
        f.state.loadMoreLibrary("library")
        f.drain()
        assertTrue(oldPage.waiting)
        f.state.updateView("library") { it.copy(onlyFavourite = true) }
        f.drain()
        oldPage.complete(ItemPage(listOf(media("not-favourite")), 205, 200))
        f.drain()
        assertEquals(listOf("favourite"), f.state.libraryItems.map { it.id })
        assertEquals(1, f.state.libraryTotal)
        assertFalse(f.state.libraryLoadingMore)
    }

    @Test
    fun `old append finally cannot clear the new generation append indicator`() = StateFixture().use { f ->
        val old = PendingRead<ItemPage>()
        val new = PendingRead<ItemPage>()
        val before = List(200) { media("old-$it") }
        val after = List(200) { media("new-$it") }
        f.catalog.readPage = { query, offset, _ ->
            if (offset == 0) ItemPage(if (query.view.onlyFavourite) after else before, 210, 0)
            else if (query.view.onlyFavourite) new.await() else old.await()
        }
        f.state.loadLibrary("library")
        f.drain()
        f.state.loadMoreLibrary("library")
        f.drain()
        f.state.updateView("library") { it.copy(onlyFavourite = true) }
        f.drain()
        f.state.loadMoreLibrary("library")
        f.drain()
        old.fail(IllegalStateException("old append failed"))
        f.drain()
        assertTrue(f.state.libraryLoadingMore)
        assertNull(f.state.toast)
        new.complete(ItemPage(listOf(media("new-page")), 210, 200))
        f.drain()
        assertFalse(f.state.libraryLoadingMore)
        assertEquals(after.map { it.id } + "new-page", f.state.libraryItems.map { it.id })
    }

    @Test
    fun `letter jump and automatic pagination serialize offsets without duplicate rows`() = StateFixture().use { f ->
        val second = PendingRead<ItemPage>()
        val offsets = mutableListOf<Int>()
        val initial = List(200) { media("first-$it") }
        f.catalog.readPage = { _, offset, _ ->
            offsets += offset
            when (offset) {
                0 -> ItemPage(initial, 202, 0)
                200 -> second.await()
                else -> ItemPage(listOf(media("third")), 202, offset)
            }
        }
        f.state.loadLibrary("library")
        f.drain()
        f.state.loadMoreLibrary("library")
        f.drain()
        f.scope.launch { f.state.ensureLibraryLoaded("library", 201) }
        f.drain()
        assertEquals(listOf(0, 200), offsets)
        second.complete(ItemPage(listOf(media("second")), 202, 200))
        f.drain()
        assertEquals(listOf(0, 200, 201), offsets)
        assertEquals(initial.map { it.id } + listOf("second", "third"), f.state.libraryItems.map { it.id })
    }

    @Test
    fun `letter jump already in flight cannot append after a filter change`() = StateFixture().use { f ->
        val pending = PendingRead<ItemPage>()
        val initial = List(200) { media("first-$it") }
        f.catalog.readPage = { query, offset, _ ->
            if (query.view.onlyFavourite) ItemPage(listOf(media("favourite")), 1, 0)
            else if (offset == 0) ItemPage(initial, 202, 0)
            else pending.await()
        }
        f.state.loadLibrary("library")
        f.drain()
        f.scope.launch { f.state.ensureLibraryLoaded("library", 201) }
        f.drain()
        f.state.updateView("library") { it.copy(onlyFavourite = true) }
        f.drain()
        pending.complete(ItemPage(listOf(media("second"), media("third")), 202, 200))
        f.drain()
        assertEquals(listOf("favourite"), f.state.libraryItems.map { it.id })
        assertEquals(1, f.state.libraryTotal)
    }
}
