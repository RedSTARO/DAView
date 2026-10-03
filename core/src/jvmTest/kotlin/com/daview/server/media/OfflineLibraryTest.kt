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
import java.util.Locale
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
import kotlin.test.assertNotEquals
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
        var rangeOverride: ((String, Long, Long?) -> WebDavClient.RangeStream)? = null

        override fun fileSize(path: String): Long? = files[path]?.size?.toLong()

        override fun openRange(path: String, start: Long, end: Long?): WebDavClient.RangeStream {
            synchronized(requests) { requests += path to start }
            rangeOverride?.let { return it(path, start, end) }
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

    private fun rememberVideo(itemId: String, path: String, target: Path, state: DownloadState, size: Long, downloaded: Long) {
        repository.saveDownload(
            DownloadDto(itemId, itemId, state, totalBytes = size, downloadedBytes = downloaded),
            file = target.toString(), mediaPath = path
        )
        repository.saveDownloadFile(
            Repository.DownloadFileRow(itemId, path, target.toString(), DOWNLOAD_KIND_VIDEO, state, size, downloaded)
        )
    }

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

    private fun awaitFailed(itemId: String) = await(itemId) { it?.state == DownloadState.FAILED }!!

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
        // A real interrupted transfer has a persisted owner for its partial file.
        rememberVideo("ep1", videoPath, videoTarget, DownloadState.FAILED, video.size.toLong(), 100_000L)

        offline.start("ep1")
        awaitDone("ep1")

        assertEquals(listOf(100_000L), source.requestsFor(videoPath), "asked only for the range after the partial file")
        assertContentEquals(video, videoTarget.readBytes())
        assertFalse(partial.exists())
    }

    @Test
    fun `resuming preserves a registered legacy destination outside the default layout`() {
        share()
        store(episode(subtitles = emptyList()))
        val legacy = dir.resolve("old-downloads/kept-name.mkv")
        val partial = Path.of(legacy.toString() + OfflineLibrary.PART_SUFFIX)
        partial.parent.createDirectories()
        partial.writeBytes(video.copyOfRange(0, 100_000))
        rememberVideo("ep1", videoPath, legacy, DownloadState.RUNNING, video.size.toLong(), 100_000L)

        assertEquals(1, offline.resumePending())
        awaitDone("ep1")
        assertEquals(legacy, offline.localFile(videoPath))
        assertEquals(listOf(100_000L), source.requestsFor(videoPath))
        assertContentEquals(video, legacy.readBytes())
        assertFalse(partial.exists())
        assertFalse(videoTarget.exists())
    }

    @Test
    fun `a full response to a resume request is rejected without appending`() {
        share()
        store(episode(subtitles = emptyList()))
        val partial = Path.of(videoTarget.toString() + OfflineLibrary.PART_SUFFIX)
        partial.parent.createDirectories()
        val prefix = video.copyOfRange(0, 100_000)
        partial.writeBytes(prefix)
        rememberVideo("ep1", videoPath, videoTarget, DownloadState.FAILED, video.size.toLong(), prefix.size.toLong())
        source.rangeOverride = { _, _, _ ->
            WebDavClient.RangeStream(ByteArrayInputStream(video), totalSize = video.size.toLong(), partial = false)
        }

        offline.start("ep1")
        awaitFailed("ep1")
        assertEquals(listOf(100_000L), source.requestsFor(videoPath))
        assertContentEquals(prefix, partial.readBytes())
        assertFalse(videoTarget.exists())
        assertNull(offline.localFile(videoPath))
    }

    @Test
    fun `an oversized partial is not marked complete`() {
        share()
        store(episode(subtitles = emptyList()))
        val partial = Path.of(videoTarget.toString() + OfflineLibrary.PART_SUFFIX)
        partial.parent.createDirectories()
        val oversized = video + byteArrayOf(42)
        partial.writeBytes(oversized)
        rememberVideo("ep1", videoPath, videoTarget, DownloadState.FAILED, video.size.toLong(), oversized.size.toLong())

        offline.start("ep1")
        awaitFailed("ep1")
        assertContentEquals(oversized, partial.readBytes())
        assertFalse(videoTarget.exists())
        assertNull(offline.localFile(videoPath))
    }

    @Test
    fun `a longer source is not considered complete at the old recorded length`() {
        share()
        store(episode(subtitles = emptyList()))
        val partial = Path.of(videoTarget.toString() + OfflineLibrary.PART_SUFFIX)
        partial.parent.createDirectories()
        val prefix = video.copyOfRange(0, 100_000)
        partial.writeBytes(prefix)
        rememberVideo("ep1", videoPath, videoTarget, DownloadState.FAILED, prefix.size.toLong(), prefix.size.toLong())

        offline.start("ep1")
        awaitFailed("ep1")
        assertEquals(listOf(100_000L), source.requestsFor(videoPath))
        assertContentEquals(prefix, partial.readBytes())
        assertFalse(videoTarget.exists())
    }

    @Test
    fun `response bytes beyond the declared length are not accepted as a finished file`() {
        share()
        store(episode(subtitles = emptyList()))
        source.rangeOverride = { _, _, _ ->
            WebDavClient.RangeStream(
                ByteArrayInputStream(video + byteArrayOf(42)), totalSize = video.size.toLong(), partial = false
            )
        }
        offline.start("ep1")
        awaitFailed("ep1")

        assertFalse(videoTarget.exists())
        assertNull(offline.localFile(videoPath))
        val bytes = Path.of(videoTarget.toString() + OfflineLibrary.PART_SUFFIX).readBytes()
        assertTrue(bytes.size <= video.size)
        assertContentEquals(video.copyOfRange(0, bytes.size), bytes)
    }

    @Test
    fun `416 without a confirmed length does not promote a partial file`() {
        store(episode(subtitles = emptyList()))
        val partial = Path.of(videoTarget.toString() + OfflineLibrary.PART_SUFFIX)
        partial.parent.createDirectories()
        partial.writeBytes(video)
        rememberVideo("ep1", videoPath, videoTarget, DownloadState.RUNNING, 0L, video.size.toLong())
        // No size is available, and 416 alone cannot distinguish complete from oversized.
        source.rangeOverride = { _, _, _ -> throw WebDavException("out of range", 416) }
        offline.start("ep1")
        awaitFailed("ep1")
        assertContentEquals(video, partial.readBytes())
        assertFalse(videoTarget.exists())
    }

    @Test
    fun `a complete registered partial is promoted after its source length is confirmed`() {
        share()
        store(episode(subtitles = emptyList()))
        val partial = Path.of(videoTarget.toString() + OfflineLibrary.PART_SUFFIX)
        partial.parent.createDirectories()
        partial.writeBytes(video)
        rememberVideo("ep1", videoPath, videoTarget, DownloadState.RUNNING, video.size.toLong(), video.size.toLong())
        offline.start("ep1")
        awaitDone("ep1")
        assertEquals(listOf(video.size.toLong()), source.requestsFor(videoPath))
        assertContentEquals(video, videoTarget.readBytes())
        assertFalse(partial.exists())
    }

    @Test
    fun `resuming after a network pause uses the size learned by the first response`() {
        share()
        store(episode(subtitles = emptyList()).copy(sizeBytes = 1L))
        source.rangeOverride = { _, start, _ ->
            val input = object : ByteArrayInputStream(video.copyOfRange(start.toInt(), video.size)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val read = super.read(b, off, len)
                    if (start == 0L && read > 0) offline.transferGate = { false }
                    return read
                }
            }
            WebDavClient.RangeStream(input, totalSize = video.size.toLong(), partial = start > 0)
        }
        offline.start("ep1")
        await("ep1") { it?.state == DownloadState.QUEUED && it.note == offline.transferGateReason }
        val partial = Path.of(videoTarget.toString() + OfflineLibrary.PART_SUFFIX)
        val received = Files.size(partial)
        assertTrue(received in 1 until video.size.toLong())
        assertEquals(video.size.toLong(), repository.downloadFiles("ep1").single().totalBytes)

        offline.transferGate = { true }
        awaitDone("ep1")
        assertEquals(listOf(0L, received), source.requestsFor(videoPath))
        assertContentEquals(video, videoTarget.readBytes())
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

    private fun assertSeparateDownloads(firstPath: String, secondPath: String) {
        // Equal lengths reproduce the old fast path that marked the second
        // download DONE without fetching even a byte of its own content.
        val firstBytes = ByteArray(4096) { 1 }
        val secondBytes = ByteArray(4096) { 2 }
        source.files[firstPath] = firstBytes
        source.files[secondPath] = secondBytes
        store(
            episode("first", emptyList()).copy(path = firstPath, sizeBytes = firstBytes.size.toLong()),
            episode("second", emptyList()).copy(path = secondPath, sizeBytes = secondBytes.size.toLong())
        )
        offline.start("first")
        awaitDone("first")
        offline.start("second")
        awaitDone("second")

        val first = assertNotNull(offline.localFile(firstPath))
        val second = assertNotNull(offline.localFile(secondPath))
        assertNotEquals(first.toString().lowercase(Locale.ROOT), second.toString().lowercase(Locale.ROOT))
        assertFalse(Files.isSameFile(first, second))
        assertContentEquals(firstBytes, first.readBytes())
        assertContentEquals(secondBytes, second.readBytes())
        assertEquals(listOf(0L), source.requestsFor(secondPath))

        // The first worker has exited before the single executor starts the second.
        offline.remove("first")
        await("first") { it == null }
        assertFalse(first.exists())
        assertTrue(repository.downloadFiles("first").isEmpty())
        assertTrue(offline.isComplete("second"))
        assertContentEquals(secondBytes, assertNotNull(offline.localFile(secondPath)).readBytes())
    }

    @Test
    fun `sanitized video names do not share bytes or deletion`() {
        assertSeparateDownloads("/Movies/a:b.mkv", "/Movies/a_b.mkv")
    }

    @Test
    fun `video names differing only in case stay isolated on every host`() {
        assertSeparateDownloads("/Movies/Film.mkv", "/movies/film.mkv")
    }

    @Test
    fun `colliding directory names do not make videos share a destination`() {
        assertSeparateDownloads("/Movies/A:B/Film.mkv", "/Movies/A_B/Film.mkv")
    }

    @Test
    fun `subtitle names are reserved ignoring case and sanitized characters`() {
        val paths = listOf("/Subs/a:b.ass", "/Subs/a_b.ass", "/Subs/A_B.ass")
        source.files[videoPath] = video
        val contents = paths.mapIndexed { index, path ->
            ByteArray(128) { index.toByte() }.also { source.files[path] = it }
        }
        store(episode(subtitles = paths))
        offline.start("ep1")
        awaitDone("ep1")

        val locals = paths.map { assertNotNull(offline.localFile(it)) }
        assertEquals(3, locals.map { it.toString().lowercase(Locale.ROOT) }.toSet().size)
        locals.forEachIndexed { index, local ->
            assertContentEquals(contents[index], local.readBytes())
            assertEquals(listOf(0L), source.requestsFor(paths[index]))
        }
    }

    @Test
    fun `subtitles from different downloads do not share bytes or deletion`() {
        val firstSub = "/Subs/First/en.ass"
        val secondSub = "/Subs/Second/en.ass"
        val secondVideo = videoPath.replace("S01E01", "S01E02")
        source.files[videoPath] = video
        source.files[secondVideo] = video
        source.files[firstSub] = "first".toByteArray()
        source.files[secondSub] = "other".toByteArray()
        store(episode("first", listOf(firstSub)), episode("second", listOf(secondSub)).copy(path = secondVideo))
        offline.start("first")
        awaitDone("first")
        offline.start("second")
        awaitDone("second")

        val first = assertNotNull(offline.localFile(firstSub))
        val second = assertNotNull(offline.localFile(secondSub))
        assertNotEquals(first, second)
        assertContentEquals(source.files.getValue(firstSub), first.readBytes())
        assertContentEquals(source.files.getValue(secondSub), second.readBytes())
        offline.remove("first")
        await("first") { it == null }
        assertFalse(first.exists())
        assertTrue(offline.isComplete("second"))
        assertContentEquals(source.files.getValue(secondSub), second.readBytes())
    }

    @Test
    fun `new downloads do not adopt unregistered files or partial bytes`() {
        share()
        store(episode(subtitles = emptyList()))
        videoTarget.parent.createDirectories()
        val unrelated = ByteArray(video.size) { 42 }
        val partial = Path.of(videoTarget.toString() + OfflineLibrary.PART_SUFFIX)
        videoTarget.writeBytes(unrelated)
        partial.writeBytes(unrelated)
        offline.start("ep1")
        awaitDone("ep1")

        val downloaded = assertNotNull(offline.localFile(videoPath))
        assertNotEquals(videoTarget, downloaded)
        assertContentEquals(video, downloaded.readBytes())
        assertContentEquals(unrelated, videoTarget.readBytes())
        assertContentEquals(unrelated, partial.readBytes())
        assertEquals(listOf(0L), source.requestsFor(videoPath))
    }

    @Test
    fun `removing a legacy shared target keeps the other download intact`() {
        val firstPath = "/Movies/a:b.mkv"
        val secondPath = "/Movies/a_b.mkv"
        val shared = dir.resolve("offline/Movies/a_b.mkv")
        shared.parent.createDirectories()
        shared.writeBytes(video)
        rememberVideo("first", firstPath, shared, DownloadState.DONE, video.size.toLong(), video.size.toLong())
        rememberVideo("second", secondPath, shared, DownloadState.DONE, video.size.toLong(), video.size.toLong())

        offline.remove("first")
        assertNull(repository.download("first"))
        assertNotNull(repository.download("second"))
        assertContentEquals(video, assertNotNull(offline.localFile(secondPath)).readBytes())

        offline.remove("second")
        assertFalse(shared.exists(), "the last owner releases the file")
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
