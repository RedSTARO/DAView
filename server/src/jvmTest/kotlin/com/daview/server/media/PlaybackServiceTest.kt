package com.daview.server.media

import com.daview.server.db.Database
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.ItemRecord
import com.daview.server.db.Repository
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlayerKind
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The position of an external player is inferred, so these tests pin down the
 * two inferences that can go visibly wrong: reading the container index at the
 * end of the file must not look like "watched to the end", and the wall clock
 * must not run past what the player has actually downloaded.
 */
class PlaybackServiceTest {

    private val dir = createTempDirectory("daview-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private val streams = StreamService({ null }, repository)
    private val playback = PlaybackService(repository, streams) { 300 }

    private val runtimeMs = 1_440_000L      // 24 minutes
    private val fileSize = 1_024_000_000L   // ~1 GB

    private val item = MediaItemDto(
        id = "item-1",
        libraryId = "lib-1",
        kind = ItemKind.EPISODE,
        name = "Episode 1",
        path = "/Ani/Show (2022)/Season 01/Show - S01E01.mkv",
        sizeBytes = fileSize,
        runtimeMs = runtimeMs
    )

    init {
        repository.upsertLibrary(LibraryDto(id = "lib-1", name = "番剧", kind = LibraryKind.ANIME, path = "/Ani"))
        repository.upsertItem(ItemRecord(dto = item))
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private fun startSession() = playback.start(
        item = item,
        player = PlayerKind.POTPLAYER,
        deviceName = "test",
        startPositionMs = 0,
        audioStreamIndex = null,
        subtitleStreamIndex = null
    )

    @Test
    fun `reading the container index at the tail does not jump to the end`() {
        val session = startSession()
        // PotPlayer reads Matroska Cues a few bytes from EOF right after opening.
        playback.onRangeRequest(session.id, fileSize - 17_909)
        Thread.sleep(SETTLE_WAIT)

        val position = session.positionMs()
        assertTrue(position < runtimeMs / 4, "position should stay near the start but was $position")

        val userData = repository.userData(item.id)
        assertFalse(userData.played, "a metadata read must not mark the episode watched")
        assertTrue(userData.positionMs < runtimeMs / 4)
    }

    @Test
    fun `a settled mid-file range anchors the position`() {
        val session = startSession()
        playback.onRangeRequest(session.id, fileSize / 2)
        Thread.sleep(SETTLE_WAIT)

        val position = session.positionMs()
        val expected = runtimeMs / 2
        assertTrue(
            position in (expected - 30_000)..(expected + 30_000),
            "expected roughly $expected but was $position"
        )
    }

    @Test
    fun `only the last range of an opening burst is used`() {
        val session = startSession()
        // Header probe, tail index read, then the real start offset.
        playback.onRangeRequest(session.id, 0)
        playback.onRangeRequest(session.id, fileSize - 20_000)
        playback.onRangeRequest(session.id, (fileSize * 0.25).toLong())
        Thread.sleep(SETTLE_WAIT)

        val position = session.positionMs()
        val expected = runtimeMs / 4
        assertTrue(
            position in (expected - 30_000)..(expected + 30_000),
            "expected roughly $expected but was $position"
        )
    }

    @Test
    fun `the clock cannot run past what has been downloaded`() {
        val session = startSession()
        playback.onRangeRequest(session.id, 0)
        Thread.sleep(SETTLE_WAIT)
        // Only 1% of the file has actually been delivered, so however long the
        // wall clock has been running the picture cannot be past ~14 seconds.
        playback.onBytesRead(session.id, fileSize / 100)
        session.anchorWallClock = System.currentTimeMillis() - 600_000

        val position = session.positionMs()
        assertTrue(position <= runtimeMs / 50, "download ceiling ignored: $position")
    }

    @Test
    fun `an explicit stop position wins over the estimate`() {
        val session = startSession()
        playback.onRangeRequest(session.id, fileSize / 2)
        Thread.sleep(SETTLE_WAIT)
        playback.stop(session.id, 123_456)

        assertEquals(123_456, repository.userData(item.id).positionMs)
    }

    private companion object {
        /** Long enough for the 3s settle window plus one 2s service tick. */
        const val SETTLE_WAIT = 6_000L
    }
}
