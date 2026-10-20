package com.daview.server.db

import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.ManualField
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.UserDataDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchStateWriteTest {

    private val dir = createTempDirectory("daview-watch-state-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)

    @AfterTest
    fun tearDown() {
        database.close()
        dir.toFile().deleteRecursively()
    }

    private fun store() {
        repository.upsertLibrary(LibraryDto(id = "lib", name = "Films", kind = LibraryKind.MOVIE, path = "/films"))
        repository.upsertItems(listOf(ItemRecord(MediaItemDto(
            id = "film", libraryId = "lib", kind = ItemKind.MOVIE, name = "Film",
            path = "/films/film.mkv", runtimeMs = 100_000
        ))))
    }

    @Test
    fun `hiding from continue watching leaves the row's sync timestamp and the revision alone`() {
        store()
        repository.restoreUserData("film", UserDataDto(positionMs = 30_000), updatedAt = 100)
        val revision = repository.syncFingerprint()

        repository.setHiddenFromResume("film", true)

        assertTrue(repository.resume(10).isEmpty())
        assertEquals(100L, repository.userDataUpdatedAt("film"))
        assertEquals(revision, repository.syncFingerprint())
    }

    @Test
    fun `playing a hidden entry again puts it back on continue watching`() {
        store()
        repository.restoreUserData("film", UserDataDto(positionMs = 30_000), updatedAt = 100)
        repository.setHiddenFromResume("film", true)

        repository.saveProgress("film", positionMs = 40_000, runtimeMs = 100_000, null, null)

        assertEquals(listOf("film"), repository.resume(10).map { it.id })
    }

    @Test
    fun `an emptied year clears it and stays a hand edit`() {
        store()
        repository.updateItemFields("film", name = null, originalName = null, overview = null, year = 2013, genres = null)
        assertEquals(2013, repository.item("film")?.year)

        repository.updateItemFields("film", name = null, originalName = null, overview = null, year = 0, genres = null)

        val item = assertNotNull(repository.item("film"))
        assertNull(item.year)
        assertTrue(ManualField.YEAR in item.manualFields)
    }

    @Test
    fun `the play count comes from the row the write replaces`() {
        store()
        // Merged from another device: watched twice there, part way into a third.
        repository.restoreUserData("film", UserDataDto(positionMs = 10_000, playCount = 2), updatedAt = 100)

        repository.saveProgress("film", positionMs = 95_000, runtimeMs = 100_000, null, null)
        assertEquals(3, repository.userData("film").playCount)

        // Still at the end: the same viewing does not count twice.
        repository.saveProgress("film", positionMs = 96_000, runtimeMs = 100_000, null, null)
        assertEquals(3, repository.userData("film").playCount)
    }
}
