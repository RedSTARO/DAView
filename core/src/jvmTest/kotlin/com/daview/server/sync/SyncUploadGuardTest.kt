package com.daview.server.sync

import com.daview.server.ServerContext
import com.daview.server.config.StorageConfig
import com.daview.server.storage.WebDavClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that keeps one device from erasing another's watch history.
 *
 * The sync file is shared by every device and each one rewrites the whole of it,
 * so an upload has to start by reading what is already there. The dangerous case
 * is the one where that read fails for a reason that is not "the file is not
 * there": answering it with an upload would replace everyone else's state with
 * only what this device happens to know. [SyncPayloadTest] covers the merge
 * arithmetic; this covers the decision to write at all, which is the half that
 * loses data when it is wrong.
 *
 * A real share is stood up on loopback rather than faked, because the thing
 * under test is how an HTTP status is classified, and that classification lives
 * inside [WebDavClient].
 */
class SyncUploadGuardTest {

    private val dir = createTempDirectory("daview-sync-guard")
    private val context = ServerContext(dir)

    /** Status the fake share answers a body read with. */
    @Volatile
    private var getStatus = 500

    private val puts = ConcurrentLinkedQueue<String>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        executor = null
        start()
    }

    private val dav = WebDavClient(StorageConfig(url = "http://127.0.0.1:${server.address.port}"))
    private val sync = SyncService(context) { dav }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            when (it.requestMethod) {
                "PUT" -> {
                    puts += it.requestURI.path
                    it.requestBody.readBytes()
                    it.sendResponseHeaders(201, -1)
                }

                "GET" -> it.sendResponseHeaders(getStatus, -1)
                else -> it.sendResponseHeaders(405, -1)
            }
        }
    }

    private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) =
        try {
            block(this)
        } finally {
            close()
        }

    @AfterTest
    fun tearDown() {
        sync.close()
        context.close()
        server.stop(0)
    }

    /**
     * 500 means the share could not be asked, not that the file is absent. What
     * is on it is unknown, so it is left alone.
     */
    @Test
    fun `an unreadable sync file is not overwritten`() {
        getStatus = 500
        val result = sync.upload()
        assertFalse(result.ok, result.message)
        assertTrue(result.message.contains("跳过"), result.message)
        assertEquals(0, puts.size, "an upload happened despite the read failing")
    }

    /** 404 is an answer: there is nothing to merge, and the first upload writes it. */
    @Test
    fun `a share with no sync file yet is written to`() {
        getStatus = 404
        val result = sync.upload()
        assertTrue(result.ok, result.message)
        assertEquals(listOf("/daview-sync.json"), puts.toList())
    }
}
