package com.daview.server.db

import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.UserDataDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SyncRevisionTest {
    private val dir = createTempDirectory("daview-sync-revision")
    private var database = Database(JdbcSqlDatabase(dir))
    private var repository = Repository(database)

    @AfterTest
    fun close() { database.close(); dir.toFile().deleteRecursively() }

    @Test
    fun `editing a non-maximum row changes the token`() {
        repository.restoreUserData("future", UserDataDto(positionMs = 10), 1000)
        repository.restoreUserData("edited", UserDataDto(positionMs = 20), 100)
        val before = repository.syncFingerprint()
        val oldSummary = repository.userDataFingerprint()
        repository.restoreUserData("edited", UserDataDto(positionMs = 30), 200)
        assertEquals(oldSummary, repository.userDataFingerprint(), "MAX plus COUNT cannot detect this edit")
        assertNotEquals(before, repository.syncFingerprint())
    }

    @Test
    fun `same-timestamp edits and edit-undo sequences change the token`() {
        val original = UserDataDto(positionMs = 10)
        repository.restoreUserData("ep", original, 100)
        val before = repository.syncFingerprint()
        repository.restoreUserData("ep", original.copy(favorite = true), 100)
        val edited = repository.syncFingerprint()
        assertNotEquals(before, edited)
        repository.restoreUserData("ep", original, 100)
        assertNotEquals(before, repository.syncFingerprint())
        assertNotEquals(edited, repository.syncFingerprint())
    }

    @Test
    fun `definitions and pins are tracked but scan timestamps and identical writes are not`() {
        val library = LibraryDto("lib", "Films", LibraryKind.MOVIE, "/Films", updatedAt = 100)
        repository.upsertLibrary(library)
        repository.savePin("ep", MetadataProvider.TMDB.name, "1", 100)
        val before = repository.syncFingerprint()
        repository.markScanned("lib", 9000)
        repository.upsertLibrary(library.copy(lastScanAt = 9000))
        repository.savePin("ep", MetadataProvider.TMDB.name, "1", 100)
        assertEquals(before, repository.syncFingerprint())
        repository.upsertLibrary(library.copy(name = "Renamed"))
        val renamed = repository.syncFingerprint()
        assertNotEquals(before, renamed)
        repository.savePin("ep", MetadataProvider.TMDB.name, "2", 100)
        assertNotEquals(renamed, repository.syncFingerprint())
    }

    @Test
    fun `a rolled back edit also rolls back its revision`() {
        repository.restoreUserData("ep", UserDataDto(positionMs = 10), 100)
        val before = repository.syncFingerprint()
        assertFailsWith<IllegalStateException> {
            database.transaction { connection ->
                connection.statement("UPDATE user_data SET position_ms = 99 WHERE item_id = 'ep'").use { it.executeUpdate() }
                assertNotEquals(before, repository.syncFingerprint())
                error("rollback")
            }
        }
        assertEquals(before, repository.syncFingerprint())
        assertEquals(10, repository.userData("ep").positionMs)
    }

    @Test
    fun `upgrade from schema 17 preserves state and revision survives reopening`() {
        val data = UserDataDto(positionMs = 123, favorite = true)
        repository.restoreUserData("ep", data, 100)
        database.transaction { connection ->
            for (table in listOf("user_data", "libraries", "scrape_pins")) {
                for (kind in listOf("insert", "delete", "update")) {
                    connection.statement("DROP TRIGGER sync_${table}_$kind").use { it.executeUpdate() }
                }
            }
            connection.statement("DROP TABLE sync_revision").use { it.executeUpdate() }
            connection.statement("UPDATE schema_version SET version = 17").use { it.executeUpdate() }
        }
        database.close()
        database = Database(JdbcSqlDatabase(dir))
        repository = Repository(database)
        assertEquals(data, repository.userData("ep"))
        val migrated = repository.syncFingerprint()
        repository.restoreUserData("ep", data.copy(positionMs = 456), 100)
        val changed = repository.syncFingerprint()
        assertNotEquals(migrated, changed)
        database.close()
        database = Database(JdbcSqlDatabase(dir))
        repository = Repository(database)
        assertEquals(changed, repository.syncFingerprint())
        assertEquals(456, repository.userData("ep").positionMs)
    }
}
