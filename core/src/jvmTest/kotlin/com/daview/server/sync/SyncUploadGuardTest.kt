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

    @Volatile
    private var getBody = ""

    @Volatile
    private var afterPut: (() -> Unit)? = null

    @Volatile
    private var declaredLength: Long? = null

    private val puts = ConcurrentLinkedQueue<String>()
    private val putBodies = ConcurrentLinkedQueue<String>()

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
                    putBodies += it.requestBody.readBytes().toString(Charsets.UTF_8)
                    afterPut?.invoke()
                    it.sendResponseHeaders(201, -1)
                }

                "GET" -> {
                    if (declaredLength != null) it.sendResponseHeaders(getStatus, declaredLength!!)
                    else if (getBody.isEmpty()) it.sendResponseHeaders(getStatus, -1)
                    else {
                        val bytes = getBody.toByteArray()
                        it.sendResponseHeaders(getStatus, bytes.size.toLong())
                        it.responseBody.write(bytes)
                    }
                }
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

    @Test
    fun `a malformed remote file is preserved for recovery`() {
        getStatus = 200
        getBody = "{\"userData\":["
        val result = sync.upload()
        assertFalse(result.ok, result.message)
        assertEquals(0, puts.size, "a truncated remote file must not be overwritten")
    }

    @Test
    fun `a valid JSON gateway error is not an empty backup`() {
        getStatus = 200
        getBody = """{"error":"temporarily unavailable"}"""
        assertFalse(sync.upload().ok)
        assertEquals(0, puts.size)
    }

    @Test
    fun `a sync file from a newer format is not overwritten by an older client`() {
        getStatus = 200
        getBody = """{"format":"${com.daview.shared.model.BACKUP_FORMAT}","version":2147483647}"""
        val result = sync.upload()
        assertFalse(result.ok, result.message)
        assertEquals(0, puts.size)
    }

    @Test
    fun `an oversized remote file is not overwritten`() {
        getStatus = 200
        declaredLength = 33L * 1024 * 1024
        val result = sync.upload()
        assertFalse(result.ok, result.message)
        assertEquals(0, puts.size)
    }

    @Test
    fun `an edit during upload is still uploaded on the next eligible tick`() {
        getStatus = 404
        afterPut = {
            context.repository.restoreUserData(
                "episode", com.daview.shared.model.UserDataDto(positionMs = 12_000), updatedAt = 100
            )
        }
        assertTrue(sync.upload().ok)
        afterPut = null
        context.updateConfig {
            it.copy(sync = it.sync.copy(enabled = true, lastUploadAt = null, lastPullAt = System.currentTimeMillis()))
        }
        sync.tick()
        assertEquals(2, puts.size, "the completed PUT did not include the edit made while it was in flight")
    }

    private fun eligibleTick(pull: Boolean = false) {
        context.updateConfig {
            it.copy(sync = it.sync.copy(enabled = true, lastUploadAt = null,
                lastPullAt = if (pull) null else System.currentTimeMillis()))
        }
        sync.tick()
    }

    private fun serve(body: String) { getStatus = 200; getBody = body }

    private fun positionInLastUpload(id: String): Long = com.daview.shared.api.DaViewJson.decodeFromString(
        com.daview.shared.model.BackupFileDto.serializer(), putBodies.last()
    ).userData.single { it.itemId == id }.data.positionMs

    @Test
    fun `an edit below the largest timestamp is still uploaded`() {
        context.repository.restoreUserData("future", com.daview.shared.model.UserDataDto(positionMs = 10), 1000)
        context.repository.restoreUserData("edited", com.daview.shared.model.UserDataDto(positionMs = 20), 100)
        getStatus = 404
        assertTrue(sync.upload().ok)
        serve(putBodies.last())
        context.repository.restoreUserData("edited", com.daview.shared.model.UserDataDto(positionMs = 30), 200)
        eligibleTick()
        assertEquals(2, puts.size)
        assertEquals(30, positionInLastUpload("edited"))
    }

    @Test
    fun `a same-timestamp local edit triggers upload without changing the merge tie policy`() {
        context.repository.restoreUserData("ep", com.daview.shared.model.UserDataDto(positionMs = 10), 100)
        getStatus = 404
        assertTrue(sync.upload().ok)
        serve(putBodies.last())
        context.repository.restoreUserData("ep", com.daview.shared.model.UserDataDto(positionMs = 20), 100)
        eligibleTick()
        assertEquals(2, puts.size)
        assertEquals(20, positionInLastUpload("ep"))
    }

    @Test
    fun `a remote overwrite is repaired even when merging changes no local row`() {
        context.repository.restoreUserData("ep", com.daview.shared.model.UserDataDto(positionMs = 10), 100)
        getStatus = 404
        assertTrue(sync.upload().ok)
        val oldSnapshot = putBodies.last()
        context.repository.restoreUserData("ep", com.daview.shared.model.UserDataDto(positionMs = 90), 200)
        assertTrue(sync.upload().ok)
        val beforePull = context.repository.syncFingerprint()
        serve(oldSnapshot) // Another client's late PUT replaced the successful upload.
        assertTrue(sync.pull().ok)
        assertEquals(beforePull, context.repository.syncFingerprint())
        eligibleTick()
        assertEquals(3, puts.size)
        assertEquals(90, positionInLastUpload("ep"))
    }

    @Test
    fun `a disappeared remote file is recreated with unchanged local data`() {
        getStatus = 404
        assertTrue(sync.upload().ok)
        assertFalse(sync.pull().ok)
        eligibleTick()
        assertEquals(2, puts.size)
    }

    @Test
    fun `pulling an identical remote copy does not schedule another upload`() {
        context.repository.restoreUserData("ep", com.daview.shared.model.UserDataDto(positionMs = 10), 100)
        getStatus = 404
        assertTrue(sync.upload().ok)
        serve(putBodies.last())
        eligibleTick(pull = true)
        assertEquals(1, puts.size)
    }
}
