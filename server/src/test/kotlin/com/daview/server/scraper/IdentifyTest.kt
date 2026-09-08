package com.daview.server.scraper

import com.daview.server.config.ScraperConfig
import com.daview.server.db.Database
import com.daview.server.db.ItemRecord
import com.daview.server.db.Repository
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.PersonDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manual override exists because automatic matching is sometimes wrong, so
 * what matters is that a pinned id is actually honoured and that applying one
 * does not leave the previous match's fields behind.
 */
class IdentifyTest {

    private val dir = createTempDirectory("daview-identify-test")
    private val database = Database(dir)
    private val repository = Repository(database)
    private val scraper = FakeScraper(
        known = mapOf(
            "603" to ScrapedMetadata(
                provider = MetadataProvider.TMDB,
                providerId = "603",
                name = "黑客帝国",
                overview = "对的简介",
                year = 1999,
                posterUrl = "https://example.invalid/matrix.jpg"
            )
        )
    )
    private val service = MetadataService(repository, mapOf(MetadataProvider.TMDB to scraper))
    private val config = ScraperConfig(tmdbApiKey = "test-key")

    @AfterTest
    fun tearDown() = database.close()

    private fun store(item: MediaItemDto) = repository.upsertItem(ItemRecord(dto = item))

    private fun wronglyScraped(id: String, locked: MetadataProvider? = null) = MediaItemDto(
        id = id,
        libraryId = "lib",
        kind = ItemKind.MOVIE,
        name = "错误的片名",
        overview = "错误的简介",
        genres = listOf("错误的类型"),
        people = listOf(PersonDto("错误的演员")),
        posterUrl = "https://example.invalid/wrong.jpg",
        providerIds = mapOf("tmdb" to "111"),
        lockedProvider = locked
    )

    @Test
    fun `a pinned item is never searched again`() {
        val item = wronglyScraped("pinned", locked = MetadataProvider.TMDB)
            .copy(providerIds = mapOf("tmdb" to "603"))
        store(item)

        assertTrue(service.enrichItem(item, listOf(MetadataProvider.TMDB), config))

        assertEquals(0, scraper.searchCalls, "a manual pin must not fall back to searching")
        assertEquals("黑客帝国", repository.item("pinned")?.name)
    }

    @Test
    fun `identify replaces the previous match instead of merging with it`() {
        store(wronglyScraped("swap"))

        val updated = service.identify(
            item = wronglyScraped("swap"),
            provider = MetadataProvider.TMDB,
            providerId = "603",
            order = listOf(MetadataProvider.TMDB),
            config = config
        )

        assertEquals("黑客帝国", updated?.name)
        // The chosen entry lists no genres or cast, so the wrong match's must be
        // gone rather than left showing under the right title.
        assertEquals(emptyList(), updated?.genres)
        assertEquals(emptyList(), updated?.people)
        assertEquals(mapOf("tmdb" to "603"), updated?.providerIds)
        assertEquals(MetadataProvider.TMDB, updated?.lockedProvider)
    }

    @Test
    fun `an id that does not resolve leaves the item alone`() {
        store(wronglyScraped("typo"))

        val updated = service.identify(
            item = wronglyScraped("typo"),
            provider = MetadataProvider.TMDB,
            providerId = "99999",
            order = listOf(MetadataProvider.TMDB),
            config = config
        )

        assertNull(updated)
        assertEquals("错误的片名", repository.item("typo")?.name, "a typo must not wipe what is stored")
    }
}

/** Answers only for the ids it was given, and counts every search it is asked for. */
private class FakeScraper(
    override val provider: MetadataProvider = MetadataProvider.TMDB,
    private val known: Map<String, ScrapedMetadata>
) : MetadataScraper {

    var searchCalls = 0
        private set

    override fun isConfigured(config: ScraperConfig) = true

    override fun search(title: String, year: Int?, kind: ItemKind, config: ScraperConfig): List<ScrapeCandidate> {
        searchCalls++
        return emptyList()
    }

    override fun details(providerId: String, kind: ItemKind, config: ScraperConfig): ScrapedMetadata? =
        known[providerId]
}
