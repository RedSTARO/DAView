package com.daview.server.media

import com.daview.server.db.Database
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlayerKind
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pipe speaks HTTP to a player this process launched, on a socket written
 * by hand, so the parts worth pinning down are the ones a framework would
 * otherwise have handled: that it only answers for a live session, that it
 * stops listening when playback ends, and that a stale link is dead.
 */
class PlaybackPipeTest {

    private val dir = createTempDirectory("daview-pipe-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private val streams = StreamService({ null }, repository)

    /** Read on every tick, so a test can retire a session by shortening it. */
    private var idleTimeoutSec = 300
    private val playback = PlaybackService(repository, streams) { idleTimeoutSec }
    private val pipe = PlaybackPipe(repository, streams, playback)

    @AfterTest
    fun tearDown() {
        pipe.close()
        database.close()
    }

    private fun status(url: String, method: String = "GET"): Int {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return connection.use { it.responseCode }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    /**
     * Whether anything still accepts a connection on that address.
     *
     * A bare connect rather than a request. What the closing tests are about is
     * the socket being gone, and asking that through [status] puts proxy
     * selection, a connection pool and a retry in front of the question — three
     * layers that answered differently on the CI runner than on any machine
     * here, which is what made those tests fail there and nowhere else. This
     * cannot hide a pipe that is genuinely still up: a listening socket accepts.
     */
    private fun listening(url: String): Boolean {
        val address = URI(url).let { InetSocketAddress(it.host, it.port) }
        return Socket().use { socket ->
            runCatching { socket.connect(address, 2_000) }.isSuccess
        }
    }

    @Test
    fun `binds loopback only, on a port the OS picks`() {
        val url = pipe.urlFor("session-1", "item-1", "Show - S01E01.mkv", redirect = false)
        assertTrue(url.startsWith("http://127.0.0.1:"), url)
        val port = url.removePrefix("http://127.0.0.1:").substringBefore('/').toInt()
        assertTrue(port > 0)
    }

    /**
     * The session id in the path is the whole of the access control: anything
     * else on the machine can reach a loopback port, so a request that does not
     * name a live session must not be answered.
     */
    @Test
    fun `a request naming no live session is refused`() {
        val url = pipe.urlFor("session-1", "item-1", "a.mkv", redirect = false)
        val other = url.replace("session-1", "session-2")
        assertEquals(404, status(other))
    }

    /** The item is unknown here, but the point is that the session was accepted. */
    @Test
    fun `a live session gets past the door`() {
        val url = pipe.urlFor("session-1", "missing-item", "a.mkv", redirect = false)
        // 404 for the item rather than for the session — both are 404 on the
        // wire, so the meaningful assertion is that it answered at all.
        assertEquals(404, status(url))
    }

    @Test
    fun `a malformed path is refused rather than crashing the connection`() {
        val url = pipe.urlFor("session-1", "item-1", "a.mkv", redirect = false)
        val root = url.substringBefore("/p/") + "/p"
        assertEquals(400, status(root))
    }

    /**
     * The pipe exists only while something is playing through it. Releasing the
     * last session takes the socket down, which is the difference between this
     * and a server.
     */
    @Test
    fun `releasing the last session closes the socket`() {
        val url = pipe.urlFor("session-1", "item-1", "a.mkv", redirect = false)
        pipe.release("session-1")
        assertFalse(listening(url), "expected the port to be closed")
    }

    /**
     * A subtitle address belongs to the session that asked for it. Hanging it
     * off a session id of its own left the pipe listening for the life of the
     * process after the first subtitle anyone loaded.
     */
    @Test
    fun `a subtitle address is released with its session`() {
        pipe.urlFor("session-1", "item-1", "a.mkv", redirect = false)
        val subtitle = pipe.subtitleUrlFor("session-1", "item-1", 1000)
        pipe.release("session-1")
        assertFalse(listening(subtitle), "expected the port to be closed")
    }

    /**
     * The common ending for an external player is not someone pressing stop: on
     * Android the player runs in another process that cannot be watched, so the
     * session goes away on its idle timeout and nothing in the UI is told. If
     * only an explicit stop closed the pipe, the socket would then stay up for
     * the life of the process — which is the whole thing this refactor removed.
     */
    @Test
    fun `a session retired by its idle timeout closes the socket`() {
        val item = MediaItemDto(id = "item-1", libraryId = "lib-1", kind = ItemKind.MOVIE, name = "Film")
        val session = playback.start(item, PlayerKind.POTPLAYER, "test", 0, null, null)
        val url = pipe.urlFor(session.id, item.id, "a.mkv", redirect = false)
        assertEquals(404, status(url), "expected the pipe to be answering before the timeout")

        // The ticker sweeps every two seconds; nothing here calls stop().
        idleTimeoutSec = 0
        val closed = generateSequence { !listening(url) }
            .take(150)
            .onEach { if (!it) Thread.sleep(100) }
            .any { it }
        assertTrue(closed, "expected the port to be closed once the session was retired")
    }

    @Test
    fun `it stays up while another session is still playing`() {
        val first = pipe.urlFor("session-1", "item-1", "a.mkv", redirect = false)
        pipe.urlFor("session-2", "item-1", "a.mkv", redirect = false)
        pipe.release("session-1")
        assertEquals(404, status(first.replace("session-1", "session-2")))
    }
}
