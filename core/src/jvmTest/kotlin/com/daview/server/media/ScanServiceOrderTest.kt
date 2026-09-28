package com.daview.server.media

import com.daview.server.config.AppConfig
import com.daview.server.config.ScraperConfig
import com.daview.server.db.Database
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.server.library.Scanner
import com.daview.server.scraper.MetadataScraper
import com.daview.server.scraper.MetadataService
import com.daview.server.scraper.SCRAPE_CACHE_MAX_AGE_MS
import com.daview.server.scraper.ScrapeCandidate
import com.daview.server.scraper.ScrapedEpisode
import com.daview.server.scraper.ScrapedMetadata
import com.daview.server.storage.DavEntry
import com.daview.server.storage.DirectoryLister
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.ScanMode
import com.daview.shared.model.ScanProgressDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * The everyday scan is ordered by level: a folder the library has never seen
 * is scraped before the folders it knows are so much as read, and a show that
 * gained an episode has its episode list fetched again — past the cache — so
 * the new file gets its title instead of keeping its file name.
 */
class ScanServiceOrderTest {

    private val dir = createTempDirectory("daview-scan-order-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private val scraper = RecordingScraper()
    private val metadata = MetadataService(repository, mapOf(MetadataProvider.TMDB to scraper))
    private val tree = MemoryTree()
    private val scans = ScanService(
        repository,
        metadata,
        StreamService({ null }, repository),
        davProvider = { tree },
        configProvider = { AppConfig() }
    )
    private val library = LibraryDto(
        id = "lib", name = "番剧", kind = LibraryKind.ANIME, path = "/Ani",
        providerOrder = listOf(MetadataProvider.TMDB)
    )

    @AfterTest
    fun tearDown() = database.close()

    /** Lists a tree held in memory. */
    private class MemoryTree : DirectoryLister {
        private val dirs = HashMap<String, MutableList<DavEntry>>()

        fun file(path: String) {
            val parent = path.substringBeforeLast('/')
            dir(parent)
            dirs.getValue(parent) += DavEntry(path.substringAfterLast('/'), path, isDirectory = false, size = 1, lastModified = null, etag = null)
        }

        private fun dir(path: String) {
            if (path in dirs) return
            dirs[path] = ArrayList()
            if (path.count { it == '/' } > 1) {
                val parent = path.substringBeforeLast('/')
                dir(parent)
                dirs.getValue(parent) += DavEntry(path.substringAfterLast('/'), path, isDirectory = true, size = null, lastModified = null, etag = null)
            }
        }

        override fun list(relativePath: String): List<DavEntry> = dirs[relativePath]?.toList() ?: emptyList()
    }

    /** Matches any title to itself and writes down every call, in order. */
    private class RecordingScraper : MetadataScraper {
        val calls = mutableListOf<String>()
        override val provider = MetadataProvider.TMDB
        override fun isConfigured(config: ScraperConfig) = true

        override fun search(title: String, year: Int?, kind: ItemKind, config: ScraperConfig): List<ScrapeCandidate> {
            calls += "search:$title"
            return listOf(ScrapeCandidate(providerId = title, title = title, originalTitle = null, year = year, overview = null, posterUrl = null))
        }

        override fun details(providerId: String, kind: ItemKind, config: ScraperConfig): ScrapedMetadata {
            calls += "details:$providerId"
            return ScrapedMetadata(
                provider = provider, providerId = providerId, name = providerId,
                overview = "关于 $providerId", posterUrl = "https://example/$providerId.jpg"
            )
        }

        override fun episodes(providerId: String, config: ScraperConfig, maxAgeMs: Long): List<ScrapedEpisode> {
            calls += "episodes:$providerId:$maxAgeMs"
            return (1..3).map { ScrapedEpisode(season = 1, episode = it, name = "$providerId 第 $it 话") }
        }
    }

    private fun scan(): ScanProgressDto {
        scans.submit(library, ScanMode.FULL)
        val deadline = System.currentTimeMillis() + 30_000
        while (scans.anyRunning()) {
            if (System.currentTimeMillis() > deadline) fail("the scan did not finish")
            Thread.sleep(20)
        }
        val status = scans.status().single()
        status.error?.let { fail("scan failed: $it") }
        assertEquals("done", status.phase)
        return status
    }

    private fun id(path: String) = Scanner.itemId("lib", path)

    @Test
    fun `new folders are scraped before known ones are read, and grown shows are refreshed`() {
        tree.file("/Ani/Show (2020)/Season 01/Show - S01E01.mkv")
        val first = scan()
        assertEquals("Show 第 1 话", repository.item(id("/Ani/Show (2020)/Season 01/Show - S01E01.mkv"))?.name)
        assertEquals(1, first.newTitles)
        assertEquals(0, first.newEpisodes)
        assertEquals("完成：新增 1 部", first.message)
        scraper.calls.clear()

        tree.file("/Ani/Show (2020)/Season 01/Show - S01E02.mkv")
        tree.file("/Ani/Other (2021)/Season 01/Other - S01E01.mkv")
        val second = scan()

        assertEquals(
            listOf(
                "search:Other",
                "details:Other",
                "episodes:Other:$SCRAPE_CACHE_MAX_AGE_MS",
                "episodes:Show:0"
            ),
            scraper.calls,
            "the new show first, then the grown show's episodes, fetched fresh"
        )
        assertEquals("Show 第 2 话", repository.item(id("/Ani/Show (2020)/Season 01/Show - S01E02.mkv"))?.name)
        assertEquals(1, second.newTitles)
        assertEquals(1, second.newEpisodes)
        assertEquals("完成：新增 1 部，新增 1 集", second.message)
        scraper.calls.clear()

        val third = scan()
        assertEquals(emptyList(), scraper.calls, "nothing changed, nothing is asked")
        assertEquals("完成，没有变化", third.message)
        assertNull(third.error)
    }
}
