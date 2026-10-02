package com.daview.server.update

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An updater that gets a version comparison wrong replaces a build with an
 * older one; one that skips the digest hands whatever the network delivered
 * to an installer. Both are checked here against a server held in the test.
 */
class UpdateServiceTest {

    private val dir = createTempDirectory("daview-update-test")
    private val service = UpdateService(dir)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @AfterTest
    fun tearDown() = server.stop(0)

    private fun serve(path: String, bytes: ByteArray, status: Int = 200) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `package versions compare field by field`() {
        assertEquals(PackageVersion(1, 2, 130), PackageVersion.parse("v1.2.130"))
        assertNull(PackageVersion.parse("1.2"))
        assertNull(PackageVersion.parse("v1.2.0-5-gabc123"))
        assertTrue(PackageVersion(1, 2, 130) > PackageVersion(1, 2, 129))
        assertTrue(PackageVersion(1, 3, 0) > PackageVersion(1, 2, 999))
        assertTrue(PackageVersion(2, 0, 0) > PackageVersion(1, 99, 99))
    }

    @Test
    fun `a manifest with fields it does not know still parses`() {
        serve(
            "/update.json",
            """
            {"version":"v1.2.0","packageVersion":"1.2.140","future":true,
             "assets":{"windows":{"url":"https://example/DAView.msi","extra":1}}}
            """.trimIndent().toByteArray()
        )
        server.start()

        val manifest = service.fetchManifest(url("/update.json"))
        assertEquals("v1.2.0", manifest.version)
        assertEquals("https://example/DAView.msi", manifest.assets["windows"]?.url)
        assertTrue(service.isNewer(manifest, "1.2.139"))
        assertFalse(service.isNewer(manifest, "1.2.140"), "the same build is not an update")
        assertFalse(service.isNewer(manifest, "2.0.0"), "an older manifest is not an update")
        assertFalse(service.isNewer(manifest, "dev"), "a build with no version is never replaced on a guess")
    }

    @Test
    fun `a missing manifest is an error, not an empty update`() {
        serve("/gone", ByteArray(0), status = 404)
        server.start()
        val error = assertFailsWith<IOException> { service.fetchManifest(url("/gone")) }
        assertTrue("404" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a download is checked against its digest and lands whole or not at all`() {
        val bytes = ByteArray(3_000_000) { it.toByte() }
        serve("/DAView.apk", bytes)
        server.start()

        val reports = mutableListOf<Pair<Long, Long?>>()
        val file = service.download(
            UpdateAsset(url("/DAView.apk"), sha256 = sha256(bytes).uppercase(), size = bytes.size.toLong()),
            "DAView.apk"
        ) { received, total -> reports += received to total }
        assertEquals("DAView.apk", file.name)
        assertEquals(bytes.size.toLong(), Files.size(file))
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), reports.last())
        assertTrue(reports.size > 2, "progress is reported along the way, not only at the end")
        assertFalse(dir.resolve("updates/DAView.apk.part").exists())

        // Fetched again with the right digest already on disk: nothing is downloaded.
        val again = service.download(UpdateAsset(url("/nowhere"), sha256 = sha256(bytes)), "DAView.apk")
        assertEquals(file, again)

        val error = assertFailsWith<IOException> {
            service.download(UpdateAsset(url("/DAView.apk"), sha256 = "00"), "bad.apk")
        }
        assertTrue("SHA-256" in error.message.orEmpty(), error.message)
        assertFalse(dir.resolve("updates/bad.apk").exists(), "a file that failed its check is not left behind")
        assertFalse(dir.resolve("updates/bad.apk.part").exists())

        service.clean(keep = file)
        assertEquals(listOf("DAView.apk"), dir.resolve("updates").listDirectoryEntries().map { it.name })
        service.clean()
        assertTrue(dir.resolve("updates").listDirectoryEntries().isEmpty())
    }

    @Test
    fun `a package the manifest gives no digest for is not downloaded`() {
        serve("/DAView.msi", ByteArray(16))
        server.start()

        val error = assertFailsWith<IOException> {
            service.download(UpdateAsset(url("/DAView.msi")), "DAView.msi")
        }
        assertTrue("SHA-256" in error.message.orEmpty(), error.message)
        assertFalse(dir.resolve("updates/DAView.msi").exists())
    }

    @Test
    fun `a name with a path in it still lands inside the updates directory`() {
        val bytes = ByteArray(1024) { it.toByte() }
        serve("/pkg", bytes)
        server.start()

        val file = service.download(
            UpdateAsset(url("/pkg"), sha256 = sha256(bytes)),
            "..\\..\\Start Menu\\Programs\\Startup\\run&me%.cmd"
        )
        assertEquals(dir.resolve("updates"), file.parent)
        assertEquals("run_me_.cmd", file.name)

        assertEquals("DAView-v1.2.0-5-gabc123.msi", UpdateService.safeFileName("DAView-v1.2.0-5-gabc123.msi"))
        assertEquals("passwd", UpdateService.safeFileName("../../etc/passwd"))
        assertEquals("DAView-update", UpdateService.safeFileName("../"))
        assertEquals("hidden", UpdateService.safeFileName(".hidden"))
    }

    @Test
    fun `a body larger than the manifest says is cut off, not written out`() {
        val bytes = ByteArray(2_000_000) { it.toByte() }
        serve("/big.msi", bytes)
        server.start()

        val error = assertFailsWith<IOException> {
            service.download(UpdateAsset(url("/big.msi"), sha256 = sha256(bytes), size = 1_000), "big.msi")
        }
        assertTrue("1000" in error.message.orEmpty(), error.message)
        assertFalse(dir.resolve("updates/big.msi").exists())
        assertFalse(dir.resolve("updates/big.msi.part").exists())
    }

    @Test
    fun `a mirror goes in front of the whole address`() {
        assertEquals(
            "https://mirror.example/https://github.com/x/y",
            UpdateService.mirrored("https://github.com/x/y", "https://mirror.example/")
        )
        assertEquals("https://github.com/x/y", UpdateService.mirrored("https://github.com/x/y", "  "))
    }
}
