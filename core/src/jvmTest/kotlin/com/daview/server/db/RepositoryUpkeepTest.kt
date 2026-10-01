package com.daview.server.db

import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Three small things the catalogue got wrong on its own, with nothing failing:
 * a search that read its own punctuation as wildcards, a shelf whose daily
 * shuffle ran off the end of the id, and a cache that was never emptied.
 */
class RepositoryUpkeepTest {

    private val dir = createTempDirectory("daview-upkeep-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)

    @AfterTest
    fun tearDown() = database.close()

    /** An id shaped like the scanner's: the first twelve bytes of a SHA-1, in hex. */
    private fun idOf(name: String): String =
        MessageDigest.getInstance("SHA-1").digest(name.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    private fun movie(name: String) = ItemRecord(
        dto = MediaItemDto(
            id = idOf(name), libraryId = "lib", kind = ItemKind.MOVIE,
            name = name, sortName = name.lowercase(), path = "/Movie/$name.mkv"
        )
    )

    private fun search(term: String): List<String> =
        repository.query(Repository.Query(search = term, sort = "relevance")).first.map { it.name }

    @Test
    fun `a percent sign in a search is a character, not a wildcard`() {
        repository.upsertItems(listOf(movie("100% Orange Juice"), movie("1001 Nights"), movie("The 100")))

        assertEquals(listOf("100% Orange Juice"), search("100%"))
        // The plain digits still find all three.
        assertEquals(3, search("100").size)
    }

    @Test
    fun `an underscore in a search is a character, not a wildcard`() {
        repository.upsertItems(listOf(movie("Re_Zero"), movie("Re Zero"), movie("RedZero")))

        assertEquals(listOf("Re_Zero"), search("Re_Z"))
    }

    @Test
    fun `the unwatched shelf is shuffled on every day, not only on some`() {
        val names = listOf("Akira", "Brazil", "Casablanca", "Dune", "Eraserhead", "Fargo", "Gattaca", "Heat")
        repository.upsertItems(names.map(::movie))
        val alphabetical = names.map(::idOf)

        // Two full turns and a bit. The order used to be alphabetical on every
        // day whose slice began past the id's twenty-fourth character.
        for (day in 0L until 40L) {
            val shelf = repository.unwatched(libraryId = null, limit = 24, day = day).map { it.id }
            val start = (day % 17).toInt()
            val expected = alphabetical.sortedBy { it.substring(start, start + 8) }
            assertEquals(expected, shelf, "day $day")
            assertNotEquals(alphabetical, shelf, "day $day is in alphabetical order")
        }
    }

    @Test
    fun `cached provider responses older than a month are dropped when a scan settles`() {
        repository.cachePut("tmdb:search:old", "{}")
        repository.cachePut("tmdb:search:new", "{}")
        // Nothing exposes the timestamp, so the old one is aged by pruning with
        // a window it falls outside of, then the default window is checked to
        // leave a fresh row alone.
        Thread.sleep(300)
        repository.cachePut("tmdb:search:new", "{}")
        repository.pruneScrapeCache(olderThanMs = 150)

        assertNull(repository.cacheGet("tmdb:search:old", Long.MAX_VALUE))
        assertNotNull(repository.cacheGet("tmdb:search:new", Long.MAX_VALUE))

        repository.refreshStatistics()
        assertNotNull(repository.cacheGet("tmdb:search:new", Long.MAX_VALUE))
    }
}
