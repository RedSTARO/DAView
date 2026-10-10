package com.daview.server.sync

import com.daview.server.ServerContext
import com.daview.server.api.applyBackup
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.SqlConnection
import com.daview.server.db.SqlDatabase
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.BackupPinDto
import com.daview.shared.model.BackupSummaryDto
import com.daview.shared.model.BackupUserDataDto
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.UserDataDto
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live writes must be compared after they commit, not overwritten using a stale WAL reader. */
class SyncConcurrentMergeTest {
    private val dir = createTempDirectory("daview-sync-concurrent")
    private val sql = SignallingSqlDatabase(JdbcSqlDatabase(dir))
    private val context = ServerContext(dir, sql)
    private val workers = Executors.newFixedThreadPool(2)

    @AfterTest
    fun close() {
        workers.shutdownNow()
        workers.awaitTermination(2, TimeUnit.SECONDS)
        context.close()
    }

    private fun library(name: String, at: Long, scannedAt: Long? = 555) = LibraryDto(
        id = "library", name = name, kind = LibraryKind.ANIME, path = "/Ani",
        updatedAt = at, lastScanAt = scannedAt
    )

    private fun lockedItem(provider: MetadataProvider) = ItemRecord(
        MediaItemDto(
            id = "episode", libraryId = "library", kind = ItemKind.EPISODE,
            name = "Episode", lockedProvider = provider
        )
    )

    /**
     * Hold a newer local write uncommitted while merge starts. Before the fix,
     * merge's read used a separate WAL reader, saw the older committed row, and
     * only then waited for the writer before replacing the newer local row.
     * Now merge waits for its row transaction before making the comparison.
     * The driver's signal observes the write-lock attempt without sleeps or
     * changes to production code.
     */
    private fun mergeWithPendingLocalWrite(
        backup: BackupFileDto,
        localChange: () -> Unit
    ): BackupSummaryDto {
        val localWritten = CountDownLatch(1)
        val releaseLocal = CountDownLatch(1)
        val local = workers.submit(Callable {
            context.database.transaction {
                localChange()
                localWritten.countDown()
                check(releaseLocal.await(5, TimeUnit.SECONDS))
            }
        })
        try {
            assertTrue(localWritten.await(2, TimeUnit.SECONDS), "local change never reached the writer")
            val merge = workers.submit(Callable {
                sql.mergeThread = Thread.currentThread()
                applyBackup(context, backup, mergeUserDataByTimestamp = true, machineLocal = true)
            })
            assertTrue(sql.mergeTransactionAttempt.await(2, TimeUnit.SECONDS), "merge never attempted its transaction")
            releaseLocal.countDown()
            local.get(2, TimeUnit.SECONDS)
            return merge.get(2, TimeUnit.SECONDS)
        } finally {
            releaseLocal.countDown()
        }
    }

    @Test
    fun `sync cannot overwrite newer live progress and favourite written during its merge`() {
        context.repository.restoreUserData("episode", UserDataDto(positionMs = 1_000), 100)
        val file = BackupFileDto(userData = listOf(
            BackupUserDataDto("episode", UserDataDto(positionMs = 2_000, favorite = false), 200)
        ))
        val summary = mergeWithPendingLocalWrite(file) {
            context.repository.saveProgress("episode", 9_000, 100_000, null, null)
            context.repository.setFavorite("episode", true)
        }
        val kept = context.repository.userData("episode")
        assertEquals(9_000L, kept.positionMs)
        assertTrue(kept.favorite)
        assertEquals(0, summary.userData)
    }

    @Test
    fun `sync cannot undo a newer local rename that commits during its merge`() {
        context.repository.upsertLibrary(library("Old", 100))
        val summary = mergeWithPendingLocalWrite(BackupFileDto(libraries = listOf(library("Remote", 200)))) {
            context.repository.upsertLibrary(library("Local rename", 300, 777))
        }
        val kept = assertNotNull(context.repository.library("library"))
        assertEquals("Local rename", kept.name)
        assertEquals(300L, kept.updatedAt)
        assertEquals(777L, kept.lastScanAt)
        assertEquals(0, summary.libraries)
    }

    @Test
    fun `sync preserves a scan finishing while a newer remote definition is merging`() {
        context.repository.upsertLibrary(library("Old", 100))
        val summary = mergeWithPendingLocalWrite(BackupFileDto(libraries = listOf(library("Remote", 200, 999)))) {
            context.repository.markScanned("library", 777)
        }
        val kept = assertNotNull(context.repository.library("library"))
        assertEquals("Remote", kept.name)
        assertEquals(200L, kept.updatedAt)
        assertEquals(777L, kept.lastScanAt)
        assertEquals(1, summary.libraries)
    }

    @Test
    fun `a deletion committing during sync cannot resurrect the library`() {
        context.repository.upsertLibrary(library("Old", 100))
        val summary = mergeWithPendingLocalWrite(BackupFileDto(libraries = listOf(library("Remote", 200)))) {
            context.repository.deleteLibrary("library")
        }
        assertNull(context.repository.library("library"))
        assertTrue(context.repository.isLibraryDeleted("library"))
        assertEquals(0, summary.libraries)
    }

    @Test
    fun `remote unpin cannot overwrite or unlock a newer local pin committing during sync`() {
        context.repository.upsertLibrary(library("Library", 100))
        context.repository.upsertItem(lockedItem(MetadataProvider.BANGUMI))
        context.repository.savePin("episode", MetadataProvider.BANGUMI.name, "old", 100)
        val file = BackupFileDto(pins = listOf(BackupPinDto("episode", MetadataProvider.NONE, "", 200)))
        val summary = mergeWithPendingLocalWrite(file) {
            context.repository.savePin("episode", MetadataProvider.TMDB.name, "new", 300)
            context.repository.upsertItem(lockedItem(MetadataProvider.TMDB))
        }
        val pin = assertNotNull(context.repository.pin("episode"))
        assertEquals(MetadataProvider.TMDB.name, pin.provider)
        assertEquals("new", pin.providerId)
        assertEquals(300L, pin.updatedAt)
        assertEquals(MetadataProvider.TMDB, context.repository.item("episode")?.lockedProvider)
        assertEquals(0, summary.pins)
    }

    @Test
    fun `an item unlock failure rolls back its paired pin tombstone`() {
        context.repository.upsertLibrary(library("Library", 100))
        context.repository.upsertItem(lockedItem(MetadataProvider.BANGUMI))
        context.repository.savePin("episode", MetadataProvider.BANGUMI.name, "old", 100)
        context.database.transaction { connection ->
            connection.statement(
                "CREATE TRIGGER reject_lock_clear BEFORE UPDATE OF locked_provider ON items " +
                    "WHEN NEW.locked_provider IS NULL BEGIN SELECT RAISE(ABORT, 'lock clear failed'); END"
            ).use { it.executeUpdate() }
        }
        assertFails {
            applyBackup(
                context,
                BackupFileDto(pins = listOf(BackupPinDto("episode", MetadataProvider.NONE, "", 200))),
                mergeUserDataByTimestamp = true,
                machineLocal = true
            )
        }
        val kept = assertNotNull(context.repository.pin("episode"))
        assertEquals(MetadataProvider.BANGUMI.name, kept.provider)
        assertEquals("old", kept.providerId)
        assertEquals(100L, kept.updatedAt)
        assertEquals(MetadataProvider.BANGUMI, context.repository.item("episode")?.lockedProvider)
    }

    @Test
    fun `a manual restore still replaces newer local watch state and revives deleted libraries`() {
        context.repository.restoreUserData("episode", UserDataDto(positionMs = 9_000, favorite = true), 300)
        context.repository.upsertLibrary(library("Local", 300))
        context.repository.deleteLibrary("library")
        val summary = applyBackup(context, BackupFileDto(
            userData = listOf(BackupUserDataDto("episode", UserDataDto(positionMs = 2_000), 200)),
            libraries = listOf(library("Restored", 200))
        ))
        assertEquals(2_000L, context.repository.userData("episode").positionMs)
        assertEquals(false, context.repository.userData("episode").favorite)
        assertEquals("Restored", context.repository.library("library")?.name)
        assertEquals(false, context.repository.isLibraryDeleted("library"))
        assertEquals(1, summary.userData)
        assertEquals(1, summary.libraries)
    }

    private class SignallingSqlDatabase(private val delegate: SqlDatabase) : SqlDatabase {
        @Volatile var mergeThread: Thread? = null
        val mergeTransactionAttempt = CountDownLatch(1)

        override fun <T> read(block: (SqlConnection) -> T): T = delegate.read(block)

        override fun <T> transaction(block: (SqlConnection) -> T): T {
            if (Thread.currentThread() === mergeThread) mergeTransactionAttempt.countDown()
            return delegate.transaction(block)
        }

        override fun close() = delegate.close()
    }
}
