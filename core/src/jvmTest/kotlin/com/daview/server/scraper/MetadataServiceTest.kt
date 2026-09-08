package com.daview.server.scraper

import com.daview.server.db.Database
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Candidate matching is where a scraper quietly ruins a library: a confident
 * wrong match replaces the title, the year and the artwork with someone else's.
 * These cases all come from real folders in the reference share.
 */
class MetadataServiceTest {

    private val dir = createTempDirectory("daview-scraper-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val service = MetadataService(Repository(database))

    @AfterTest
    fun tearDown() = database.close()

    private fun item(name: String, year: Int?) = MediaItemDto(
        id = "x", libraryId = "lib", kind = ItemKind.SERIES, name = name, year = year
    )

    private fun candidate(title: String, year: Int?, original: String? = null, rank: Int = 0) =
        ScrapeCandidate(
            providerId = title, title = title, originalTitle = original,
            year = year, overview = null, posterUrl = null, rank = rank
        )

    @Test
    fun `same title in a different year is rejected`() {
        val result = service.bestMatch(
            item("All Is Well", 2019),
            listOf(candidate("All is Well", 2024))
        )
        assertNull(result, "a 2024 show must not match a 2019 folder")
    }

    @Test
    fun `an unrelated title far down the ranking is rejected even with the right year`() {
        val result = service.bestMatch(
            item("Anohana The Flower We Saw That Day", 2011),
            listOf(candidate("Something Completely Different", 2011, rank = 7))
        )
        assertNull(result, "only the provider's top hits are trusted on year alone")
    }

    @Test
    fun `a top ranked hit with the right year is trusted across languages`() {
        // bangumi.tv answers in Japanese: string similarity is ~0, but its own
        // search ranking is what actually resolved the romaji query.
        val result = service.bestMatch(
            item("Bocchi the Rock!", 2022),
            listOf(candidate("孤独摇滚！", 2022, original = "ぼっち・ざ・ろっく！", rank = 0))
        )
        assertEquals("孤独摇滚！", result?.title)
    }

    @Test
    fun `close titles with the right year win`() {
        val result = service.bestMatch(
            item("16bit Sensation - Another Layer", 2023),
            listOf(
                candidate("16bit的感动 ANOTHER LAYER", 2023, "16bit Sensation Another Layer"),
                candidate("Something Else Entirely", 2023)
            )
        )
        assertEquals("16bit的感动 ANOTHER LAYER", result?.title)
    }

    @Test
    fun `an exact title matches even when the folder carries no year`() {
        val result = service.bestMatch(
            item("Bocchi the Rock!", null),
            listOf(candidate("Bocchi the Rock!", 2022))
        )
        assertEquals("Bocchi the Rock!", result?.title)
    }

    @Test
    fun `the original title is considered as well as the localised one`() {
        val result = service.bestMatch(
            item("Lycoris Recoil", 2022),
            listOf(candidate("莉可丽丝", 2022, "Lycoris Recoil"))
        )
        assertEquals("莉可丽丝", result?.title)
    }

    @Test
    fun `no candidates yields no match`() {
        assertNull(service.bestMatch(item("Whatever", 2020), emptyList()))
    }
}
