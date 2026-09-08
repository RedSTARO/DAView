package com.daview.server.db

import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A real library holds more than one row for the same season and episode — a
 * folder that got scanned twice, or a season that exists under two names. The
 * reference share has 32 such pairs.
 *
 * "Next up" asks which episode has nothing unwatched ahead of it, and for a
 * pair like that the answer is both: neither sits earlier than the other. That
 * falls out of comparing strictly earlier, and it has to keep falling out of
 * whatever computes the same answer faster — picking one row would quietly
 * drop entries from the home screen, and nothing in [Repository.nextUp] would
 * look wrong.
 */
class NextUpDuplicatesTest {

    private val dir = createTempDirectory("daview-nextup-dup-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)

    @AfterTest
    fun tearDown() = database.close()

    private fun episode(id: String, season: Int, number: Int, sortName: String) = MediaItemDto(
        id = id,
        libraryId = "lib",
        kind = ItemKind.EPISODE,
        name = id,
        parentId = "show",
        seriesId = "show",
        indexNumber = number,
        parentIndexNumber = season,
        sortName = sortName,
        path = "/Ani/Show/$sortName.mkv"
    )

    @Test
    fun `two rows for the same episode both come back`() {
        repository.upsertItems(
            listOf(
                MediaItemDto(id = "show", libraryId = "lib", kind = ItemKind.SERIES, name = "Show"),
                episode("e1-a", season = 1, number = 1, sortName = "0001a"),
                episode("e1-b", season = 1, number = 1, sortName = "0001b"),
                episode("e2", season = 1, number = 2, sortName = "0002")
            ).map { ItemRecord(dto = it) }
        )
        // Watching the later episode is what makes the series a started one;
        // the pair in front of it is then what next up has to answer with.
        repository.setPlayed("e2", true)

        assertEquals(listOf("e1-a", "e1-b"), repository.nextUp(10).map { it.id })
    }

    /** The pair only qualifies while nothing earlier is left unwatched. */
    @Test
    fun `a duplicated episode drops out once it is watched`() {
        repository.upsertItems(
            listOf(
                MediaItemDto(id = "show", libraryId = "lib", kind = ItemKind.SERIES, name = "Show"),
                episode("e1-a", season = 1, number = 1, sortName = "0001a"),
                episode("e1-b", season = 1, number = 1, sortName = "0001b"),
                episode("e2", season = 1, number = 2, sortName = "0002")
            ).map { ItemRecord(dto = it) }
        )
        repository.setPlayed("e1-a", true)
        repository.setPlayed("e1-b", true)

        assertEquals(listOf("e2"), repository.nextUp(10).map { it.id })
    }
}
