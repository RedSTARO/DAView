package com.daview.server.db

import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「最近添加」 follows the scan: later stamps first, and among rows that share
 * a stamp the episode numbers decide, never the id. The library page's "added"
 * sort agrees with the shelf — a show that just gained an episode moves up.
 */
class LatestOrderTest {

    private val dir = createTempDirectory("daview-latest-order-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)

    @AfterTest
    fun tearDown() = database.close()

    private fun series(id: String, at: Long) = ItemRecord(
        dto = MediaItemDto(id = id, libraryId = "lib", kind = ItemKind.SERIES, name = id, sortName = id, path = "/Ani/$id"),
        dateCreated = at
    )

    private fun episode(id: String, show: String, number: Int, at: Long, season: Int = 1) = ItemRecord(
        dto = MediaItemDto(
            id = id, libraryId = "lib", kind = ItemKind.EPISODE, name = id, sortName = id,
            parentId = "$show-s$season", seriesId = show, indexNumber = number, parentIndexNumber = season,
            path = "/Ani/$show/S${season}E$number.mkv"
        ),
        dateCreated = at
    )

    private fun movie(id: String, at: Long) = ItemRecord(
        dto = MediaItemDto(id = id, libraryId = "lib", kind = ItemKind.MOVIE, name = id, sortName = id, path = "/Movie/$id.mkv"),
        dateCreated = at
    )

    @Test
    fun `later stamps first, the highest episode stands for its show`() {
        repository.upsertItems(
            listOf(
                series("aaa", 100),
                // Ids chosen so that hash-like ordering by id would put the
                // wrong one first: "aaa-e1" sorts before "aaa-e3".
                episode("aaa-e1", "aaa", 1, 100),
                episode("aaa-e3", "aaa", 3, 100),
                episode("aaa-e2", "aaa", 2, 100),
                series("bbb", 200),
                episode("bbb-e1", "bbb", 1, 200),
                episode("bbb-e2", "bbb", 2, 200),
                movie("film", 300)
            )
        )

        assertEquals(listOf("film", "bbb-e2", "aaa-e3"), repository.latest(null, 10).map { it.id })

        // A new episode under the oldest show is the newest thing there is.
        repository.upsertItems(listOf(episode("aaa-e4", "aaa", 4, 400)))
        assertEquals(listOf("aaa-e4", "film", "bbb-e2"), repository.latest(null, 10).map { it.id })

        // Specials do not outrank the run when they arrive together.
        repository.upsertItems(listOf(episode("bbb-sp", "bbb", 9, 200, season = 0)))
        assertEquals("bbb-e2", repository.latest(null, 10)[2].id)
    }

    @Test
    fun `the library page's added sort takes a show's newest episode`() {
        repository.upsertItems(
            listOf(
                series("aaa", 100), episode("aaa-e1", "aaa", 1, 100),
                series("bbb", 200), episode("bbb-e1", "bbb", 1, 200),
                movie("film", 300)
            )
        )
        val query = Repository.Query(libraryId = "lib", topLevelOnly = true, sort = "added", descending = true)
        assertEquals(listOf("film", "bbb", "aaa"), repository.query(query).first.map { it.id })

        repository.upsertItems(listOf(episode("aaa-e2", "aaa", 2, 400)))
        assertEquals(listOf("aaa", "film", "bbb"), repository.query(query).first.map { it.id })
        assertEquals(
            listOf("bbb", "film", "aaa"),
            repository.query(query.copy(descending = false)).first.map { it.id },
            "the other direction is the same order reversed"
        )
    }
}
