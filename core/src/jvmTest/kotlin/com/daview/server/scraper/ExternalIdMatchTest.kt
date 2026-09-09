package com.daview.server.scraper

import com.daview.server.config.ScraperConfig
import com.daview.server.db.Database
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A library moved over from Emby or Jellyfin arrives carrying TVDB ids — in
 * the rows, and in the folder names those servers leave behind. Somebody, or
 * something, already decided what each folder is.
 *
 * Matching by title again throws that away and re-decides by guesswork, which
 * is how a move quietly ends with a handful of entries pointing at the wrong
 * show. So an id from another database is used when one is there, and the
 * title search is only what happens when there is not.
 */
class ExternalIdMatchTest {

    private val dir = createTempDirectory("daview-external-id-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)

    @AfterTest
    fun tearDown() = database.close()

    /** Answers either way, and records which way it was asked. */
    private class Fake(
        override val provider: MetadataProvider,
        private val translates: Boolean
    ) : MetadataScraper {

        var searchedByTitle = false
        var askedAbout: String? = null

        override fun isConfigured(config: ScraperConfig) = true

        override fun search(
            title: String,
            year: Int?,
            kind: ItemKind,
            config: ScraperConfig
        ): List<ScrapeCandidate> {
            searchedByTitle = true
            return listOf(
                ScrapeCandidate(
                    providerId = "found-by-title",
                    title = title,
                    originalTitle = null,
                    year = year,
                    overview = null,
                    posterUrl = null
                )
            )
        }

        override fun fromExternalIds(
            providerIds: Map<String, String>,
            kind: ItemKind,
            config: ScraperConfig
        ): String? = if (translates) providerIds["tvdb"]?.let { "tmdb-of-tvdb-$it" } else null

        override fun details(
            providerId: String,
            kind: ItemKind,
            config: ScraperConfig
        ): ScrapedMetadata {
            askedAbout = providerId
            return ScrapedMetadata(
                provider = provider,
                providerId = providerId,
                name = "Resolved",
                overview = "not blank, so the loop settles here",
                posterUrl = "http://example.invalid/poster.jpg"
            )
        }
    }

    private fun store(vararg ids: Pair<String, String>): MediaItemDto {
        val item = MediaItemDto(
            id = "show",
            libraryId = "lib",
            kind = ItemKind.SERIES,
            name = "Beyond the Boundary",
            year = 2013,
            providerIds = ids.toMap()
        )
        repository.upsertItems(listOf(ItemRecord(dto = item)))
        return item
    }

    private fun scrape(item: MediaItemDto, scraper: Fake): Boolean =
        MetadataService(repository, mapOf(MetadataProvider.TMDB to scraper))
            .enrichItem(item, listOf(MetadataProvider.TMDB), ScraperConfig(tmdbApiKey = "key"))

    @Test
    fun `an id from another database is used instead of searching by title`() {
        val item = store("tvdb" to "263300")
        val scraper = Fake(MetadataProvider.TMDB, translates = true)

        assertTrue(scrape(item, scraper))
        assertEquals("tmdb-of-tvdb-263300", scraper.askedAbout)
        assertFalse(scraper.searchedByTitle, "the title search would re-decide a settled match")
    }

    @Test
    fun `the title search is what happens when there is no id to translate`() {
        val item = store()
        val scraper = Fake(MetadataProvider.TMDB, translates = true)

        assertTrue(scrape(item, scraper))
        assertEquals("found-by-title", scraper.askedAbout)
        assertTrue(scraper.searchedByTitle)
    }

    /** A source that cannot translate the id still falls back to the title. */
    @Test
    fun `an id the source cannot translate does not block the match`() {
        val item = store("tvdb" to "263300")
        val scraper = Fake(MetadataProvider.TMDB, translates = false)

        assertTrue(scrape(item, scraper))
        assertEquals("found-by-title", scraper.askedAbout)
        assertTrue(scraper.searchedByTitle)
    }
}
