package com.daview.server.db

import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Season 00` is the specials folder, and it is the one season whose number
 * lies about where it belongs: it is the smallest integer and the last thing
 * anyone wants played. Ordering by the season number alone made pressing play
 * on a series start its SP — which is what the reference share looks like:
 *
 * ```
 * /Ani/Beyond the Boundary (2013)/Season 00/… - S00E07.mkv
 *                                /Season 01/… - S01E01.mkv
 * ```
 */
class SpecialsOrderTest {

    private val dir = createTempDirectory("daview-specials-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)

    @AfterTest
    fun tearDown() = database.close()

    private fun season(id: String, seriesId: String, number: Int) = MediaItemDto(
        id = id, libraryId = "lib", kind = ItemKind.SEASON, name = "s$number",
        parentId = seriesId, seriesId = seriesId, indexNumber = number
    )

    private fun episode(id: String, seasonId: String, season: Int, episode: Int) = MediaItemDto(
        id = id, libraryId = "lib", kind = ItemKind.EPISODE, name = id,
        parentId = seasonId, seriesId = "show", indexNumber = episode,
        parentIndexNumber = season, sortName = "%04d%04d".format(season, episode),
        path = "/Ani/Show (2013)/Season %02d/S%02dE%02d.mkv".format(season, season, episode)
    )

    /** A series with specials in front of it, the shape the bug needs. */
    private fun store() = repository.upsertItems(
        listOf(
            MediaItemDto(id = "show", libraryId = "lib", kind = ItemKind.SERIES, name = "Show"),
            season("sp", "show", 0),
            season("s1", "show", 1),
            episode("sp-e1", "sp", 0, 1),
            episode("sp-e7", "sp", 0, 7),
            episode("s1-e1", "s1", 1, 1),
            episode("s1-e2", "s1", 1, 2)
        ).map { ItemRecord(dto = it) }
    )

    @Test
    fun `play on a series starts the first regular episode, not the SP`() {
        store()
        assertEquals("s1-e1", repository.nextEpisodeUnder("show")?.id)
    }

    /**
     * The failure the user hit: a few seconds land on a special — which is
     * enough to make it the part-watched one — and every later press of play
     * comes back to it instead of the season being worked through.
     */
    @Test
    fun `a part-watched special does not outrank the regular run`() {
        store()
        repository.saveProgress("sp-e7", positionMs = 5_000L, runtimeMs = 348_000L, null, null)
        assertEquals("s1-e1", repository.nextEpisodeUnder("show")?.id)

        // A part-watched *regular* episode still wins, which is the whole point
        // of the watch-state ranking.
        repository.saveProgress("s1-e2", positionMs = 60_000L, runtimeMs = 1_400_000L, null, null)
        assertEquals("s1-e2", repository.nextEpisodeUnder("show")?.id)
    }

    @Test
    fun `next up skips the specials while the regular seasons have anything left`() {
        store()
        repository.saveProgress("s1-e1", positionMs = 60_000L, runtimeMs = 1_400_000L, null, null)
        assertEquals(listOf("s1-e2"), repository.nextUp(10).map { it.id })

        // Once the run is finished the specials are all that is left, and then
        // they are what comes next.
        repository.setPlayed("s1-e1", true)
        repository.setPlayed("s1-e2", true)
        assertEquals(listOf("sp-e1"), repository.nextUp(10).map { it.id })
    }

    @Test
    fun `a series lists its specials last, so the page opens on season one`() {
        store()
        assertEquals(listOf("s1", "sp"), repository.children("show").map { it.id })
    }

    /**
     * Episodes that sit directly under the series carry no season at all. They
     * are the main run, not extras, so a null season must not be read as zero.
     */
    @Test
    fun `an episode with no season is not treated as a special`() {
        repository.upsertItems(
            listOf(
                MediaItemDto(id = "flat", libraryId = "lib", kind = ItemKind.SERIES, name = "Flat"),
                MediaItemDto(
                    id = "flat-e1", libraryId = "lib", kind = ItemKind.EPISODE, name = "flat-e1",
                    parentId = "flat", seriesId = "flat", indexNumber = 1, path = "/x/E01.mkv"
                ),
                MediaItemDto(
                    id = "flat-sp", libraryId = "lib", kind = ItemKind.EPISODE, name = "flat-sp",
                    parentId = "flat", seriesId = "flat", indexNumber = 1, parentIndexNumber = 0,
                    path = "/x/Specials/E01.mkv"
                )
            ).map { ItemRecord(dto = it) }
        )
        assertEquals("flat-e1", repository.nextEpisodeUnder("flat")?.id)
    }
}
