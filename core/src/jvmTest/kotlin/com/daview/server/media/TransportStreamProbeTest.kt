package com.daview.server.media

import com.daview.server.db.Database
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.server.library.TransportStreams
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A Blu-ray .m2ts on its way through the probe: its tracks and runtime end up
 * on the item beside the subtitle file the scan found; a read that fails on the
 * network is tried again later rather than written off; and a file an older
 * build stamped as probed with nothing found gets read once the database is
 * opened by this one.
 */
class TransportStreamProbeTest {

    private val dir = createTempDirectory("daview-ts-probe-test")
    private var database = Database(JdbcSqlDatabase(dir))
    private var repository = Repository(database)
    private val path = "/Ani/Kaiji (2007)/Season 02/Kaiji - S02E03.m2ts"
    private val subtitle = MediaStreamDto(
        index = 1000, type = StreamType.SUBTITLE, codec = "ass", language = "zh-Hans",
        isExternal = true, externalPath = "/Ani/Kaiji (2007)/Season 02/Kaiji - S02E03.zh-Hans.ass"
    )

    @AfterTest
    fun tearDown() = database.close()

    private fun episode(id: String, path: String, size: Long?, streams: List<MediaStreamDto> = listOf(subtitle)) = MediaItemDto(
        id = id, libraryId = "lib", kind = ItemKind.EPISODE, name = "第 3 集", path = path,
        sizeBytes = size, mediaStreams = streams
    )

    private fun service(local: Path?) = StreamService({ null }, repository).apply { offlineFile = { local } }

    @Test
    fun `a Blu-ray clip gets its tracks and runtime, after the subtitle file the scan found`() {
        val bytes = TransportStreams.kaiji(packetSize = 192)
        val file = dir.resolve("clip.m2ts").also { it.writeBytes(bytes) }
        val item = episode("e3", path, bytes.size.toLong())
        repository.upsertItem(ItemRecord(dto = item))

        val probed = service(file).probeItem(item)

        assertEquals(1_357_354L, probed.runtimeMs)
        assertEquals(listOf(4113, 4352, 4608, 1000), probed.mediaStreams.map { it.index })
        assertNotNull(repository.itemRecord("e3")?.probedAt)
        assertEquals(probed.mediaStreams, repository.item("e3")?.mediaStreams)
    }

    @Test
    fun `a read that fails is not recorded as a file with nothing in it`() {
        val item = episode("e3", path, 4_326_807_552L)
        repository.upsertItem(ItemRecord(dto = item))

        val probed = service(dir.resolve("missing.m2ts")).probeItem(item)

        assertEquals(listOf(1000), probed.mediaStreams.map { it.index })
        assertNull(repository.itemRecord("e3")?.probedAt)
    }

    @Test
    fun `a file that is not what its name says is recorded as read`() {
        val file = dir.resolve("clip.m2ts").also { it.writeBytes(ByteArray(64 * 1024) { (it * 7).toByte() }) }
        val item = episode("e3", path, Files.size(file))
        repository.upsertItem(ItemRecord(dto = item))

        service(file).probeItem(item)

        assertNotNull(repository.itemRecord("e3")?.probedAt)
    }

    @Test
    fun `transport streams an older build stamped with nothing found are read again`() {
        val stamped = 1_700_000_000_000L
        repository.upsertItem(ItemRecord(dto = episode("ts", path, 1L), probedAt = stamped))
        repository.upsertItem(ItemRecord(dto = episode("mkv", "/Ani/Show/Show - S01E01.mkv", 1L, emptyList()), probedAt = stamped))
        val read = listOf(MediaStreamDto(index = 4113, type = StreamType.VIDEO, codec = "h264"), subtitle)
        repository.upsertItem(ItemRecord(dto = episode("read", "/Ani/Show/Show - S01E02.M2TS", 1L, read), probedAt = stamped))
        // Back to the version the build before transport streams left, as a
        // database it wrote would be. By number, not "one back": that step is
        // only the last one until another is added after it.
        database.transaction { connection ->
            connection.statement("UPDATE schema_version SET version = 15").use { it.executeUpdate() }
        }
        database.close()

        database = Database(JdbcSqlDatabase(dir))
        repository = Repository(database)

        assertNull(repository.itemRecord("ts")?.probedAt)
        assertEquals(stamped, repository.itemRecord("mkv")?.probedAt)
        assertEquals(stamped, repository.itemRecord("read")?.probedAt)
    }
}
