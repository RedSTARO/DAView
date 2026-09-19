package com.daview.server.scraper

import com.daview.server.config.ScraperConfig
import com.daview.server.db.Database
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.ScrapeStatus
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A field typed in by hand has to outlast a re-scrape until it is handed back,
 * and a pin that was taken off has to stay off.
 */
class ManualEditsTest {

    private val dir = createTempDirectory("daview-manual-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private val scraper = CountingScraper(
        ScrapedMetadata(
            provider = MetadataProvider.TMDB,
            providerId = "603",
            name = "黑客帝国",
            overview = "刮削的简介",
            year = 1999,
            communityRating = 8.2,
            posterUrl = "https://example.invalid/matrix.jpg"
        )
    )
    private val service = MetadataService(repository, mapOf(MetadataProvider.TMDB to scraper))
    private val config = ScraperConfig(tmdbApiKey = "test-key")

    @AfterTest
    fun tearDown() = database.close()

    private fun movie(id: String) = MediaItemDto(
        id = id,
        libraryId = "lib",
        kind = ItemKind.MOVIE,
        name = "The.Matrix.1999.1080p",
        year = 1999,
        providerIds = mapOf("tmdb" to "603")
    )

    private fun fresh(id: String) = repository.item(id)!!

    @Test
    fun `a title typed in by hand survives a re-scrape`() {
        repository.upsertItem(ItemRecord(dto = movie("m")))
        repository.updateItemFields("m", name = "我改的片名", originalName = null, overview = null, year = null, genres = null)

        assertTrue(service.enrichItem(fresh("m"), listOf(MetadataProvider.TMDB), config))

        val after = fresh("m")
        assertEquals("我改的片名", after.name)
        assertEquals("刮削的简介", after.overview, "only the typed field is held back")
        assertEquals(MetadataProvider.TMDB, after.communityRatingSource)
    }

    @Test
    fun `handing the fields back lets the scrape write them again`() {
        repository.upsertItem(ItemRecord(dto = movie("m")))
        repository.updateItemFields("m", name = "我改的片名", originalName = null, overview = null, year = null, genres = null)
        repository.clearManualFields("m")

        service.enrichItem(fresh("m"), listOf(MetadataProvider.TMDB), config)

        assertEquals("黑客帝国", fresh("m").name)
        assertTrue(fresh("m").manualFields.isEmpty())
    }

    @Test
    fun `a pin taken off is not re-applied from the pin table`() {
        repository.upsertItem(ItemRecord(dto = movie("p").copy(lockedProvider = MetadataProvider.TMDB)))
        repository.savePin("p", MetadataProvider.TMDB.name, "603")

        service.unpin(fresh("p"), listOf(MetadataProvider.TMDB), config)
        val unlocked = fresh("p")
        assertNull(unlocked.lockedProvider)
        assertEquals(MetadataProvider.NONE.name, repository.pin("p")?.provider, "the pin stays as a tombstone")
        // The pinned id is not looked up again: matching starts from the folder,
        // and this scraper's search finds nothing for it.
        assertNull(unlocked.providerIds["tmdb"], "the pinned id must not be reused")
        assertEquals(ScrapeStatus.UNMATCHED, unlocked.scrapeStatus)
        assertNull(unlocked.overview, "the pinned entry's details go with it")

        service.enrichItem(unlocked, listOf(MetadataProvider.TMDB), config)
        assertNull(fresh("p").lockedProvider, "the tombstone must not lock the item again")
    }

    @Test
    fun `unpinning keeps what was typed in by hand`() {
        repository.upsertItem(ItemRecord(dto = movie("q").copy(lockedProvider = MetadataProvider.TMDB)))
        repository.updateItemFields("q", name = "我改的片名", originalName = null, overview = null, year = null, genres = null)

        service.unpin(fresh("q"), listOf(MetadataProvider.TMDB), config)

        assertEquals("我改的片名", fresh("q").name)
    }
}

private class CountingScraper(private val result: ScrapedMetadata) : MetadataScraper {
    override val provider: MetadataProvider = MetadataProvider.TMDB
    override fun isConfigured(config: ScraperConfig) = true
    override fun search(title: String, year: Int?, kind: ItemKind, config: ScraperConfig): List<ScrapeCandidate> =
        emptyList()

    override fun details(providerId: String, kind: ItemKind, config: ScraperConfig): ScrapedMetadata? =
        result.takeIf { providerId == it.providerId }
}
