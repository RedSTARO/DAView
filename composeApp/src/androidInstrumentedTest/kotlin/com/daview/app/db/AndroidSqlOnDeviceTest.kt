package com.daview.app.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daview.server.db.AndroidSqlDatabase
import com.daview.server.db.Database
import com.daview.server.db.Repository
import com.daview.server.db.SqlConnection
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.UserDataDto
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidSqlOnDeviceTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sql-${UUID.randomUUID()}")
    private val opened = mutableListOf<AndroidSqlDatabase>()
    private fun open() = AndroidSqlDatabase(dir).also { opened += it }

    @After
    fun close() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    @Test
    fun freshSchemaSupportsUpsertsAndRevisionTriggers() {
        Database(open()).use { database ->
            val repo = Repository(database)
            val library = LibraryDto("lib", "Films", LibraryKind.MOVIE, "/Films", updatedAt = 100)
            repo.upsertLibrary(library)
            repo.upsertLibrary(library.copy(name = "Renamed"))
            repo.restoreUserData("ep", UserDataDto(positionMs = 10), 100)
            val before = repo.syncFingerprint()
            repo.restoreUserData("ep", UserDataDto(positionMs = 20), 100)
            assertTrue(repo.syncFingerprint() > before)
            assertEquals(20L, repo.userData("ep").positionMs)
            val after = repo.syncFingerprint()
            repo.restoreUserData("ep", UserDataDto(positionMs = 20), 100)
            assertEquals(after, repo.syncFingerprint())
            database.read { connection ->
                connection.statement("PRAGMA integrity_check").useQuery {
                    assertTrue(it.next())
                    assertEquals("ok", it.getStringAt(1))
                }
            }
        }
    }

    @Test
    fun bindingsBatchesAndAffectedRowsKeepTheirTypes() {
        open().use { sql ->
            sql.transaction { c ->
                c.exec("CREATE TABLE sample (id INTEGER PRIMARY KEY, name TEXT, amount REAL, big INTEGER)")
                c.statement("INSERT INTO sample VALUES (?, ?, ?, ?)").use { statement ->
                    statement.setInt(1, 1)
                    statement.setString(2, "A+中文")
                    statement.setDouble(3, 1.25)
                    statement.setLong(4, 4_000_000_000)
                    statement.addBatch()
                    statement.setInt(1, 2)
                    statement.setString(2, null)
                    statement.setNull(3)
                    statement.setNull(4)
                    statement.addBatch()
                    statement.executeBatch()
                }
                assertEquals(0, c.exec("INSERT OR IGNORE INTO sample(id) VALUES (1)"))
                assertEquals(1, c.exec("INSERT INTO sample(id, name) VALUES (1, 'updated') ON CONFLICT(id) DO UPDATE SET name=excluded.name"))
                assertEquals(0, c.exec("CREATE TABLE extra (id INTEGER)"))
                c.statement("SELECT * FROM sample ORDER BY id").useQuery {
                    assertTrue(it.next())
                    assertEquals("updated", it.getString("NAME"))
                    assertEquals(1.25, it.getDoubleOrNull("amount")!!, 0.0)
                    assertEquals(4_000_000_000, it.getLong("big"))
                    assertEquals(1, it.getIntAt(1))
                    assertTrue(it.next())
                    assertNull(it.getString("name"))
                    assertNull(it.getDoubleOrNull("amount"))
                    assertNull(it.getLongOrNull("big"))
                    assertNull(it.getIntOrNull("big"))
                    assertEquals(0L, it.getLong("big"))
                    assertFalse(it.next())
                    assertFalse(it.next())
                }
                c.statement("SELECT ? AS text, ? AS number, ? IS NULL AS absent").use {
                    it.setString(1, "A+中文")
                    it.setDouble(2, 2.5)
                    it.setNull(3)
                    it.useQuery { row ->
                        assertTrue(row.next())
                        assertEquals("A+中文", row.getStringAt(1))
                        assertEquals(2.5, row.getDoubleOrNull("number")!!, 0.0)
                        assertEquals(1, row.getInt("absent"))
                    }
                }
                assertEquals(2, c.exec("DELETE FROM sample"))
            }
        }
    }

    @Test
    fun rollbackIncludesNestedWritesAndRevision() {
        Database(open()).use { database ->
            val repo = Repository(database)
            val before = repo.syncFingerprint()
            assertThrows(IllegalStateException::class.java) {
                database.transaction {
                    repo.restoreUserData("ep", UserDataDto(positionMs = 10), 100)
                    database.read { database.read { assertEquals(10L, repo.userData("ep").positionMs) } }
                    error("roll back the outer transaction")
                }
            }
            assertEquals(before, repo.syncFingerprint())
            assertEquals(0L, repo.userData("ep").positionMs)
            repo.restoreUserData("ep", UserDataDto(positionMs = 20), 200)
            assertEquals(20L, repo.userData("ep").positionMs)
        }
    }

    @Test
    fun caughtNestedFailureStillPreventsOuterCommit() {
        open().use { sql ->
            sql.transaction { it.exec("CREATE TABLE sample (id INTEGER)") }
            assertThrows(IllegalStateException::class.java) {
                sql.transaction { c ->
                    c.exec("INSERT INTO sample VALUES (1)")
                    runCatching { sql.transaction { error("inner failure") } }
                }
            }
            sql.read { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun otherThreadsCannotObserveRolledBackWrites() {
        open().use { sql ->
            sql.transaction { it.exec("CREATE TABLE sample (id INTEGER)") }
            val executor = Executors.newSingleThreadExecutor()
            val requested = CountDownLatch(1)
            val readFinished = CountDownLatch(1)
            try {
                lateinit var reader: java.util.concurrent.Future<Long>
                assertThrows(IllegalStateException::class.java) {
                    sql.transaction { c ->
                        c.exec("INSERT INTO sample VALUES (1)")
                        reader = executor.submit<Long> {
                            requested.countDown()
                            sql.read { it.count() }.also { readFinished.countDown() }
                        }
                        assertTrue(requested.await(5, TimeUnit.SECONDS))
                        assertFalse(readFinished.await(150, TimeUnit.MILLISECONDS))
                        error("rollback")
                    }
                }
                assertEquals(0L, reader.get(5, TimeUnit.SECONDS).toLong())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun platformDatabaseAndWalUpgradeWithoutLosingUserData() {
        Database(open()).use { database ->
            val repo = Repository(database)
            repo.restoreUserData("ep", UserDataDto(positionMs = 123, favorite = true), 100)
            database.transaction { c ->
                for (table in listOf("user_data", "libraries", "scrape_pins")) {
                    for (kind in listOf("insert", "delete", "update")) c.exec("DROP TRIGGER sync_${table}_$kind")
                }
                c.exec("DROP TABLE sync_revision")
                c.exec("UPDATE schema_version SET version = 17")
            }
        }
        // Leave a platform WAL open while the new driver reads it. This also
        // covers an upgrade after the old process stopped before checkpointing.
        SQLiteDatabase.openOrCreateDatabase(File(dir, "daview.db"), null).use { platform ->
            platform.enableWriteAheadLogging()
            platform.execSQL("UPDATE user_data SET position_ms = 456 WHERE item_id = 'ep'")
            Database(open()).use { database ->
                val repo = Repository(database)
                assertEquals(456L, repo.userData("ep").positionMs)
                assertTrue(repo.userData("ep").favorite)
                val before = repo.syncFingerprint()
                repo.restoreUserData("ep", repo.userData("ep").copy(positionMs = 789), 100)
                assertTrue(repo.syncFingerprint() > before)
            }
        }
        Database(open()).use { database ->
            assertEquals(789L, Repository(database).userData("ep").positionMs)
        }
    }

    @Test
    fun closeIsIdempotentAndCannotReopenTheConnection() {
        val sql = open()
        sql.close()
        sql.close()
        assertThrows(IllegalStateException::class.java) { sql.read { } }
        assertThrows(IllegalStateException::class.java) { sql.transaction { } }
    }

    private fun SqlConnection.exec(sql: String): Int = statement(sql).use { it.executeUpdate() }
    private fun SqlConnection.count(): Long = statement("SELECT count(*) FROM sample").useQuery {
        check(it.next())
        it.getLongAt(1)
    }
}
