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

    /** Sessions currently pointed at this pipe. It closes when the set empties. */
    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
        val encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%2F", "/")
        val mode = if (redirect) "r" else "p"
        return "http://127.0.0.1:$port/$mode/$sessionId/$itemId/$encoded"
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
        Thread({ accept(server) }, "daview-playback-pipe").apply { isDaemon = true }.start()
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
        val rangeStart: Long,
        val rangeEnd: Long?,
        val hasRange: Boolean
    )

    /**
     * Reads the request line and the headers we care about. Everything else is
     * skipped: the only client is a media player this process just launched.
     */
    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        var rangeStart = 0L
        var rangeEnd: Long? = null
        var hasRange = false
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (!line.startsWith("Range:", ignoreCase = true)) continue
            val spec = line.substringAfter(':').trim().removePrefix("bytes=")
            hasRange = true
            rangeStart = spec.substringBefore('-').trim().toLongOrNull() ?: 0L
            rangeEnd = spec.substringAfter('-').trim().toLongOrNull()
        }
        return Request(parts[0].uppercase(), parts[1], rangeStart, rangeEnd, hasRange)
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

        val path = repository.item(itemId)?.path ?: return respondStatus(output, 404, "Not Found")

        // Every request is a position report, including the ones a player makes
        // while opening the file. PlaybackService is what decides which of them
        // is a real seek and which is an index read at the end of the file.
        playback.onRangeRequest(sessionId, request.rangeStart)

        if (mode == "r") {
            val direct = streams.directUrl(path)
            if (direct != null) {
                output.write(
                    ("HTTP/1.1 302 Found\r\nLocation: $direct\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                )
                return
            }
            // No signed link to hand over; fall through and serve the bytes.
        }

        val size = repository.item(itemId)?.sizeBytes ?: runCatching { streams.fileSize(path) }.getOrNull()
        val rangeEnd = request.rangeEnd ?: size?.let { it - 1 }
        val length = if (size != null && rangeEnd != null) rangeEnd - request.rangeStart + 1 else null

        val header = StringBuilder()
        header.append(if (request.hasRange) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        header.append("Content-Type: application/octet-stream\r\n")
        header.append("Accept-Ranges: bytes\r\n")
        if (size != null && rangeEnd != null) {
            header.append("Content-Range: bytes ${request.rangeStart}-$rangeEnd/$size\r\n")
        }
        length?.let { header.append("Content-Length: $it\r\n") }
        header.append("Connection: close\r\n\r\n")
        output.write(header.toString().toByteArray())

        // A player asks for the headers before it commits to reading, and
        // answering that with a body would leave it parsing a video as a
        // response to a question it did not ask.
        if (request.method == "HEAD") return

        streams.openRange(path, request.rangeStart, rangeEnd).use { stream ->
            val buffer = ByteArray(STREAM_BUFFER)
            var delivered = 0L
            while (true) {
                val read = stream.stream.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                delivered += read
                // How far the player has buffered. It is an upper bound on the
                // playback position, not the position itself.
                playback.onBytesRead(sessionId, request.rangeStart + delivered)
            }
        }
    }

    private fun respondStatus(output: OutputStream, code: Int, reason: String) {
        output.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    @Synchronized
    override fun close() {
        running.set(false)
        sessions.clear()
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val STREAM_BUFFER = 256 * 1024
        const val MAX_LINE = 8 * 1024
    }
}
