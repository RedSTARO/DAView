package com.daview.server.sync

import com.daview.server.ServerContext
import com.daview.server.config.ScraperConfig
import com.daview.server.config.StorageConfig
import com.daview.server.db.Database
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.server.library.Scanner
import com.daview.server.media.ScanService
import com.daview.server.media.StreamService
import com.daview.server.scraper.MetadataScraper
import com.daview.server.scraper.MetadataService
import com.daview.server.scraper.ScrapeCandidate
import com.daview.server.scraper.ScrapedEpisode
import com.daview.server.scraper.ScrapedMetadata
import com.daview.server.storage.DavEntry
import com.daview.server.storage.DirectoryLister
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.ManualField
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataLogDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.ScanMode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Two and more devices against one share, stood up on loopback so that every
 * request the feature makes can be counted. Counting them is the point: what
 * has to hold is not only that the rows arrive, but that a change costs one
 * segment and a device with nothing to do costs nothing.
 */
class MetadataLogTest {

    private val share = FakeShare()
    private val devices = ArrayList<Device>()

    @AfterTest
    fun tearDown() {
        devices.forEach { it.close() }
        share.close()
    }

    private companion object {
        const val LIBRARY = "library"
        const val SYNC_FILE = "/daview-sync.json"
    }

    private inner class Device(name: String, language: String = "zh-CN") : AutoCloseable {
        val dir = createTempDirectory("daview-metadata-$name")
        val context = ServerContext(dir)
        val repository: Repository get() = context.repository
        val log: MetadataLog get() = context.sync.metadata

        init {
            devices += this
            context.updateConfig {
                it.copy(
                    storage = StorageConfig(url = share.url),
                    scraper = it.scraper.copy(language = language),
                    sync = it.sync.copy(enabled = true)
                )
            }
            repository.upsertLibrary(
                LibraryDto(LIBRARY, "影片", LibraryKind.MOVIE, "/Films", updatedAt = 1, providerOrder = listOf(MetadataProvider.TMDB), language = "")
            )
            context.sync.pullOnStartup()
            log.settle()
        }

        val writer: String get() = context.config.sync.deviceId

        fun scanned(id: String) = repository.upsertScannedItems(
            listOf(ItemRecord(dto = MediaItemDto(id = id, libraryId = LIBRARY, kind = ItemKind.MOVIE, name = id, path = "/Films/$id.mkv")))
        )

        /** Writes a row as a scrape does, stamped [at]. */
        fun scraped(id: String, name: String, at: Long, edit: MediaItemDto.() -> MediaItemDto = { this }) {
            repository.upsertItem(
                ItemRecord(
                    dto = MediaItemDto(
                        id = id, libraryId = LIBRARY, kind = ItemKind.MOVIE, name = name, overview = "关于 $name",
                        posterUrl = "https://example.invalid/$id.jpg", path = "/Films/$id.mkv"
                    ).edit(),
                    scrapedAt = at
                )
            )
        }

        /** What the timer does: read the sync file, then let the log catch up and publish. */
        fun sync(): MetadataLog.Moved {
            context.sync.pull()
            return log.cycle(force = true)
        }

        fun held(): Int = context.database.read { connection ->
            connection.statement("SELECT COUNT(*) FROM metadata_held").useQuery { rs -> rs.next(); rs.getIntAt(1) }
        }

        fun appliedFrom(other: Device): Long = repository.metadataLog(other.writer)?.appliedSeq ?: 0

        override fun close() = context.close()
    }

    private fun segments(): List<String> = share.puts.filter { it != SYNC_FILE }

    /** The ids a file on the share carries. */
    private fun rowsIn(path: String): List<String> =
        GZIPInputStream(share.files.getValue(path).inputStream()).bufferedReader().readLines().drop(1).map { line ->
            ((Json.parseToJsonElement(line) as JsonObject)["id"] as JsonPrimitive).content
        }

    private fun text(path: String): String =
        GZIPInputStream(share.files.getValue(path).inputStream()).bufferedReader().readText()

    private fun folderFile(name: String) = "/daview-sync.metadata/$name"

    // ------------------------------------------------------------ the rows arrive

    @Test
    fun `what one device scrapes reaches the other, and does not come back`() {
        val a = Device("a")
        val b = Device("b")
        b.scanned("one")
        a.scraped("one", "千与千寻", at = 100)

        a.sync()
        share.clearLog()
        val moved = b.sync()

        assertEquals(1, moved.received)
        val row = assertNotNull(b.repository.itemRecord("one"))
        assertEquals("千与千寻", row.dto.name)
        assertEquals("关于 千与千寻", row.dto.overview)
        assertEquals("https://example.invalid/one.jpg", row.dto.posterUrl)
        assertEquals(100L, row.scrapedAt)
        assertEquals("/Films/one.mkv", row.dto.path, "what the files say stays this device's own")
        assertEquals(emptyList(), share.puts.toList(), "a row that came from the share went back to it")
    }

    @Test
    fun `a change costs one segment holding that row, read once`() {
        val a = Device("a")
        val b = Device("b")
        listOf("one", "two", "three").forEach { a.scraped(it, it, at = 100); b.scanned(it) }
        a.sync()
        b.sync()

        share.clearLog()
        a.scraped("two", "改过的", at = 200)
        a.sync()

        val written = segments().single()
        assertEquals(listOf("two"), rowsIn(written))
        assertTrue(share.gets.none { it != SYNC_FILE }, "publishing read a segment: ${share.gets}")

        share.clearLog()
        assertEquals(1, b.sync().received)
        assertEquals(listOf(SYNC_FILE, written), share.gets.toList())
        assertEquals("改过的", b.repository.item("two")?.name)
    }

    @Test
    fun `a device with nothing to do reads the sync file and nothing else`() {
        val a = Device("a")
        val b = Device("b")
        a.scraped("one", "千与千寻", at = 100)
        b.scanned("one")
        a.sync()
        b.sync()

        share.clearLog()
        assertEquals(MetadataLog.Moved(), b.sync())

        assertEquals(listOf(SYNC_FILE), share.gets.toList())
        assertEquals(emptyList(), share.puts.toList())
    }

    @Test
    fun `publishing waits for the interval unless it is asked for`() {
        val a = Device("a")
        a.context.updateConfig { it.copy(sync = it.sync.copy(minIntervalMinutes = 60)) }
        a.scraped("one", "千与千寻", at = 100)
        a.log.cycle(force = true)
        a.scraped("two", "天空之城", at = 100)
        share.clearLog()

        a.log.cycle()
        assertEquals(emptyList(), segments())

        a.log.cycle(force = true)
        assertEquals(listOf("two"), rowsIn(segments().single()))
    }

    @Test
    fun `a large publish is split so that one failed request costs one segment`() {
        val a = Device("a")
        repeat(2_500) { a.scraped("item-${it.toString().padStart(4, '0')}", "第 $it 部", at = 100) }

        a.sync()

        assertEquals(2, segments().size)
        assertEquals(2_000, rowsIn(segments().first()).size)
        assertEquals(500, rowsIn(segments().last()).size)
        assertEquals(2L, a.repository.metadataLog(a.writer)?.seq)
    }

    @Test
    fun `switching the metadata off leaves the share and the catalogue alone`() {
        val a = Device("a")
        a.context.updateConfig { it.copy(sync = it.sync.copy(metadata = false)) }
        a.scraped("one", "千与千寻", at = 100)

        assertEquals(MetadataLog.Moved(), a.sync())

        assertEquals(emptyList(), segments())
    }

    // ------------------------------------------------------------ scans

    /** Lists a tree held in memory and records whether a provider was ever asked. */
    private class Library(private val titles: List<String>) : DirectoryLister {
        override fun list(relativePath: String): List<DavEntry> = when {
            relativePath == "/Films" -> titles.map { DavEntry(it, "/Films/$it", true, null, null, null) }
            else -> {
                val title = relativePath.substringAfterLast('/')
                listOf(DavEntry("$title.mkv", "$relativePath/$title.mkv", false, 1, null, null))
            }
        }

        fun id(title: String) = Scanner.itemId(LIBRARY, "/Films/$title/$title.mkv")
    }

    private class CountingScraper : MetadataScraper {
        val titles = ConcurrentLinkedQueue<String>()
        override val provider = MetadataProvider.TMDB
        override fun isConfigured(config: ScraperConfig) = true

        override fun search(title: String, year: Int?, kind: ItemKind, config: ScraperConfig): List<ScrapeCandidate> {
            titles += title
            return listOf(ScrapeCandidate(title, title, null, year, null, null))
        }

        override fun details(providerId: String, kind: ItemKind, config: ScraperConfig) =
            ScrapedMetadata(provider, providerId, providerId, overview = "本机刮削")
    }

    private fun Device.scan(tree: DirectoryLister, scraper: CountingScraper) {
        val scans = ScanService(
            repository,
            MetadataService(repository, mapOf(MetadataProvider.TMDB to scraper)),
            StreamService({ null }, repository),
            davProvider = { tree },
            configProvider = { context.config },
            beforeScan = { report -> log.catchUp(report) }
        )
        val library = assertNotNull(repository.library(LIBRARY))
        scans.submit(library, ScanMode.FULL)
        val deadline = System.currentTimeMillis() + 30_000
        while (scans.anyRunning()) {
            if (System.currentTimeMillis() > deadline) fail("the scan did not finish")
            Thread.sleep(10)
        }
        val status = scans.status().single()
        assertEquals("done", status.phase, status.error)
    }

    @Test
    fun `a scan creates what another device scraped without asking a provider`() {
        val a = Device("a")
        val b = Device("b")
        val tree = Library(listOf("Spirited Away (2001)", "Castle (1986)"))
        a.scraped(tree.id("Spirited Away (2001)"), "千与千寻", at = 100)
        a.sync()
        b.context.sync.pull()

        val scraper = CountingScraper()
        b.scan(tree, scraper)

        assertEquals(listOf("Castle"), scraper.titles.toList(), "only what nobody scraped goes to a provider")
        val filled = assertNotNull(b.repository.item(tree.id("Spirited Away (2001)")))
        assertEquals("千与千寻", filled.name)
        assertEquals("本机刮削", b.repository.item(tree.id("Castle (1986)"))?.overview)
        assertEquals(0, b.held())
    }

    @Test
    fun `rows wait for a device that has never scanned, and leave once a walk does not find them`() {
        val a = Device("a")
        val b = Device("b")
        val tree = Library(listOf("Spirited Away (2001)"))
        a.scraped(tree.id("Spirited Away (2001)"), "千与千寻", at = 100)
        a.scraped("gone from the share", "已删除", at = 100)
        a.sync()

        b.sync()
        assertEquals(2, b.held())

        b.scan(tree, CountingScraper())
        assertEquals(0, b.held())
    }

    @Test
    fun `a row older than the last walk is not kept waiting`() {
        val a = Device("a")
        val b = Device("b")
        b.repository.markScanned(LIBRARY, 500)
        a.scraped("gone from the share", "已删除", at = 100)
        a.sync()

        b.sync()

        assertEquals(0, b.held())
    }

    @Test
    fun `a library this device deleted gets nothing back`() {
        val a = Device("a")
        val b = Device("b")
        b.scanned("one")
        a.scraped("one", "千与千寻", at = 100)
        a.context.sync.upload()
        a.sync()
        b.repository.deleteLibrary(LIBRARY)

        b.sync()

        assertNull(b.repository.library(LIBRARY))
        assertEquals(0, b.held())
    }

    @Test
    fun `a new episode does not re-date the ones that came back the same`() {
        val a = Device("a")
        val repository = a.repository
        val show = MediaItemDto(
            id = "show", libraryId = LIBRARY, kind = ItemKind.SERIES, name = "Show", path = "/Films/Show",
            providerIds = mapOf("tmdb" to "1")
        )
        fun episode(number: Int) = ItemRecord(
            dto = MediaItemDto(
                id = "episode-$number", libraryId = LIBRARY, kind = ItemKind.EPISODE, name = "$number",
                seriesId = "show", parentIndexNumber = 1, indexNumber = number, path = "/Films/Show/$number.mkv"
            )
        )
        var listed = (1..2).map { ScrapedEpisode(1, it, "第 $it 话") }
        val scraper = object : MetadataScraper {
            override val provider = MetadataProvider.TMDB
            override fun isConfigured(config: ScraperConfig) = true
            override fun search(title: String, year: Int?, kind: ItemKind, config: ScraperConfig) = emptyList<ScrapeCandidate>()
            override fun details(providerId: String, kind: ItemKind, config: ScraperConfig) =
                ScrapedMetadata(provider, providerId, "Show", overview = "简介")
            override fun episodes(providerId: String, config: ScraperConfig, maxAgeMs: Long) = listed
        }
        val service = MetadataService(repository, mapOf(MetadataProvider.TMDB to scraper))
        val library = assertNotNull(repository.library(LIBRARY))
        repository.upsertItem(ItemRecord(dto = show, scrapedAt = 50))
        repository.upsertItems(listOf(episode(1), episode(2)))
        service.refreshEpisodes(assertNotNull(repository.item("show")), library, ScraperConfig())
        a.sync()

        repository.upsertItems(listOf(episode(3)))
        listed = (1..3).map { ScrapedEpisode(1, it, "第 $it 话") }
        share.clearLog()
        service.refreshEpisodes(assertNotNull(repository.item("show")), library, ScraperConfig())
        a.sync()

        assertEquals(listOf("episode-3"), rowsIn(segments().single()))
    }

    @Test
    fun `scraping again and getting the same answer sends nothing`() {
        val a = Device("a")
        val scraper = CountingScraper()
        val service = MetadataService(a.repository, mapOf(MetadataProvider.TMDB to scraper))
        a.scanned("Film")
        val order = listOf(MetadataProvider.TMDB)
        service.enrichItem(assertNotNull(a.repository.item("Film")), order, ScraperConfig())
        a.sync()

        share.clearLog()
        service.enrichItem(assertNotNull(a.repository.item("Film")), order, ScraperConfig())
        a.sync()

        assertEquals(emptyList(), segments())
    }

    // ------------------------------------------------------------ who wins

    @Test
    fun `the newer scrape wins on every device, whichever log is read first`() {
        val a = Device("a")
        val b = Device("b")
        a.scraped("one", "旧的", at = 100)
        b.scraped("one", "新的", at = 200)
        a.scraped("two", "新的", at = 200)
        b.scraped("two", "旧的", at = 100)

        a.sync()
        b.sync()
        a.sync()

        listOf(a, b).forEach { device ->
            assertEquals("新的", device.repository.item("one")?.name)
            assertEquals("新的", device.repository.item("two")?.name)
        }
        share.clearLog()
        assertEquals(MetadataLog.Moved(), a.sync())
        assertEquals(MetadataLog.Moved(), b.sync())
    }

    @Test
    fun `two devices that scraped the same words do not send them to each other`() {
        val a = Device("a")
        val b = Device("b")
        listOf("one", "two", "three").forEach { a.scraped(it, it, at = 100) }
        listOf("one", "two", "three").forEach { b.scraped(it, it, at = 200) }

        a.sync()
        share.clearLog()
        b.sync()

        assertEquals(emptyList(), segments(), "the first sync after upgrading would send the library back")
    }

    @Test
    fun `a device set to another language leaves the rows alone`() {
        val a = Device("a", language = "zh-CN")
        val b = Device("b", language = "en-US")
        a.scraped("one", "千与千寻", at = 100)
        b.scraped("one", "Spirited Away", at = 50)

        a.sync()
        b.sync()

        assertEquals("Spirited Away", b.repository.item("one")?.name)
        a.sync()
        assertEquals("千与千寻", a.repository.item("one")?.name)
    }

    @Test
    fun `a pin outranks a newer row that disagrees with it`() {
        val a = Device("a")
        val b = Device("b")
        b.repository.savePin("one", MetadataProvider.TMDB.name, "603", updatedAt = 500)
        b.scraped("one", "钉住的", at = 300) { copy(lockedProvider = MetadataProvider.TMDB, providerIds = mapOf("tmdb" to "603")) }
        a.scraped("one", "自动匹配的", at = 900) { copy(providerIds = mapOf("tmdb" to "111")) }

        a.sync()
        b.sync()

        assertEquals("钉住的", b.repository.item("one")?.name)
    }

    @Test
    fun `a row that honours a pin replaces one that does not, however old`() {
        val a = Device("a")
        val b = Device("b")
        a.repository.savePin("one", MetadataProvider.TMDB.name, "603", updatedAt = 150)
        a.scraped("one", "手动指定的", at = 100) { copy(lockedProvider = MetadataProvider.TMDB, providerIds = mapOf("tmdb" to "603")) }
        b.scraped("one", "自动匹配的", at = 300) { copy(providerIds = mapOf("tmdb" to "111")) }
        a.context.sync.upload()

        b.context.sync.pull()
        assertNotNull(b.repository.pin("one"), "the correction arrives with the sync file")
        a.sync()
        b.sync()

        val row = assertNotNull(b.repository.item("one"))
        assertEquals("手动指定的", row.name)
        assertEquals(MetadataProvider.TMDB, row.lockedProvider)
    }

    @Test
    fun `rows from before a pin was taken off stay out`() {
        val a = Device("a")
        val b = Device("b")
        a.scraped("one", "指定错的", at = 100)
        a.scraped("two", "之后才刮削的", at = 900)
        a.sync()
        listOf("one", "two").forEach { id ->
            b.scanned(id)
            b.repository.savePin(id, MetadataProvider.NONE.name, "", updatedAt = 500)
        }

        b.sync()

        assertEquals("one", b.repository.item("one")?.name)
        assertEquals("之后才刮削的", b.repository.item("two")?.name)
    }

    // ------------------------------------------------------------ what people did by hand

    @Test
    fun `what somebody typed travels, and is still theirs on the other device`() {
        val a = Device("a")
        val b = Device("b")
        a.scraped("one", "自动", at = 100)
        b.scanned("one")
        a.sync()
        b.sync()

        a.repository.updateItemFields("one", name = "手写的片名", originalName = null, overview = null, year = null, genres = null)
        a.sync()
        b.sync()

        val row = assertNotNull(b.repository.item("one"))
        assertEquals("手写的片名", row.name)
        assertEquals(listOf(ManualField.NAME), row.manualFields)
    }

    @Test
    fun `giving a field back reaches the other device even when the words do not change`() {
        val a = Device("a")
        val b = Device("b")
        b.scanned("one")
        a.scraped("one", "片名", at = 100)
        a.repository.updateItemFields("one", name = "片名", originalName = null, overview = null, year = null, genres = null)
        a.sync()
        b.sync()
        assertEquals(listOf(ManualField.NAME), b.repository.item("one")?.manualFields)

        Thread.sleep(2)
        a.repository.clearManualFields("one")
        a.sync()
        b.sync()

        assertEquals(emptyList(), b.repository.item("one")?.manualFields)
    }

    @Test
    fun `a picture chosen by hand stays on its device, and its path stays there too`() {
        val a = Device("a")
        val b = Device("b")
        b.scraped("one", "千与千寻", at = 100)
        a.scraped("one", "千与千寻", at = 100)
        a.sync()
        b.sync()

        val path = "file:C:\\Users\\somebody\\DAView\\artwork\\one.jpg"
        a.repository.setArtwork("one", "poster", path)
        a.repository.updateItemFields("one", name = "改过的", originalName = null, overview = null, year = null, genres = null)
        share.clearLog()
        a.sync()
        assertFalse(segments().any { "somebody" in text(it) }, "a local path was published")
        b.sync()

        val received = assertNotNull(b.repository.item("one"))
        assertEquals("改过的", received.name)
        assertEquals("https://example.invalid/one.jpg", received.posterUrl)
        assertFalse(ManualField.POSTER in received.manualFields)
    }

    @Test
    fun `a newer row does not replace a picture this device chose`() {
        val a = Device("a")
        val b = Device("b")
        val path = "file:/data/artwork/one.jpg"
        b.scraped("one", "千与千寻", at = 100)
        b.repository.setArtwork("one", "poster", path)
        a.scraped("one", "新的", at = 200)

        a.sync()
        b.sync()

        val kept = assertNotNull(b.repository.item("one"))
        assertEquals("新的", kept.name)
        assertEquals(path, kept.posterUrl)
        assertTrue(ManualField.POSTER in kept.manualFields)
    }

    // ------------------------------------------------------------ compaction

    private fun Device.publishTimes(count: Int, first: Int = 0) = repeat(count) { index ->
        scraped("row-${first + index}", "第 ${first + index} 部", at = 1_000L + first + index)
        sync()
    }

    @Test
    fun `a new device reads one base rather than the whole history`() {
        val a = Device("a")
        a.publishTimes(16)
        val base = folderFile("${a.writer}-base-16.jsonl.gz")
        assertEquals(16, rowsIn(base).size)

        share.clearLog()
        val c = Device("c")

        assertEquals(16, c.held())
        assertEquals(listOf(SYNC_FILE, base), share.gets.toList(), "a new device starts reading on its own")
    }

    @Test
    fun `a device that is keeping up never reads the base`() {
        val a = Device("a")
        val b = Device("b")
        repeat(15) { index ->
            a.publishTimes(1, index)
            b.sync()
        }
        a.publishTimes(1, 15)

        share.clearLog()
        b.sync()

        assertEquals(listOf(SYNC_FILE, folderFile("${a.writer}-16.jsonl.gz")), share.gets.toList())
    }

    @Test
    fun `a compaction removes what the one before it covered, and only that`() {
        val a = Device("a")
        a.publishTimes(32)

        val log = assertNotNull(a.repository.metadataLog(a.writer))
        assertEquals(32L, log.baseSeq)
        assertEquals(17L, log.firstSeq)
        val names = share.files.keys.filter { it != SYNC_FILE }.toSet()
        assertEquals(
            (17..32).map { folderFile("${a.writer}-$it.jsonl.gz") }.toSet() + folderFile("${a.writer}-base-32.jsonl.gz"),
            names
        )
    }

    @Test
    fun `a device left behind by a compaction takes the base and carries on`() {
        val a = Device("a")
        val b = Device("b")
        a.publishTimes(4)
        b.sync()
        assertEquals(4L, b.appliedFrom(a))

        a.publishTimes(30, 4)
        share.clearLog()
        b.sync()

        assertEquals(34L, b.appliedFrom(a))
        assertEquals(34, b.held())
        val reads = share.gets.filter { it != SYNC_FILE }
        assertEquals(
            listOf(folderFile("${a.writer}-base-32.jsonl.gz")) +
                (33..34).map { folderFile("${a.writer}-$it.jsonl.gz") },
            reads
        )
    }

    @Test
    fun `rows somebody else has replaced drop out of the next base`() {
        val a = Device("a")
        val b = Device("b")
        a.scraped("replaced", "旧的", at = 10)
        b.scraped("replaced", "旧的", at = 5)
        a.sync()
        b.scraped("replaced", "新的", at = 20)
        b.sync()
        a.sync()
        assertEquals("新的", a.repository.item("replaced")?.name)

        a.publishTimes(15)

        val base = folderFile("${a.writer}-base-16.jsonl.gz")
        assertFalse("replaced" in rowsIn(base))
        assertEquals(15, rowsIn(base).size)
    }

    // ------------------------------------------------------------ when things go wrong

    @Test
    fun `a share that takes no folder keeps the files beside the sync file`() {
        val a = Device("a")
        val b = Device("b")
        share.foldersAllowed = false
        a.scraped("one", "千与千寻", at = 100)
        b.scanned("one")

        a.sync()
        assertEquals(listOf("/daview-sync.metadata-${a.writer}-1.jsonl.gz"), segments())
        assertTrue(assertNotNull(a.repository.metadataLog(a.writer)).flat)

        b.sync()
        assertEquals("千与千寻", b.repository.item("one")?.name)
    }

    @Test
    fun `a failed upload leaves the rows waiting and says so`() {
        val a = Device("a")
        a.scraped("one", "千与千寻", at = 100)
        share.refuseWrites = true
        a.sync()

        assertEquals(emptyList(), segments())
        assertNotNull(a.context.config.sync.metadataError)

        share.refuseWrites = false
        a.sync()

        assertEquals(listOf("one"), rowsIn(segments().single()))
        assertNull(a.context.config.sync.metadataError)
    }

    @Test
    fun `a share out of reach leaves a device where it was, to carry on later`() {
        val a = Device("a")
        val b = Device("b")
        b.scanned("one")
        a.scraped("one", "千与千寻", at = 100)
        a.sync()
        b.context.sync.pull()

        share.failReads = true
        b.log.cycle(force = true)
        assertEquals(0L, b.appliedFrom(a))
        assertNotNull(b.context.config.sync.metadataError)

        share.failReads = false
        b.log.cycle(force = true)
        assertEquals(1L, b.appliedFrom(a))
        assertNull(b.context.config.sync.metadataError)
    }

    @Test
    fun `a segment that is really gone is passed over only once fresh sync files keep pointing at it`() {
        val a = Device("a")
        val b = Device("b")
        a.publishTimes(3)
        val missing = folderFile("${a.writer}-2.jsonl.gz")
        share.files.remove(missing)

        b.context.sync.pull()
        repeat(5) { b.log.cycle(force = true) }
        assertEquals(1L, b.appliedFrom(a), "the same sync file read over and over is no evidence")

        b.sync()
        assertEquals(1L, b.appliedFrom(a))
        b.sync()

        assertEquals(3L, b.appliedFrom(a))
    }

    @Test
    fun `a file from a newer build is left for a newer build`() {
        val a = Device("a")
        val b = Device("b")
        a.scraped("one", "千与千寻", at = 100)
        b.scanned("one")
        a.sync()
        val segment = segments().single()
        share.files[segment] = gzip("""{"format":"daview-metadata","version":99,"writer":"${a.writer}","seq":1}""" + "\n")

        repeat(4) { b.sync() }

        assertEquals(0L, b.appliedFrom(a))
        assertTrue(assertNotNull(b.context.config.sync.metadataError).contains("99"))
    }

    @Test
    fun `a log somebody else is writing is left to them`() {
        val a = Device("a")
        a.scraped("one", "千与千寻", at = 100)
        a.sync()
        val before = a.writer

        // What a copied data directory looks like from here: this device's own
        // log, further along than this device took it.
        a.log.onManifest(listOf(MetadataLogDto(before, seq = 5, updatedAt = System.currentTimeMillis() + 1_000)))
        share.clearLog()
        a.log.cycle(force = true)

        assertNotEquals(before, a.writer)
        assertEquals(listOf(folderFile("${a.writer}-1.jsonl.gz")), segments())
        assertEquals(listOf("one"), rowsIn(segments().single()))
        assertFalse(share.files.keys.any { "$before-2" in it || "$before-6" in it })
    }

    @Test
    fun `moving the sync file starts the logs over at the new place`() {
        val a = Device("a")
        a.scraped("one", "千与千寻", at = 100)
        a.sync()

        a.context.updateConfig { it.copy(sync = it.sync.copy(remotePath = "/sync/daview.json")) }
        a.log.restart()
        share.mkdir("/sync")
        share.mkdir("/sync/daview.metadata")
        share.clearLog()
        a.sync()

        assertEquals(listOf("/sync/daview.metadata/${a.writer}-1.jsonl.gz"), segments().filter { it != "/sync/daview.json" })
    }

    @Test
    fun `a sync file written by an older build gets its log entries back`() {
        val a = Device("a")
        a.scraped("one", "千与千寻", at = 100)
        a.sync()
        val text = share.files.getValue(SYNC_FILE).decodeToString()
        assertTrue("metadataLogs" in text)
        val stripped = (Json.parseToJsonElement(text) as JsonObject).filterKeys { it != "metadataLogs" }
        share.files[SYNC_FILE] = JsonObject(stripped).toString().toByteArray()

        a.context.sync.pull()
        a.context.updateConfig { it.copy(sync = it.sync.copy(lastUploadAt = 0)) }
        a.context.sync.tick()

        assertTrue("metadataLogs" in share.files.getValue(SYNC_FILE).decodeToString())
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ the table

    @Test
    fun `finding what waits to go out does not walk the catalogue`() {
        val a = Device("a")
        val plan = a.context.database.read { connection ->
            connection.statement(
                "EXPLAIN QUERY PLAN SELECT id FROM items " +
                    "WHERE scraped_at IS NOT NULL AND scraped_at IS NOT published_at ORDER BY id LIMIT 10"
            ).useQuery { rs -> buildString { while (rs.next()) append(rs.getString("detail")) } }
        }
        assertTrue("idx_items_unpublished" in plan, plan)
    }

    @Test
    fun `hand edits made before the upgrade outrank what other devices scraped`() {
        val dir = createTempDirectory("daview-metadata-upgrade")
        val old = Database(JdbcSqlDatabase(dir))
        Repository(old).upsertItems(
            listOf("typed", "scraped").map { id ->
                ItemRecord(dto = MediaItemDto(id = id, libraryId = LIBRARY, kind = ItemKind.MOVIE, name = id), scrapedAt = 5)
            }
        )
        old.transaction { connection ->
            connection.statement("UPDATE items SET manual_fields = '[\"name\"]' WHERE id = 'typed'").use { it.executeUpdate() }
            connection.statement("UPDATE schema_version SET version = version - 1").use { it.executeUpdate() }
        }
        old.close()

        val reopened = Database(JdbcSqlDatabase(dir))
        try {
            val repository = Repository(reopened)
            assertTrue(assertNotNull(repository.itemRecord("typed")?.scrapedAt) > 1_000_000_000_000L)
            assertEquals(5L, repository.itemRecord("scraped")?.scrapedAt)
        } finally {
            reopened.close()
        }
    }
}

/** Just enough of a WebDAV share: files by path, folders that must exist, and a record of every request. */
private class FakeShare : AutoCloseable {
    val files = ConcurrentHashMap<String, ByteArray>()
    val gets = ConcurrentLinkedQueue<String>()
    val puts = ConcurrentLinkedQueue<String>()

    @Volatile
    var foldersAllowed = true

    @Volatile
    var refuseWrites = false

    @Volatile
    var failReads = false

    private val folders = ConcurrentHashMap.newKeySet<String>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun clearLog() {
        gets.clear()
        puts.clear()
    }

    fun mkdir(path: String) {
        folders += path
    }

    private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) = try { block(this) } finally { close() }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        when (exchange.requestMethod) {
            "GET" -> {
                if (failReads) return exchange.sendResponseHeaders(500, -1)
                gets += path
                val body = files[path] ?: return exchange.sendResponseHeaders(404, -1)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
            "PUT" -> {
                val body = exchange.requestBody.readBytes()
                val parent = path.substringBeforeLast('/')
                when {
                    refuseWrites -> exchange.sendResponseHeaders(403, -1)
                    parent.isNotEmpty() && parent !in folders -> exchange.sendResponseHeaders(409, -1)
                    else -> {
                        puts += path
                        files[path] = body
                        exchange.sendResponseHeaders(201, -1)
                    }
                }
            }
            "MKCOL" -> {
                if (foldersAllowed && !refuseWrites) {
                    folders += path
                    exchange.sendResponseHeaders(201, -1)
                } else exchange.sendResponseHeaders(403, -1)
            }
            "DELETE" -> {
                files.remove(path)
                exchange.sendResponseHeaders(204, -1)
            }
            else -> exchange.sendResponseHeaders(405, -1)
        }
    }

    override fun close() = server.stop(0)
}
