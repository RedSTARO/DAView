package com.daview.server.sync

import com.daview.server.ServerContext
import com.daview.server.api.BackupOptions
import com.daview.server.api.applyBackup
import com.daview.server.api.buildBackup
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MetadataProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The parts of the sync file that are not watch state: library definitions and
 * settings. Neither carried a timestamp, so every pull laid the file's copy
 * over this device's, and whatever a person had just changed here was put back
 * within minutes — by this device's own earlier upload as readily as by
 * another device's.
 */
class SyncDefinitionsTest {

    private val deviceA = ServerContext(createTempDirectory("daview-sync-def-a"))
    private val deviceB = ServerContext(createTempDirectory("daview-sync-def-b"))

    @AfterTest
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    private val syncSections = BackupOptions(
        settings = true, libraries = true, items = false, userData = true, pins = true, secrets = false
    )

    private fun syncFile(context: ServerContext): BackupFileDto =
        DaViewJson.decodeFromString(BackupFileDto.serializer(), buildBackup(context, syncSections))

    /** What the sync loop does with a file it has read. */
    private fun pull(into: ServerContext, file: BackupFileDto) =
        applyBackup(into, file, mergeUserDataByTimestamp = true, machineLocal = true)

    private fun library(name: String, updatedAt: Long = 0, lastScanAt: Long? = null) = LibraryDto(
        id = "lib-ani", name = name, kind = LibraryKind.ANIME, path = "/Ani",
        providerOrder = listOf(MetadataProvider.BANGUMI), language = "",
        lastScanAt = lastScanAt, updatedAt = updatedAt
    )

    @Test
    fun `a pull does not undo a change made to a library here`() {
        deviceA.repository.upsertLibrary(library("番剧"))
        val uploadedEarlier = syncFile(deviceA)

        // Renamed, with a different scraping order, the way the edit dialog saves it.
        deviceA.repository.upsertLibrary(
            library("动画", updatedAt = 200).copy(providerOrder = listOf(MetadataProvider.TMDB, MetadataProvider.BANGUMI))
        )
        pull(deviceA, uploadedEarlier)

        val kept = assertNotNull(deviceA.repository.library("lib-ani"))
        assertEquals("动画", kept.name)
        assertEquals(listOf(MetadataProvider.TMDB, MetadataProvider.BANGUMI), kept.providerOrder)
    }

    @Test
    fun `neither side having edited a library leaves this device's copy as it is`() {
        deviceA.repository.upsertLibrary(library("番剧"))
        deviceB.repository.upsertLibrary(library("Anime"))

        val summary = pull(deviceA, syncFile(deviceB))

        assertEquals("番剧", deviceA.repository.library("lib-ani")?.name)
        assertEquals(0, summary.libraries, "nothing was applied, so nothing is counted")
    }

    @Test
    fun `a newer definition from another device is taken, and this device's scan time is kept`() {
        deviceA.repository.upsertLibrary(library("番剧", updatedAt = 100, lastScanAt = 555))
        deviceB.repository.upsertLibrary(library("动画", updatedAt = 300, lastScanAt = 999))

        pull(deviceA, syncFile(deviceB))

        val merged = assertNotNull(deviceA.repository.library("lib-ani"))
        assertEquals("动画", merged.name)
        assertEquals(300, merged.updatedAt)
        assertEquals(555, merged.lastScanAt, "when another device scanned says nothing about this one")
    }

    @Test
    fun `a library this device has never had arrives as one it has never scanned`() {
        deviceB.repository.upsertLibrary(library("番剧", lastScanAt = 999))

        val summary = pull(deviceA, syncFile(deviceB))

        val arrived = assertNotNull(deviceA.repository.library("lib-ani"))
        assertNull(arrived.lastScanAt)
        assertEquals(1, summary.libraries)
    }

    @Test
    fun `the sync loop leaves this device's settings alone, and a restore sets them`() {
        deviceA.updateConfig { it.copy(scraper = it.scraper.copy(language = "ja-JP")) }
        deviceB.updateConfig { it.copy(serverName = "Phone", scraper = it.scraper.copy(language = "en-US")) }
        val fromB = syncFile(deviceB)

        pull(deviceA, fromB)
        assertEquals("ja-JP", deviceA.config.scraper.language)
        assertEquals("DAView", deviceA.config.serverName)

        // The same file chosen by hand is a migration, and carries them across.
        applyBackup(deviceA, fromB)
        assertEquals("en-US", deviceA.config.scraper.language)
        assertEquals("Phone", deviceA.config.serverName)
    }

    @Test
    fun `a library deleted here stays deleted through a pull and comes back with a restore`() {
        deviceA.repository.upsertLibrary(library("番剧"))
        val file = syncFile(deviceA)
        deviceA.repository.deleteLibrary("lib-ani")

        pull(deviceA, file)
        assertNull(deviceA.repository.library("lib-ani"), "another device not having caught up is not a reason")

        // Choosing the file by hand is putting the library back.
        val summary = applyBackup(deviceA, file)
        assertNotNull(deviceA.repository.library("lib-ani"))
        assertEquals(1, summary.libraries)

        // And it is not taken away again by the tombstone the delete left.
        pull(deviceA, file)
        assertNotNull(deviceA.repository.library("lib-ani"))
    }

    @Test
    fun `changing a library or a pin is something to upload, not only watching something`() {
        deviceA.repository.upsertLibrary(library("番剧"))
        val before = deviceA.repository.syncFingerprint()

        deviceA.repository.upsertLibrary(library("动画", updatedAt = 200))
        val afterEdit = deviceA.repository.syncFingerprint()
        assertNotEquals(before, afterEdit)

        deviceA.repository.savePin("item-1", MetadataProvider.BANGUMI.name, "49278", updatedAt = 300)
        assertNotEquals(afterEdit, deviceA.repository.syncFingerprint())
    }
}
