package com.daview.server.storage

import com.daview.server.config.StorageConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val dav = WebDavClient(StorageConfig(url = "$base/dav", username = "user", password = "secret"))

    @AfterTest
    fun close() = server.stop(0)

    private fun respond(path: String, body: String, code: Int = 200, chunked: Boolean = false) {
        server.createContext(path) { exchange ->
            try {
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(code, if (chunked) 0 else bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } finally {
                exchange.close()
            }
        }
    }

    private fun redirect(path: String, location: String) {
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Location", location)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
    }

    private fun multistatus(href: String, status: String = "200 OK") = """
        <d:multistatus xmlns:d="DAV:"><d:response><d:href>$href</d:href>
        <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>5</d:getcontentlength></d:prop>
        <d:status>HTTP/1.1 $status</d:status></d:propstat></d:response></d:multistatus>
    """.trimIndent()

    @Test
    fun `DAV href preserves literal plus and decodes percent encoding once`() {
        respond("/dav/", multistatus("/dav/A+B%20%252B.mkv"), 207)
        val entry = dav.list("/").single()
        assertEquals("/A+B %2B.mkv", entry.path)
        assertEquals("$base/dav/A%2BB%20%252B.mkv", dav.absoluteUrl(entry.path))
    }

    @Test
    fun `an HTML success page is not an empty directory`() {
        respond("/dav/", "<html><body>Sign in</body></html>")
        assertFailsWith<WebDavException> { dav.list("/Show") }
    }

    @Test
    fun `a failed property response must not become a complete listing`() {
        respond("/dav/", multistatus("/dav/Show/Season%2001/", "403 Forbidden"), 207)
        assertFailsWith<WebDavException> { dav.list("/Show") }
    }

    @Test
    fun `a failed self response is not an empty directory`() {
        respond("/dav/", multistatus("/dav/Show/", "403 Forbidden"), 207)
        assertFailsWith<WebDavException> { dav.list("/Show") }
    }

    @Test
    fun `a valid empty multistatus is allowed`() {
        respond("/dav/", """<d:multistatus xmlns:d="DAV:"/>""", 207)
        assertEquals(emptyList(), dav.list("/Show"))
    }

    @Test
    fun `a name without a readable resource type cannot turn a directory into a file`() {
        respond("/dav/", """
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/Show/</d:href>
            <d:propstat><d:prop><d:displayname>Show</d:displayname></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            <d:propstat><d:prop><d:resourcetype/></d:prop>
            <d:status>HTTP/1.1 403 Forbidden</d:status></d:propstat>
            </d:response></d:multistatus>
        """.trimIndent(), 207)
        assertFailsWith<WebDavException> { dav.list("/") }
    }

    @Test
    fun `a full response over the byte limit is an error not a partial file`() {
        respond("/dav/sync", "123456789")
        val result = dav.read("/sync", limit = 8)
        assertNull(result.bytes)
        assertNotNull(result.error)
        assertFalse(result.missing)
    }

    @Test
    fun `a chunked response is bounded too`() {
        respond("/dav/sync", "123456789", chunked = true)
        val result = dav.read("/sync", limit = 8)
        assertNull(result.bytes)
        assertNotNull(result.error)
        assertFalse(result.missing)
    }

    @Test
    fun `exact byte limit succeeds including multibyte text`() {
        val body = "中文+"
        respond("/dav/sync", body, chunked = true)
        val result = dav.read("/sync", limit = body.toByteArray().size.toLong())
        assertContentEquals(body.toByteArray(), result.bytes)
        assertNull(result.error)
    }

    @Test
    fun `a total call timeout aborts a stalled body read after a redirect`() {
        val releaseBody = CountDownLatch(1)
        redirect("/dav/sync", "/cdn/sync")
        server.createContext("/cdn/sync") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write('a'.code)
                exchange.responseBody.flush()
                releaseBody.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        val started = System.nanoTime()
        try {
            val result = dav.read("/sync", callTimeoutMs = 200)
            assertNull(result.bytes)
            assertNotNull(result.error)
            assertFalse(result.missing)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000,
                "the body read used the client's 120 second read timeout")
        } finally { releaseBody.countDown() }
    }

    @Test
    fun `relative redirect reads the complete file`() {
        redirect("/dav/sync", "../cdn/sync")
        respond("/cdn/sync", "hello")
        assertContentEquals("hello".toByteArray(), dav.read("/sync", limit = 5).bytes)
    }

    @Test
    fun `redirect cannot turn a ranged prefix into a complete sync file`() {
        redirect("/dav/sync", "$base/cdn/sync")
        server.createContext("/cdn/sync") { exchange ->
            try {
                val range = exchange.requestHeaders.getFirst("Range")
                val body = if (range != null) "12345678" else "123456789"
                if (range != null) exchange.responseHeaders.add("Content-Range", "bytes 0-7/9")
                exchange.sendResponseHeaders(if (range != null) 206 else 200, body.length.toLong())
                exchange.responseBody.write(body.toByteArray())
            } finally { exchange.close() }
        }
        val result = dav.read("/sync", limit = 8)
        assertNull(result.bytes)
        assertNotNull(result.error)
    }

    @Test
    fun `relative media redirect is resolved against the requested URL`() {
        redirect("/dav/video", "../cdn/video?token=abc")
        assertEquals("$base/cdn/video?token=abc", dav.resolveDirectUrl("/video"))
    }

    @Test
    fun `HEAD errors do not report the size of the error page`() {
        server.createContext("/dav/video") { exchange ->
            exchange.responseHeaders.add("Content-Length", "999")
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        assertNull(dav.size("/video"))
    }

    @Test
    fun `a server ignoring a resumed range cannot append the entire file`() {
        respond("/dav/video", "0123456789")
        assertFailsWith<WebDavException> { dav.openRangeAt("$base/dav/video", 5, null, true).use { } }
    }

    @Test
    fun `a wrong partial offset is rejected`() {
        server.createContext("/dav/video") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-4/10")
            exchange.sendResponseHeaders(206, 5)
            exchange.responseBody.write("01234".toByteArray())
            exchange.close()
        }
        assertFailsWith<WebDavException> { dav.openRangeAt("$base/dav/video", 5, 9, true).use { } }
    }

    @Test
    fun `unknown range total is not replaced with the fragment length`() {
        server.createContext("/dav/video") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 5-9/*")
            exchange.sendResponseHeaders(206, 5)
            exchange.responseBody.write("56789".toByteArray())
            exchange.close()
        }
        dav.openRangeAt("$base/dav/video", 5, 9, true).use {
            assertNull(it.totalSize)
            assertContentEquals("56789".toByteArray(), it.stream.readBytes())
        }
    }

    @Test
    fun `a missing redirected object is an error rather than permission to replace the DAV file`() {
        redirect("/dav/sync", "/cdn/sync")
        respond("/cdn/sync", "expired", 404)
        val result = dav.read("/sync", 8)
        assertFalse(result.missing)
        assertNotNull(result.error)
    }

    @Test
    fun `a same origin redirect keeps credentials`() {
        redirect("/dav/sync", "/dav/actual")
        var authorization: String? = null
        server.createContext("/dav/actual") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.write("{}".toByteArray())
            exchange.close()
        }
        assertContentEquals("{}".toByteArray(), dav.read("/sync", 8).bytes)
        assertNotNull(authorization)
    }

    @Test
    fun `credentials are not forwarded to a different origin`() {
        val cdn = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorization: String? = "not-requested"
        cdn.createContext("/sync") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.write("{}".toByteArray())
            exchange.close()
        }
        cdn.start()
        try {
            redirect("/dav/sync", "http://127.0.0.1:${cdn.address.port}/sync")
            assertContentEquals("{}".toByteArray(), dav.read("/sync", 8).bytes)
            assertNull(authorization)
        } finally { cdn.stop(0) }
    }
}
