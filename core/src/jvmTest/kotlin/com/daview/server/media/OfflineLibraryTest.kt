package com.daview.server.media

import com.daview.server.db.DOWNLOAD_KIND_VIDEO
import com.daview.server.db.Database
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.server.storage.WebDavClient
import com.daview.server.storage.WebDavException
import com.daview.shared.model.DownloadDto
import com.daview.shared.model.DownloadState
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A download is the video and its subtitles, or it is not a download: the
 * point of keeping a film is to watch it with no network, and a player sent
 * back to the share for a subtitle file is a player with no subtitles. The
 * rest is what makes the queue survive the things that happen to it — a
 * subtitle that is gone, a connection that drops, a process that dies.
 */
class OfflineLibraryTest {

    private val dir = createTempDirectory("daview-offline-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private val source = MemorySource()
    private val offline = OfflineLibrary(dir, repository, source)

    @AfterTest
    fun tearDown() {
        offline.close()
        database.close()
    }

    /** Answers ranges from memory, and remembers what was asked for. */
    private class MemorySource : RangeSource {
        val files = HashMap<String, ByteArray>()
        val failing = HashSet<String>()
        val requests = ArrayList<Pair<String, Long>>()

        override fun fileSize(path: String): Long? = files[path]?.size?.toLong()

        override fun openRange(path: String, start: Long, end: Long?): WebDavClient.RangeStream {
            synchronized(requests) { requests += path to start }
            val bytes = files[path]?.takeIf { path !in failing }
                ?: throw WebDavException("读取字节区间失败: HTTP 404", 404)
            if (start >= bytes.size) throw WebDavException("读取字节区间失败: HTTP 416", 416)
            val stop = (end?.plus(1) ?: bytes.size.toLong()).toInt()
            return WebDavClient.RangeStream(
                ByteArrayInputStream(bytes.copyOfRange(start.toInt(), stop)),
                totalSize = bytes.size.toLong(),
                partial = start > 0 || end != null
            )
        }

        fun requestsFor(path: String) = synchronized(requests) { requests.filter { it.first == path }.map { it.second } }
    }

    private val videoPath = "/Ani/Show (2020)/Season 01/Show - S01E01.mkv"
    private val subtitleBeside = "/Ani/Show (2020)/Season 01/Show - S01E01.zh-Hans.default.ass"
    private val subtitleNested = "/Ani/Show (2020)/Season 01/Subs/Show - S01E01.zh-Hant.ass"

    private val video = ByteArray(300_000) { (it % 251).toByte() }
    private val besideBytes = "[Script Info]\nTitle: chs".toByteArray()
    private val nestedBytes = "[Script Info]\nTitle: cht".toByteArray()

    private fun episode(id: String = "ep1", subtitles: List<String> = listOf(subtitleBeside, subtitleNested)) =
        MediaItemDto(
            id = id, libraryId = "lib", kind = ItemKind.EPISODE, name = "第 1 集",
            seriesId = "show", parentId = "s1", indexNumber = 1, parentIndexNumber = 1,
            path = videoPath, sizeBytes = video.size.toLong(),
            mediaStreams = subtitles.mapIndexed { index, path ->
                MediaStreamDto(
                    index = 1000 + index, type = StreamType.SUBTITLE, codec = "ass",
                    isExternal = true, externalPath = path
                )
            }
        )

    private fun store(vararg items: MediaItemDto) = repository.upsertItems(items.map { ItemRecord(dto = it) })

    private fun share() {
        source.files[videoPath] = video
        source.files[subtitleBeside] = besideBytes
        source.files[subtitleNested] = nestedBytes
    }

    private fun await(itemId: String, timeoutMs: Long = 15_000, until: (DownloadDto?) -> Boolean): DownloadDto? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val row = offline.status(itemId)
            if (until(row)) return row
            if (System.currentTimeMillis() > deadline) error("timed out waiting for $itemId, last state: $row")
            Thread.sleep(40)
        }
    }

    private fun awaitDone(itemId: String) = await(itemId) { it?.state == DownloadState.DONE }!!

    private val videoTarget: Path
        get() = dir.resolve("offline").resolve("Ani").resolve("Show (2020)").resolve("Season 01")
            .resolve("Show - S01E01.mkv")

    @Test
    fun `a download brings the subtitles along and lays them out beside the video`() {
        share()
        store(episode())

        offline.start("ep1")
        val row = awaitDone("ep1")

        assertEquals(2, row.subtitleCount)
        assertEquals(0, row.failedSubtitles)
        assertNull(row.note)
        assertEquals((video.size + besideBytes.size + nestedBytes.size).toLong(), row.totalBytes)
        assertEquals(row.totalBytes, row.downloadedBytes)

        // The video mirrors the share's own folders under the download directory.
        val local = assertNotNull(offline.localFile(videoPath))
        assertEquals(videoTarget, local)
        assertContentEquals(video, local.readBytes())

        // Both subtitles sit next to it, the nested one lifted out of Subs/:
        // that is where a player looks, and the name is what ties it to the video.
        val beside = assertNotNull(offline.localFile(subtitleBeside))
        assertEquals(local.parent, beside.parent)
        assertEquals("Show - S01E01.zh-Hans.default.ass", beside.fileName.toString())
        val nested = assertNotNull(offline.localFile(subtitleNested))
        assertEquals(local.parent, nested.parent)
        assertEquals("Show - S01E01.zh-Hant.ass", nested.fileName.toString())
        assertContentEquals(nestedBytes, nested.readBytes())

        // Nothing half-finished is left behind under a name a player would open.
        Files.walk(dir.resolve("offline")).use { paths ->
            assertTrue(paths.noneMatch { it.toString().endsWith(OfflineLibrary.PART_SUFFIX) })
        }
    }

    @Test
    fun `a subtitle the share will not give up does not fail the film`() {
        share()
        source.failing += subtitleNested
        store(episode())

        offline.start("ep1")
        val row = awaitDone("ep1")

        assertEquals(DownloadState.DONE, row.state)
        assertEquals(1, row.failedSubtitles)
        assertEquals("1 个字幕未能下载", row.note)
        assertNotNull(offline.localFile(videoPath))
        assertNotNull(offline.localFile(subtitleBeside))
        assertNull(offline.localFile(subtitleNested), "a failed subtitle must not be served from disk")
    }

    @Test
    fun `a partial file is continued from where it stopped, not restarted`() {
        share()
        store(episode(subtitles = emptyList()))
        val partial = Path.of(videoTarget.toString() + OfflineLibrary.PART_SUFFIX)
        partial.parent.createDirectories()
        partial.writeBytes(video.copyOfRange(0, 100_000))

        offline.start("ep1")
        awaitDone("ep1")

        assertEquals(listOf(100_000L), source.requestsFor(videoPath), "asked only for the range after the partial file")
        assertContentEquals(video, videoTarget.readBytes())
        assertFalse(partial.exists())
    }

    @Test
    fun `cancelling while waiting for the network drops the row and the bytes`() {
        share()
        store(episode())
        offline.transferGate = { false }
        offline.transferGateReason = "等待 Wi-Fi"

        offline.start("ep1")
        // Held at the gate: still queued, and the row says what for.
        val waiting = await("ep1") { it?.state == DownloadState.QUEUED && it.note == "等待 Wi-Fi" }
        assertNotNull(waiting)
        assertTrue(source.requestsFor(videoPath).isEmpty(), "nothing is fetched while the gate is closed")

        offline.cancel("ep1")
        await("ep1") { it == null }

        assertNull(repository.download("ep1"))
        assertTrue(repository.downloadFiles("ep1").isEmpty())
        assertFalse(dir.resolve("offline").resolve("Ani").exists(), "the folders it made are gone with it")
    }

    @Test
    fun `a download the last process left running is picked up on the next start`() {
        share()
        store(episode())
        // What a process that died mid-transfer leaves behind: a running row,
        // a file row, a partial file — and nothing in memory.
        val partial = Path.of(videoTarget.toString() + OfflineLibrary.PART_SUFFIX)
        partial.parent.createDirectories()
        partial.writeBytes(video.copyOfRange(0, 50_000))
        repository.saveDownload(
            DownloadDto("ep1", "Show", DownloadState.RUNNING, totalBytes = video.size.toLong(), downloadedBytes = 50_000),
            file = videoTarget.toString(), mediaPath = videoPath
        )
        repository.saveDownloadFile(
            Repository.DownloadFileRow(
                "ep1", videoPath, videoTarget.toString(), DOWNLOAD_KIND_VIDEO,
                DownloadState.RUNNING, video.size.toLong(), 50_000
            )
        )

        assertEquals(1, offline.resumePending())
        val row = awaitDone("ep1")

        assertEquals(listOf(50_000L), source.requestsFor(videoPath))
        assertEquals(2, row.subtitleCount)
        assertContentEquals(video, videoTarget.readBytes())
    }

    @Test
    fun `a copy made before subtitles were kept gets them without fetching the video again`() {
        share()
        store(episode())
        // A download from the version that only knew the video: finished, and
        // its one file row is what the schema upgrade carries over.
        videoTarget.parent.createDirectories()
        videoTarget.writeBytes(video)
        repository.saveDownload(
            DownloadDto("ep1", "Show", DownloadState.DONE, totalBytes = video.size.toLong(), downloadedBytes = video.size.toLong()),
            file = videoTarget.toString(), mediaPath = videoPath
        )
        repository.updateDownloadState("ep1", DownloadState.DONE, null)
        repository.saveDownloadFile(
            Repository.DownloadFileRow(
                "ep1", videoPath, videoTarget.toString(), DOWNLOAD_KIND_VIDEO,
                DownloadState.DONE, video.size.toLong(), video.size.toLong()
            )
        )
        // The video is served from disk the whole time.
        assertNotNull(offline.localFile(videoPath))

        assertEquals(1, offline.resumePending())
        val row = awaitDone("ep1")

        assertTrue(source.requestsFor(videoPath).isEmpty(), "the video was here already")
        assertEquals(2, row.subtitleCount)
        assertNotNull(offline.localFile(subtitleBeside))
        assertNotNull(offline.localFile(subtitleNested))
        // Whole now: asking again does nothing.
        assertTrue(offline.isComplete("ep1"))
        assertEquals(0, offline.resumePending())
    }

    @Test
    fun `starting a finished download again does nothing`() {
        share()
        store(episode())
        offline.start("ep1")
        awaitDone("ep1")
        val before = source.requests.size

        offline.start("ep1")
        Thread.sleep(200)
        assertEquals(before, source.requests.size)
    }

    @Test
    fun `removing deletes every file and the folders it made`() {
        share()
        store(episode())
        offline.start("ep1")
        awaitDone("ep1")
        val subtitle = assertNotNull(offline.localFile(subtitleBeside))

        offline.remove("ep1")

        assertNull(repository.download("ep1"))
        assertFalse(videoTarget.exists())
        assertFalse(subtitle.exists())
        assertFalse(dir.resolve("offline").resolve("Ani").exists())
        assertEquals(0L, offline.usedBytes())
    }

    @Test
    fun `share names are made safe for the file system here`() {
        assertEquals("a_b_.ass", OfflineLibrary.safeName("a:b?.ass"))
        assertEquals("_CON.mkv", OfflineLibrary.safeName("CON.mkv"))
        assertEquals("name", OfflineLibrary.safeName("name. "))
        assertEquals("_", OfflineLibrary.safeName("..."))
        val long = OfflineLibrary.safeName("x".repeat(300) + ".mkv")
        assertTrue(long.length <= 180 && long.endsWith(".mkv"))
    }
}
