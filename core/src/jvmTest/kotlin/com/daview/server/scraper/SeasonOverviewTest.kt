package com.daview.server.scraper

import com.daview.server.config.ScraperConfig
import com.daview.server.db.Database
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A show with several seasons shows the picked season's synopsis on its page,
 * so each season row has to get one from the scrape and keep it through the
 * scans that follow.
 */
class SeasonOverviewTest {

    private val dir = createTempDirectory("daview-season-overview-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private val scraper = SeasonScraper()
    private val service = MetadataService(repository, mapOf(MetadataProvider.TMDB to scraper))
    private val config = ScraperConfig(tmdbApiKey = "test-key")
    private val order = listOf(MetadataProvider.TMDB)

    @AfterTest
    fun tearDown() = database.close()

    private val show = MediaItemDto(
        id = "show", libraryId = "lib", kind = ItemKind.SERIES, name = "Show",
        providerIds = mapOf("tmdb" to "1"), path = "/Ani/Show"
    )

    /** A season row as the scanner writes it: a number and a folder, nothing scraped. */
    private fun season(number: Int) = ItemRecord(
        dto = MediaItemDto(
            id = "season$number", libraryId = "lib", kind = ItemKind.SEASON,
            parentId = "show", seriesId = "show", name = if (number == 0) "特别篇" else "第 $number 季",
            indexNumber = number, path = "/Ani/Show/Season $number"
        )
    )

    private fun scan(vararg seasons: Int) {
        repository.upsertScannedItems(listOf(ItemRecord(dto = show)) + seasons.map { season(it) })
    }

    private fun overview(season: Int) = repository.item("season$season")?.overview

    @Test
    fun `each season of a show with several gets its own synopsis`() {
        scan(1, 2, 0)

        assertTrue(service.enrichItem(show, order, config))

        assertEquals("第一季的简介", overview(1))
        assertEquals("第二季的简介", overview(2))
        assertNull(overview(0), "the source says nothing about the specials")
        assertEquals("整部剧的简介", repository.item("show")?.overview)
    }

    @Test
    fun `a rescan keeps the season synopsis`() {
        scan(1, 2)
        service.enrichItem(show, order, config)

        scan(1, 2)

        assertEquals("第二季的简介", overview(2), "the scanner's empty synopsis must not win")
        assertNotNull(repository.itemRecord("season2")?.scrapedAt)
    }

    @Test
    fun `a source with nothing to say keeps what is stored`() {
        scan(1, 2)
        service.enrichItem(show, order, config)

        scraper.seasons = emptyList()
        service.enrichItem(show, order, config)

        assertEquals("第二季的简介", overview(2), "a timeout must not wipe what an earlier scrape found")
    }

    @Test
    fun `a failing source still leaves the show scraped`() {
        scan(1, 2)
        scraper.failSeasons = true

        assertTrue(service.enrichItem(show, order, config))

        assertEquals("整部剧的简介", repository.item("show")?.overview)
        assertNull(overview(2))
    }

    @Test
    fun `a show with one season is not asked about its seasons`() {
        scan(1, 0)

        service.enrichItem(show, order, config)

        assertEquals(0, scraper.seasonCalls)
        assertNull(overview(1))
    }

    @Test
    fun `picking another entry drops the previous one's season synopses`() {
        scan(1, 2)
        service.enrichItem(show, order, config)

        val updated = service.identify(show, MetadataProvider.TMDB, "2", order, config)

        assertNotNull(updated)
        assertNull(overview(2), "the synopsis described the wrong show")
        assertNull(repository.itemRecord("season2")?.scrapedAt, "the scanner owns the row again")
    }

    @Test
    fun `a show that gained a season is asked afresh`() {
        scan(1)
        service.enrichItem(show, order, config)
        scan(1, 2)

        val library = LibraryDto(id = "lib", name = "番剧", kind = LibraryKind.ANIME, path = "/Ani", providerOrder = order)
        assertTrue(service.refreshEpisodes(repository.item("show")!!, library, config))

        assertEquals("第二季的简介", overview(2))
        assertEquals(0L, scraper.lastMaxAgeMs, "a cached season list would not know the new season")
    }
}

/** Knows show "1", with two seasons, and show "2", with none. */
private class SeasonScraper : MetadataScraper {
    override val provider = MetadataProvider.TMDB

    var seasons = listOf(
        ScrapedSeason(1, "第一季的简介"),
        ScrapedSeason(2, "第二季的简介")
    )
    var failSeasons = false
    var seasonCalls = 0
        private set
    var lastMaxAgeMs: Long? = null
        private set

    override fun isConfigured(config: ScraperConfig) = true

    override fun search(title: String, year: Int?, kind: ItemKind, config: ScraperConfig) = emptyList<ScrapeCandidate>()

    override fun details(providerId: String, kind: ItemKind, config: ScraperConfig): ScrapedMetadata? =
        when (providerId) {
            "1" -> ScrapedMetadata(
                provider = MetadataProvider.TMDB, providerId = "1", name = "Show",
                overview = "整部剧的简介", posterUrl = "https://example.invalid/1.jpg"
            )
            "2" -> ScrapedMetadata(
                provider = MetadataProvider.TMDB, providerId = "2", name = "Another Show",
                overview = "另一部剧的简介", posterUrl = "https://example.invalid/2.jpg"
            )
            else -> null
        }

    override fun seasons(providerId: String, upTo: Int, config: ScraperConfig, maxAgeMs: Long): List<ScrapedSeason> {
        seasonCalls++
        lastMaxAgeMs = maxAgeMs
        if (failSeasons) throw IllegalStateException("unexpected response")
        return if (providerId == "1") seasons.filter { it.season <= upTo } else emptyList()
    }
}
