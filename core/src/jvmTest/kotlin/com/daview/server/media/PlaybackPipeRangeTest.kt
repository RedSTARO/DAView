package com.daview.server.media

import com.daview.server.config.StorageConfig
import com.daview.server.db.Database
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.server.storage.WebDavClient
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inspect the entire socket response so a client library cannot hide excess body bytes. */
class PlaybackPipeRangeTest {
    private val dir = createTempDirectory("daview-pipe-range-test")
    private val file = dir.resolve("movie.bin")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private var dav: WebDavClient? = null
    private val streams = StreamService({ dav }, repository)
    private val playback = PlaybackService(repository, streams) { 300 }
    private val pipe = PlaybackPipe(repository, streams, playback)
    private val servers = mutableListOf<HttpServer>()
    private val localLookups = AtomicInteger()
    private val upstreamRanges = CopyOnWriteArrayList<String>()
    private val bytes = ByteArray(1_024) { (it * 37 + it / 256).toByte() }

    init {
        repository.upsertLibrary(LibraryDto(id = "lib-range", name = "Range tests", kind = LibraryKind.MOVIE, path = "/"))
    }

    @AfterTest
    fun tearDown() {
        pipe.close()
        servers.forEach { it.stop(0) }
        database.close()
        dir.toFile().deleteRecursively()
    }

    private fun url(size: Long?): String {
        repository.upsertItem(ItemRecord(dto = MediaItemDto(
            id = "item-range", libraryId = "lib-range", kind = ItemKind.MOVIE,
            name = "Movie", path = "/movie.bin", sizeBytes = size
        )))
        return pipe.urlFor("session-range", "item-range", "movie.bin", redirect = false)
    }

    private fun localUrl(body: ByteArray = bytes, size: Long? = body.size.toLong()): String {
        Files.write(file, body)
        streams.offlineFile = {
            localLookups.incrementAndGet()
            file
        }
        return url(size)
    }

    private data class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun request(
        url: String,
        range: String? = null,
        method: String = "GET",
        extraHeaders: List<String> = emptyList()
    ): Response {
        val uri = URI(url)
        val wire = Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, uri.port), 5_000)
            socket.soTimeout = 5_000
            val head = buildString {
                append("$method ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n")
                range?.let { append("Range: $it\r\n") }
                extraHeaders.forEach { append("$it\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes()
        }
        val text = wire.toString(Charsets.ISO_8859_1)
        val separator = text.indexOf("\r\n\r\n")
        assertTrue(separator >= 0, "missing HTTP response headers")
        val lines = text.substring(0, separator).split("\r\n")
        val headers = lines.drop(1).associate {
            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
        }
        return Response(lines.first().split(' ')[1].toInt(), headers, wire.copyOfRange(separator + 4, wire.size))
    }

    private fun assertPartial(response: Response, start: Int, end: Int, source: ByteArray = bytes) {
        assertEquals(206, response.status)
        assertEquals("bytes $start-$end/${source.size}", response.headers["content-range"])
        assertEquals((end - start + 1).toString(), response.headers["content-length"])
        assertEquals("bytes", response.headers["accept-ranges"])
        assertContentEquals(source.copyOfRange(start, end + 1), response.body)
    }

    private fun assertFull(response: Response, source: ByteArray = bytes) {
        assertEquals(200, response.status)
        assertNull(response.headers["content-range"])
        assertEquals(source.size.toString(), response.headers["content-length"])
        assertContentEquals(source, response.body)
    }

    private fun assertRejected(response: Response, size: Int = bytes.size) {
        assertEquals(416, response.status)
        assertEquals("bytes */$size", response.headers["content-range"])
        assertEquals("0", response.headers["content-length"])
        assertTrue(response.body.isEmpty())
    }

    @Test
    fun `GET without Range sends the complete file without Content-Range`() {
        assertFull(request(localUrl()))
    }

    @Test
    fun `suffix is counted backwards from the end`() {
        assertPartial(request(localUrl(), "bytes=-500"), 524, 1023)
    }

    @Test
    fun `suffix at least the file size selects the whole file with 206`() {
        val url = localUrl()
        listOf("1024", "4096", "9223372036854775807", "999999999999999999999999999999").forEach {
            assertPartial(request(url, "bytes=-$it"), 0, 1023)
        }
    }

    @Test
    fun `open ended ranges include the last byte`() {
        val url = localUrl()
        assertPartial(request(url, "bytes=500-"), 500, 1023)
        assertPartial(request(url, "bytes=1023-"), 1023, 1023)
        assertPartial(request(url, "bytes=0-"), 0, 1023)
    }

    @Test
    fun `inclusive endpoints and a one byte range are honoured`() {
        val url = localUrl()
        assertPartial(request(url, "bytes=5-8"), 5, 8)
        assertPartial(request(url, "bytes=0-0"), 0, 0)
        assertPartial(request(url, "bytes=1023-1023"), 1023, 1023)
    }

    @Test
    fun `end is clipped to the file even when its numeral overflows Long`() {
        val url = localUrl()
        listOf("1024", "9223372036854775807", "999999999999999999999999999999").forEach {
            assertPartial(request(url, "bytes=1000-$it"), 1000, 1023)
        }
    }

    @Test
    fun `range names are case insensitive and decimal leading zeros are accepted`() {
        assertPartial(request(localUrl(), extraHeaders = listOf("rAnGe: ByTeS= \t0002-0005\t")), 2, 5)
    }

    @Test
    fun `out of bounds reversed and zero suffix ranges return 416 without opening a file`() {
        val url = localUrl()
        listOf("bytes=1024-", "bytes=1024-1025", "bytes=2000-", "bytes=8-5", "bytes=-0",
            "bytes=999999999999999999999999999999-").forEach {
            assertRejected(request(url, it))
        }
        assertEquals(0, localLookups.get())
    }

    @Test
    fun `malformed single byte ranges cannot silently become a different range`() {
        val url = localUrl()
        listOf("bytes=", "bytes=-", "bytes=4", "bytes=a-5", "bytes=4-x", "bytes=+4-6",
            "bytes=4--6", "bytes=--5", "bytes=1 -3", "bytes=1- 3", "bytes=1.0-3").forEach {
            assertRejected(request(url, it))
        }
        assertEquals(0, localLookups.get())
    }

    @Test
    fun `multiple ranges repeated Range fields and unknown units fall back to the whole file`() {
        val url = localUrl()
        assertFull(request(url, "bytes=1-2,8-9"))
        assertFull(request(url, "bytes=1-2", extraHeaders = listOf("Range: bytes=8-9")))
        assertFull(request(url, "items=1-2"))
    }

    @Test
    fun `If-Range without a matching validator falls back to the whole file`() {
        assertFull(request(localUrl(), "bytes=5-8", extraHeaders = listOf("If-Range: \"old-version\"")))
    }

    @Test
    fun `missing or negative stored size is resolved from the local file`() {
        assertPartial(request(localUrl(size = null), "bytes=-10"), 1014, 1023)
        assertPartial(request(localUrl(size = -1), "bytes=1000-2000"), 1000, 1023)
    }

    @Test
    fun `HEAD ignores Range and sends full headers without opening or sending a body`() {
        val url = localUrl()
        listOf(null, "bytes=5-8", "bytes=-500", "bytes=9000-", "bytes=bad", "bytes=1-2,8-9").forEach {
            val response = request(url, it, method = "HEAD")
            assertEquals(200, response.status)
            assertEquals("1024", response.headers["content-length"])
            assertNull(response.headers["content-range"])
            assertTrue(response.body.isEmpty())
        }
        assertEquals(0, localLookups.get())
    }

    @Test
    fun `empty file has no negative endpoints or body and unsatisfiable ranges report zero size`() {
        val empty = ByteArray(0)
        val url = localUrl(empty)
        assertFull(request(url), empty)
        assertFull(request(url, method = "HEAD"), empty)
        listOf("bytes=0-", "bytes=0-0", "bytes=-0").forEach { assertRejected(request(url, it), 0) }
        // RFC 9110 allows ignoring a nonzero suffix of a zero-length representation.
        assertFull(request(url, "bytes=-500"), empty)
        assertEquals(0, localLookups.get())
    }

    private data class UpstreamResponse(val status: Int, val body: ByteArray, val contentRange: String? = null)

    /** A real loopback upstream serves a temporary file through the unmodified StreamService. */
    private fun upstreamUrl(
        body: ByteArray = bytes,
        size: Long? = body.size.toLong(),
        headSize: Long? = size,
        reply: (String, ByteArray) -> UpstreamResponse
    ): String {
        Files.write(file, body)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        servers += server
        server.createContext("/movie.bin") { exchange ->
            try {
                if (exchange.requestMethod == "HEAD") {
                    headSize?.let { exchange.responseHeaders.add("Content-Length", it.toString()) }
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    val range = exchange.requestHeaders.getFirst("Range").orEmpty()
                    upstreamRanges += range
                    val response = reply(range, Files.readAllBytes(file))
                    response.contentRange?.let { exchange.responseHeaders.add("Content-Range", it) }
                    exchange.sendResponseHeaders(response.status, response.body.size.toLong())
                    exchange.responseBody.write(response.body)
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        streams.offlineFile = null
        dav = WebDavClient(StorageConfig(url = "http://127.0.0.1:${server.address.port}"))
        return url(size)
    }

    @Test
    fun `upstream 200 ignoring end is capped at the advertised length across buffer boundaries`() {
        val source = ByteArray(3 * 256 * 1024 + 17) { (it * 37 + it / 256).toByte() }
        val url = upstreamUrl(source) { _, body -> UpstreamResponse(200, body) }
        listOf(1, 256 * 1024 - 1, 256 * 1024, 256 * 1024 + 1).forEach { length ->
            assertPartial(request(url, "bytes=0-${length - 1}"), 0, length - 1, source)
            assertTrue("bytes=0-${length - 1}" in upstreamRanges)
        }
        assertFull(request(url), source)
    }

    @Test
    fun `upstream 206 ignoring end is also capped and unknown upstream total is acceptable`() {
        val url = upstreamUrl { range, body ->
            val start = range.substringAfter('=').substringBefore('-').toInt()
            UpstreamResponse(206, body.copyOfRange(start, body.size), "bytes $start-${body.lastIndex}/*")
        }
        assertPartial(request(url, "bytes=7-10"), 7, 10)
        assertTrue("bytes=7-10" in upstreamRanges)
    }

    @Test
    fun `upstream ignoring a nonzero seek fails before success headers and sends no wrong bytes`() {
        val url = upstreamUrl { _, body -> UpstreamResponse(200, body) }
        val response = request(url, "bytes=200-299")
        assertEquals(502, response.status)
        assertNull(response.headers["content-range"])
        assertEquals("0", response.headers["content-length"])
        assertTrue(response.body.isEmpty())
        assertTrue("bytes=200-299" in upstreamRanges)
    }

    @Test
    fun `upstream 206 with missing or mismatched Content-Range fails before success headers`() {
        listOf(null, "bytes 0-99/1024").forEach { contentRange ->
            val url = upstreamUrl { _, body -> UpstreamResponse(206, body.copyOfRange(0, 100), contentRange) }
            val response = request(url, "bytes=200-299")
            assertEquals(502, response.status)
            assertNull(response.headers["content-range"])
            assertEquals("0", response.headers["content-length"])
            assertTrue(response.body.isEmpty())
        }
    }

    @Test
    fun `unknown representation size ignores Range without inventing length or Content-Range`() {
        val url = upstreamUrl(size = null, headSize = null) { _, body ->
            UpstreamResponse(206, body, "bytes 0-${body.lastIndex}/*")
        }
        listOf(null, "bytes=-500", "bytes=500-", "bytes=5-8").forEach { range ->
            val response = request(url, range)
            assertEquals(200, response.status)
            assertNull(response.headers["content-range"])
            assertNull(response.headers["content-length"])
            assertContentEquals(bytes, response.body)
        }
        assertTrue("bytes=0-" in upstreamRanges)
        val getCount = upstreamRanges.size
        val head = request(url, "bytes=-500", method = "HEAD")
        assertEquals(200, head.status)
        assertNull(head.headers["content-range"])
        assertNull(head.headers["content-length"])
        assertTrue(head.body.isEmpty())
        assertEquals(getCount, upstreamRanges.size)
    }
}
