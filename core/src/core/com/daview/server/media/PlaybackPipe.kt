package com.daview.server.media

import com.daview.server.db.Repository
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A local address an external player can fetch, and nothing else.
 *
 * PotPlayer, VLC and MX Player take a URL, not a Kotlin object, and none of
 * them reports where playback has got to. The only way to know is to watch
 * which bytes they ask for, which means the bytes have to come from somewhere
 * we control — so this exists.
 *
 * It is deliberately not a server:
 *  - it binds `127.0.0.1` on a port the OS picks, so nothing off the machine
 *    can reach it and no fixed port has to be claimed;
 *  - it starts when an external player is handed a file and stops when the
 *    last session ends, rather than running for as long as the app does;
 *  - it answers exactly one shape of request, has no API, no token store and
 *    no JSON.
 *
 * Written against a raw socket rather than a server framework because `:core`
 * runs on Android too: `com.sun.net.httpserver` does not exist there, and
 * pulling in an HTTP server for one route is how the port that started all
 * this got opened in the first place.
 */
class PlaybackPipe(
    private val repository: Repository,
    private val streams: StreamService,
    private val playback: PlaybackService
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(PlaybackPipe::class.java)
    private val running = AtomicBoolean(false)

    @Volatile
    private var socket: ServerSocket? = null

    /** The thread blocked in accept() on [socket]; see [close] for why it is kept. */
    @Volatile
    private var acceptThread: Thread? = null

    /** Sessions currently pointed at this pipe. It closes when the set empties. */
    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        // The screen that started an external player is not a reliable place to
        // learn that it ended: on Android the process cannot be watched at all,
        // so the session is retired by its idle timeout rather than by anyone
        // calling stop. Taking the end from the session tracker itself means the
        // socket goes down on every route out, not just the one the UI drives.
        playback.onSessionEnded(::release)
    }

    /**
     * Starts the pipe if it is not already up and returns an address for
     * [sessionId]. The session id is in the path and is the only thing that
     * makes a request answerable, so a stale link stops working when playback
     * ends.
     */
    @Synchronized
    fun urlFor(sessionId: String, itemId: String, fileName: String, redirect: Boolean): String {
        val port = start()
        sessions += sessionId
        val encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%2F", "/")
        val mode = if (redirect) "r" else "p"
        return "http://127.0.0.1:$port/$mode/$sessionId/$itemId/$encoded"
    }

    /**
     * A subtitle file, for the case where the storage would not produce a link
     * of its own. Players load these over HTTP like anything else.
     */
    @Synchronized
    fun subtitleUrlFor(sessionId: String, itemId: String, index: Int): String {
        val port = start()
        sessions += sessionId
        return "http://127.0.0.1:$port/t/$sessionId/$itemId/$index"
    }

    /** Drops a session and closes the socket once none are left. */
    @Synchronized
    fun release(sessionId: String) {
        sessions -= sessionId
        if (sessions.isEmpty()) close()
    }

    @Synchronized
    private fun start(): Int {
        socket?.let { if (!it.isClosed) return it.localPort }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        socket = server
        running.set(true)
        acceptThread = Thread({ accept(server) }, "daview-playback-pipe").apply {
            isDaemon = true
            start()
        }
        log.info("播放管道已启动: 127.0.0.1:{}", server.localPort)
        return server.localPort
    }

    private fun accept(server: ServerSocket) {
        while (running.get() && !server.isClosed) {
            val client = runCatching { server.accept() }.getOrElse { return }
            // A player opens a few connections at once — one to probe, one or
            // more to read — so each gets its own thread rather than queueing.
            Thread({ serve(client) }, "daview-pipe-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            connection.tcpNoDelay = true
            val input = connection.getInputStream().buffered()
            val output = BufferedOutputStream(connection.getOutputStream())
            val request = readRequest(input) ?: return
            runCatching { handle(request, output) }
                .onFailure { log.warn("播放管道请求失败 {}: {}", request.path, it.message) }
            runCatching { output.flush() }
        }
    }

    // ------------------------------------------------------------ request

    private data class Request(
        val method: String,
        val path: String,
        val range: String?,
        val hasIfRange: Boolean
    )

    private sealed interface ByteRange {
        data object Full : ByteRange
        data object Rejected : ByteRange
        data class Partial(val start: Long, val end: Long) : ByteRange
    }

    /** Resolve only after the representation length is known; a suffix is not an end offset. */
    private fun resolveRange(request: Request, size: Long?): ByteRange {
        val value = request.range ?: return ByteRange.Full
        // RFC 9110 defines Range for GET only. Without validators, If-Range
        // cannot be confirmed, so send the complete representation instead.
        if (request.method != "GET" || request.hasIfRange || size == null) return ByteRange.Full
        if (!value.substringBefore('=').equals("bytes", ignoreCase = true)) return ByteRange.Full
        val spec = value.substringAfter('=', "").trim()
        // Multipart responses are not supported. Repeated Range fields also
        // arrive here as a combined value and must not select just one range.
        if (',' in spec) return ByteRange.Full
        val match = SINGLE_BYTE_RANGE.matchEntire(spec) ?: return ByteRange.Rejected
        val (first, last) = match.destructured
        if (first.isEmpty() && last.isEmpty()) return ByteRange.Rejected

        // Numerals can exceed Long. Saturation preserves clipping for ends and
        // suffix lengths, while any overflowing start is outside a known file.
        fun offset(digits: String): Long = digits.toLongOrNull() ?: Long.MAX_VALUE
        if (first.isEmpty()) {
            val suffix = offset(last)
            if (suffix == 0L) return ByteRange.Rejected
            // A nonzero suffix is satisfiable even for an empty representation
            // (RFC 9110 14.1.2); ignore it rather than emit an invalid 0--1 range.
            if (size == 0L) return ByteRange.Full
            return ByteRange.Partial(size - minOf(suffix, size), size - 1)
        }
        val start = offset(first)
        if (start >= size) return ByteRange.Rejected
        val end = if (last.isEmpty()) size - 1 else offset(last)
        if (end < start) return ByteRange.Rejected
        return ByteRange.Partial(start, minOf(end, size - 1))
    }

    /**
     * Reads the request line and the headers we care about. Everything else is
     * skipped: the only client is a media player this process just launched.
     */
    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        var range: String? = null
        var hasIfRange = false
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("If-Range:", ignoreCase = true)) hasIfRange = true
            if (line.startsWith("Range:", ignoreCase = true)) {
                val value = line.substringAfter(':').trim()
                range = range?.let { "$it,$value" } ?: value
            }
        }
        return Request(parts[0].uppercase(), parts[1], range, hasIfRange)
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.removeSuffix("\r").toString()
            builder.append(byte.toChar())
            if (builder.length > MAX_LINE) return null
        }
    }

    private fun StringBuilder.removeSuffix(suffix: String): StringBuilder =
        if (endsWith(suffix)) apply { setLength(length - suffix.length) } else this

    // ------------------------------------------------------------ response

    private fun handle(request: Request, output: OutputStream) {
        // /<mode>/<sessionId>/<itemId>/<name>
        val segments = request.path.trimStart('/').split('/', limit = 4)
        if (segments.size < 3) return respondStatus(output, 400, "Bad Request")
        val (mode, sessionId, itemId) = segments
        if (sessionId !in sessions) return respondStatus(output, 404, "Not Found")

        val item = repository.item(itemId) ?: return respondStatus(output, 404, "Not Found")

        if (mode == "t") {
            val index = segments.getOrNull(3)?.toIntOrNull()
            val subtitlePath = item.mediaStreams
                .firstOrNull { it.index == index && it.isExternal }?.externalPath
                ?: return respondStatus(output, 404, "Not Found")
            val bytes = streams.openRange(subtitlePath, 0, null).use { it.stream.readBytes() }
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            if (request.method != "HEAD") output.write(bytes)
            return
        }

        val path = item.path ?: return respondStatus(output, 404, "Not Found")

        val size = item.sizeBytes?.takeIf { it >= 0 }
            ?: runCatching { streams.fileSize(path) }.getOrNull()?.takeIf { it >= 0 }
        val range = resolveRange(request, size)
        val start = (range as? ByteRange.Partial)?.start ?: 0L

        if (mode == "r") {
            val direct = streams.directUrl(path)
            if (direct != null) {
                if (request.method == "GET" && range != ByteRange.Rejected) {
                    playback.onRangeRequest(sessionId, start)
                }
                output.write(
                    ("HTTP/1.1 302 Found\r\nLocation: $direct\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                )
                return
            }
            // No signed link to hand over; fall through and serve the bytes.
        }

        if (range == ByteRange.Rejected) {
            return respondStatus(output, 416, "Range Not Satisfiable", "bytes */$size")
        }
        val partial = range as? ByteRange.Partial
        val end = partial?.end ?: size?.takeIf { it > 0 }?.minus(1)
        val length = partial?.let { it.end - it.start + 1 } ?: size

        val header = StringBuilder()
        header.append(if (partial != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        header.append("Content-Type: application/octet-stream\r\n")
        header.append("Accept-Ranges: bytes\r\n")
        if (partial != null) {
            header.append("Content-Range: bytes $start-$end/$size\r\n")
        }
        length?.let { header.append("Content-Length: $it\r\n") }
        header.append("Connection: close\r\n\r\n")

        // A player asks for the headers before it commits to reading, and
        // answering that with a body would leave it parsing a video as a
        // response to a question it did not ask.
        if (request.method == "HEAD" || length == 0L) {
            output.write(header.toString().toByteArray())
            return
        }

        // Open before sending success headers, so an upstream failure can
        // still be reported without an incomplete or incorrectly offset 206.
        val opened = try {
            streams.openRange(path, start, end)
        } catch (e: Exception) {
            log.warn("播放管道读取失败 {}: {}", path, e.message)
            return respondStatus(output, 502, "Bad Gateway")
        }
        opened.use { stream ->
            // WebDavClient validates upstream Content-Range offsets. Keep a
            // guard at this boundary too: a whole-file stream cannot be used
            // as-is for a nonzero seek.
            if (start > 0 && !stream.partial) {
                return respondStatus(output, 502, "Bad Gateway")
            }
            output.write(header.toString().toByteArray())
            if (request.method == "GET") playback.onRangeRequest(sessionId, start)
            val buffer = ByteArray(STREAM_BUFFER)
            var delivered = 0L
            while (length == null || delivered < length) {
                // A server may ignore the requested end, including sending
                // the entire file with 200 for a range starting at zero.
                val wanted = if (length == null) buffer.size else minOf(buffer.size.toLong(), length - delivered).toInt()
                val read = stream.stream.read(buffer, 0, wanted)
                if (read <= 0) break
                output.write(buffer, 0, read)
                delivered += read
                // How far the player has buffered. It is an upper bound on the
                // playback position, not the position itself.
                playback.onBytesRead(sessionId, start + delivered)
            }
        }
    }

    private fun respondStatus(output: OutputStream, code: Int, reason: String, contentRange: String? = null) {
        val rangeHeader = contentRange?.let { "Content-Range: $it\r\n" }.orEmpty()
        output.write("HTTP/1.1 $code $reason\r\n${rangeHeader}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    /**
     * Stops listening, and returns only once the port is really closed.
     *
     * Closing the socket is not enough on its own on Linux: while a thread is
     * blocked in accept() on it, that call holds the listening socket open until
     * the thread is woken and leaves it, and in that moment the port still takes
     * connections — the accept even hands one back. The JDK wakes the thread,
     * but asynchronously, so "released" could still be listening a moment
     * later, which is how the CI runner saw it and Windows, where a close takes
     * effect at once, never did. Waiting for the thread closes that gap.
     */
    @Synchronized
    override fun close() {
        running.set(false)
        sessions.clear()
        runCatching { socket?.close() }
        socket = null
        val thread = acceptThread
        acceptThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(ACCEPT_EXIT_WAIT_MS) }
        }
    }

    private companion object {
        val SINGLE_BYTE_RANGE = Regex("([0-9]*)-([0-9]*)")
        const val STREAM_BUFFER = 256 * 1024
        const val MAX_LINE = 8 * 1024

        /** Long enough for a woken thread to leave accept(); it takes microseconds. */
        const val ACCEPT_EXIT_WAIT_MS = 1_000L
    }
}
