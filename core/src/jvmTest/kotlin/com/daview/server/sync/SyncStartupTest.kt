package com.daview.server.sync

import com.daview.server.ServerContext
import com.daview.server.config.ConfigStore
import com.daview.server.config.StorageConfig
import com.daview.server.config.SyncConfig
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.BackupUserDataDto
import com.daview.shared.model.UserDataDto
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncStartupTest {
    private val contexts = mutableListOf<ServerContext>()
    private val workers = Executors.newCachedThreadPool()
    private val httpWorkers = Executors.newCachedThreadPool()
    private val requests = ConcurrentLinkedQueue<String>()
    private val releaseGet = CountDownLatch(1)
    private val getEntered = CountDownLatch(1)
    private val releasePut = CountDownLatch(1)
    private val putEntered = CountDownLatch(1)

    @Volatile private var getStatus = 200
    @Volatile private var body = payload("episode" to (5_000L to 200L))
    @Volatile private var blockGet = false
    @Volatile private var blockPut = false

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            try {
                requests += exchange.requestMethod
                when (exchange.requestMethod) {
                    "GET" -> {
                        getEntered.countDown()
                        if (blockGet) check(releaseGet.await(5, TimeUnit.SECONDS))
                        val bytes = body.toByteArray()
                        if (getStatus == 404) exchange.sendResponseHeaders(404, -1)
                        else {
                            exchange.sendResponseHeaders(getStatus, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                        }
                    }
                    "PUT" -> {
                        exchange.requestBody.readBytes()
                        putEntered.countDown()
                        if (blockPut) check(releasePut.await(5, TimeUnit.SECONDS))
                        exchange.sendResponseHeaders(201, -1)
                    }
                    else -> exchange.sendResponseHeaders(405, -1)
                }
            } finally { exchange.close() }
        }
        executor = httpWorkers
        start()
    }

    private fun start(enabled: Boolean = true): ServerContext {
        val dir = createTempDirectory("daview-sync-startup")
        // Persist before construction: timestamps left by the previous process
        // must never suppress the first pull in this process.
        ConfigStore(dir).update {
            it.copy(
                storage = StorageConfig(url = "http://127.0.0.1:${server.address.port}"),
                sync = SyncConfig(
                    enabled = enabled,
                    minIntervalMinutes = 1440,
                    lastPullAt = System.currentTimeMillis() + 86_400_000,
                    lastUploadAt = System.currentTimeMillis() + 86_400_000
                )
            )
        }
        return ServerContext(dir).also { contexts += it }
    }

    private fun payload(vararg rows: Pair<String, Pair<Long, Long>>) = DaViewJson.encodeToString(
        BackupFileDto.serializer(),
        BackupFileDto(userData = rows.map { (id, state) ->
            BackupUserDataDto(id, UserDataDto(positionMs = state.first), state.second)
        })
    )

    @AfterTest
    fun close() {
        releaseGet.countDown()
        releasePut.countDown()
        workers.shutdownNow()
        contexts.forEach { it.close() }
        server.stop(0)
        httpWorkers.shutdownNow()
    }

    @Test
    fun `startup ignores saved intervals and merges newer progress without uploading`() = runBlocking {
        val context = start()
        context.repository.restoreUserData("episode", UserDataDto(positionMs = 1_000), 100)
        context.repository.restoreUserData("newer-local", UserDataDto(positionMs = 9_000), 300)
        context.repository.restoreUserData("same-time", UserDataDto(positionMs = 7_000), 200)
        body = payload(
            "episode" to (5_000L to 200L),
            "newer-local" to (2_000L to 100L),
            "same-time" to (3_000L to 200L),
            "not-scanned" to (4_000L to 200L)
        )
        val previousUpload = context.config.sync.lastUploadAt
        val result = assertNotNull(context.media.syncOnStartup())
        assertTrue(result.ok, result.message)
        assertEquals(5_000L, context.repository.userData("episode").positionMs)
        assertEquals(9_000L, context.repository.userData("newer-local").positionMs)
        assertEquals(7_000L, context.repository.userData("same-time").positionMs)
        assertEquals(4_000L, context.repository.userData("not-scanned").positionMs)
        assertNull(context.repository.item("not-scanned"))
        assertEquals(listOf("GET"), requests.toList())
        assertEquals(previousUpload, context.config.sync.lastUploadAt)
        assertTrue(assertNotNull(context.config.sync.lastPullAt) <= System.currentTimeMillis())
    }

    @Test
    fun `disabled startup does not contact storage or change sync settings`() {
        val context = start(enabled = false)
        val before = context.config.sync
        assertNull(context.sync.pullOnStartup())
        assertTrue(requests.isEmpty())
        assertEquals(before, context.config.sync)
    }

    @Test
    fun `reopening the UI reuses the startup result within one context`() {
        val context = start()
        val first = context.sync.pullOnStartup()
        body = payload("episode" to (8_000L to 300L))
        assertEquals(first, context.sync.pullOnStartup())
        assertEquals(5_000L, context.repository.userData("episode").positionMs)
        assertEquals(listOf("GET"), requests.toList())
    }

    @Test
    fun `manual upload reaching sync first cannot skip the startup merge`() {
        val context = start()
        blockGet = true
        blockPut = true
        val upload = workers.submit(Callable { context.sync.upload() })
        assertTrue(getEntered.await(2, TimeUnit.SECONDS))
        val startup = workers.submit(Callable { context.sync.pullOnStartup() })
        assertFalse(startup.isDone)
        releaseGet.countDown()
        assertTrue(putEntered.await(2, TimeUnit.SECONDS))
        // Startup readiness is independent of the later PUT finishing.
        assertTrue(assertNotNull(startup.get(2, TimeUnit.SECONDS)).ok)
        assertEquals(5_000L, context.repository.userData("episode").positionMs)
        assertFalse(upload.isDone)
        releasePut.countDown()
        assertTrue(upload.get(2, TimeUnit.SECONDS).ok)
        assertEquals(listOf("GET", "GET", "PUT"), requests.toList())
    }

    @Test
    fun `a timer reaching sync first still performs startup despite recent persisted pull`() {
        val context = start()
        blockGet = true
        val tick = workers.submit(Callable { context.sync.tick() })
        assertTrue(getEntered.await(2, TimeUnit.SECONDS))
        val startup = workers.submit(Callable { context.sync.pullOnStartup() })
        assertFalse(startup.isDone)
        releaseGet.countDown()
        assertTrue(assertNotNull(startup.get(2, TimeUnit.SECONDS)).ok)
        tick.get(2, TimeUnit.SECONDS)
        assertEquals(5_000L, context.repository.userData("episode").positionMs)
        assertEquals(listOf("GET"), requests.toList())
    }

    @Test
    fun `failed startup preserves local progress and does not upload or immediately retry`() {
        val context = start()
        context.repository.restoreUserData("episode", UserDataDto(positionMs = 9_000), 300)
        getStatus = 503
        val result = assertNotNull(context.sync.pullOnStartup())
        assertFalse(result.ok)
        assertEquals(result.message, context.config.sync.lastError)
        assertEquals(9_000L, context.repository.userData("episode").positionMs)
        context.updateConfig { it.copy(sync = it.sync.copy(lastPullAt = null)) }
        context.sync.tick()
        assertEquals(result, context.sync.pullOnStartup())
        assertEquals(listOf("GET"), requests.toList())
    }

    @Test
    fun `a recoverable startup exception does not prevent subsequent manual sync`() {
        val context = start()
        val calls = AtomicInteger()
        val sync = SyncService(context) {
            if (calls.incrementAndGet() == 1) error("transient provider failure")
            context.webdav()
        }
        try {
            val startup = assertNotNull(sync.pullOnStartup())
            assertFalse(startup.ok)
            assertTrue(startup.message.contains("transient provider failure"))
            assertTrue(sync.pull().ok)
            assertEquals(5_000L, context.repository.userData("episode").positionMs)
            assertEquals(startup, sync.pullOnStartup())
            assertEquals(listOf("GET"), requests.toList())
        } finally { sync.close() }
    }

    @Test
    fun `missing or unreadable startup file leaves local progress and remote storage intact`() {
        for ((status, content) in listOf(404 to "", 200 to "{\"error\":\"gateway\"}")) {
            val context = start()
            context.repository.restoreUserData("episode", UserDataDto(positionMs = 9_000), 300)
            getStatus = status
            body = content
            assertFalse(assertNotNull(context.sync.pullOnStartup()).ok)
            assertEquals(9_000L, context.repository.userData("episode").positionMs)
        }
        assertEquals(listOf("GET", "GET"), requests.toList())
    }
}
