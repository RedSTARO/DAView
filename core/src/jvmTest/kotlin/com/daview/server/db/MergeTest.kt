package com.daview.server.db

import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Merging has to survive the thing that would most obviously undo it — a
 * rescan, which rebuilds every parent link from the folder tree — and it has to
 * come apart again cleanly, including for the duplicate shape that actually
 * turned up in the reference share: two folders differing only in case.
 */
class MergeTest {

    private val dir = createTempDirectory("daview-merge-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)

    @AfterTest
    fun tearDown() = database.close()

    private fun series(id: String, path: String) = MediaItemDto(
        id = id, libraryId = "lib", kind = ItemKind.SERIES, name = id, path = path
    )

    private fun episode(id: String, seriesId: String, path: String) = MediaItemDto(
        id = id, libraryId = "lib", kind = ItemKind.EPISODE, name = id,
        parentId = seriesId, seriesId = seriesId, path = path, indexNumber = 1
    )

    private fun store(vararg items: MediaItemDto) =
        repository.upsertItems(items.map { ItemRecord(dto = it) })

    /** What the scanner does on every run: parentage straight from the folders. */
    private fun rescan() = repository.upsertScannedItems(
        listOf(
            ItemRecord(dto = series("keep", "/Ani/SHOW (2015)")),
            ItemRecord(dto = series("dupe", "/Ani/Show (2015)")),
            ItemRecord(dto = episode("keep-e1", "keep", "/Ani/SHOW (2015)/S01E01.mkv")),
            ItemRecord(dto = episode("dupe-e1", "dupe", "/Ani/Show (2015)/S01E01.mkv"))
        )
    )

    @Test
    fun `a merge survives a rescan`() {
        rescan()
        repository.mergeItems("keep", listOf("dupe"))
        assertEquals(2, repository.children("keep").size)
        assertEquals("keep", repository.item("dupe")?.mergedInto)

        // The scanner rebuilds parentage from the folder tree, which puts the
        // duplicate's episode back under it.
        rescan()
        assertEquals(1, repository.children("keep").size, "the scan splits them again")

        repository.reapplyMerges()
        assertEquals(2, repository.children("keep").size, "and the merge is laid back on top")
        assertEquals("keep", repository.item("dupe")?.mergedInto)
    }

    @Test
    fun `unmerging returns only the duplicate's own children`() {
        rescan()
        repository.mergeItems("keep", listOf("dupe"))

        repository.unmergeItem("dupe")

        // The two folders differ only in case. SQLite's LIKE ignores ASCII case,
        // so matching paths with it would hand the target's episode back as well.
        assertEquals(listOf("keep-e1"), repository.children("keep").map { it.id })
        assertEquals(listOf("dupe-e1"), repository.children("dupe").map { it.id })
        assertNull(repository.item("dupe")?.mergedInto)
    }

    @Test
    fun `a merged duplicate is hidden from listings`() {
        rescan()
        repository.mergeItems("keep", listOf("dupe"))

        val (items, total) = repository.query(Repository.Query(libraryId = "lib", kind = ItemKind.SERIES))
        assertEquals(listOf("keep"), items.map { it.id })
        assertEquals(1, total)
    }
}
