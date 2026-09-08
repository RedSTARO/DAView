package com.daview.server.sync

import com.daview.server.ServerContext
import com.daview.server.api.BackupOptions
import com.daview.server.api.applyBackup
import com.daview.server.api.buildBackup
import com.daview.server.library.Scanner
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.UserDataDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What has to hold for two devices that scan the same share independently and
 * only ever meet through one file on it.
 *
 * These are the failure modes that cost watch history rather than convenience:
 * ids that do not line up across devices, and an upload that replaces the file
 * with only what the uploading device happened to know.
 */
class SyncPayloadTest {

    private val dirA = createTempDirectory("daview-sync-a")
    private val dirB = createTempDirectory("daview-sync-b")
    private val deviceA = ServerContext(dirA)
    private val deviceB = ServerContext(dirB)

    @AfterTest
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    private fun payload(context: ServerContext): BackupFileDto =
        DaViewJson.decodeFromString(
            BackupFileDto.serializer(),
            buildBackup(context, SYNC_SECTIONS)
        )

    private val SYNC_SECTIONS = BackupOptions(
        settings = true, libraries = true, items = false, userData = true, pins = true, secrets = false
    )

    // ------------------------------------------------------------ library ids

    @Test
    fun `library id comes from the path so two devices agree without talking`() {
        assertEquals(Scanner.libraryId("/Ani"), Scanner.libraryId("/Ani"))
        assertNotEquals(Scanner.libraryId("/Ani"), Scanner.libraryId("/Movies"))
    }

    /**
     * The two devices type the path in by hand, through a folder picker that may
     * or may not keep the trailing slash. A difference there must not turn into
     * two libraries and two sets of item ids.
     */
    @Test
    fun `library id ignores trailing slashes and case`() {
        val expected = Scanner.libraryId("/Ani")
        assertEquals(expected, Scanner.libraryId("Ani"))
        assertEquals(expected, Scanner.libraryId("/Ani/"))
        assertEquals(expected, Scanner.libraryId("/ani"))
        assertEquals(expected, Scanner.libraryId("  /Ani/  "))
    }

    @Test
    fun `item ids follow from the library id, so both devices derive the same one`() {
        val library = Scanner.libraryId("/Ani")
        assertEquals(
            Scanner.itemId(library, "/Ani/Show (2015)"),
            Scanner.itemId(Scanner.libraryId("/ani/"), "/Ani/Show (2015)")
        )
    }

    // ------------------------------------------------------------ merge before upload

    /**
     * The reason [SyncService.upload] reads before it writes: `PUT` replaces the
     * whole file, so the payload it writes has to already carry what the other
     * device put there. Merging first and rebuilding is what makes that true.
     */
    @Test
    fun `merging the remote file before building the payload keeps both sides`() {
        deviceB.repository.restoreUserData("watched-on-b", UserDataDto(positionMs = 5_000), updatedAt = 100)
        val fromB = payload(deviceB)

        deviceA.repository.restoreUserData("watched-on-a", UserDataDto(positionMs = 9_000), updatedAt = 100)
        applyBackup(deviceA, fromB, mergeUserDataByTimestamp = true)

        val uploaded = payload(deviceA).userData.associateBy { it.itemId }
        assertEquals(9_000, uploaded["watched-on-a"]?.data?.positionMs)
        assertEquals(5_000, uploaded["watched-on-b"]?.data?.positionMs)
    }

    @Test
    fun `the newer side of a row wins and the older one is left alone`() {
        deviceA.repository.restoreUserData("ep", UserDataDto(positionMs = 9_000), updatedAt = 200)
        deviceB.repository.restoreUserData("ep", UserDataDto(positionMs = 1_000), updatedAt = 100)

        applyBackup(deviceA, payload(deviceB), mergeUserDataByTimestamp = true)
        assertEquals(9_000, deviceA.repository.userData("ep").positionMs)

        applyBackup(deviceB, payload(deviceA), mergeUserDataByTimestamp = true)
        assertEquals(9_000, deviceB.repository.userData("ep").positionMs)
    }

    // ------------------------------------------------------------ pins

    /**
     * A correction is a decision, not a derivation: scraping again reproduces
     * titles and artwork but never rediscovers that this folder is that entry.
     * So it travels, even though the catalogue does not.
     */
    @Test
    fun `a pinned entry travels in the sync payload`() {
        deviceA.repository.savePin("item-1", MetadataProvider.BANGUMI.name, "49278", updatedAt = 100)

        applyBackup(deviceB, payload(deviceA), mergeUserDataByTimestamp = true)

        val landed = deviceB.repository.pin("item-1")
        assertEquals(MetadataProvider.BANGUMI.name, landed?.provider)
        assertEquals("49278", landed?.providerId)
    }

    /**
     * Pins live outside `items` precisely so this works: the receiving device
     * may not have scanned yet, and the correction has to be waiting when it does.
     */
    @Test
    fun `a pin arrives even when the item it names does not exist here yet`() {
        deviceA.repository.savePin("not-scanned-here", MetadataProvider.TMDB.name, "1396", updatedAt = 100)

        applyBackup(deviceB, payload(deviceA), mergeUserDataByTimestamp = true)

        assertNull(deviceB.repository.item("not-scanned-here"))
        assertEquals("1396", deviceB.repository.pin("not-scanned-here")?.providerId)
    }

    @Test
    fun `a newer pin replaces an older one, and an older one is ignored`() {
        deviceA.repository.savePin("item-1", MetadataProvider.TMDB.name, "new", updatedAt = 200)
        deviceB.repository.savePin("item-1", MetadataProvider.TMDB.name, "old", updatedAt = 100)

        applyBackup(deviceA, payload(deviceB), mergeUserDataByTimestamp = true)
        assertEquals("new", deviceA.repository.pin("item-1")?.providerId)

        applyBackup(deviceB, payload(deviceA), mergeUserDataByTimestamp = true)
        assertEquals("new", deviceB.repository.pin("item-1")?.providerId)
    }

    /** The catalogue stays out of the sync file; the pins are the exception. */
    @Test
    fun `the sync payload carries no catalogue`() {
        deviceA.repository.savePin("item-1", MetadataProvider.TMDB.name, "1396", updatedAt = 100)
        val file = payload(deviceA)
        assertTrue(file.items.isEmpty())
        assertEquals(1, file.pins.size)
    }
}
