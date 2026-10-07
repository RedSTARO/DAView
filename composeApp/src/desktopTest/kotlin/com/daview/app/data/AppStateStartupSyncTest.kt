package com.daview.app.data

import com.daview.server.ServerContext
import com.daview.server.config.ScraperConfig
import com.daview.server.config.StorageConfig
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.SqlConnection
import com.daview.server.db.SqlDatabase
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.BackupUserDataDto
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.UserDataDto
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStateStartupSyncTest {
    @Test
    fun `startup merges remote progress before initial catalogue and home reads`() = runBlocking {
        withFixture(enabled = true) { f ->
            val job = assertNotNull(f.state.open())
            f.awaitRequest()

            assertTrue(f.state.startupSyncing)
            assertFalse(f.state.ready)
            assertNull(f.state.serverInfo)
            assertTrue(f.state.libraries.isEmpty())
            assertFalse(f.state.homeLoaded)
            assertTrue(f.sql.cataloguePositions.isEmpty(), "Catalogue reads must wait for remote progress")
            assertNull(f.state.open(), "Reopening an existing context must not start a second pull")

            f.releaseResponse()
            f.awaitStartup(job)

            assertTrue(f.state.ready)
            assertFalse(f.state.startupSyncing)
            assertNull(f.state.startupError)
            assertNull(f.state.toast)
            assertEquals("Remote movies", f.state.libraries.single().name)
            assertEquals(REMOTE_POSITION, f.state.home.resume.single().userData.positionMs)
            assertTrue(f.sql.cataloguePositions.isNotEmpty())
            assertTrue(f.sql.cataloguePositions.all { it == REMOTE_POSITION },
                "Initial and home catalogue reads observed stale progress: ${f.sql.cataloguePositions}")
            assertEquals(listOf("GET /daview-sync.json"), f.requests.toList())
            assertNotNull(f.core.config.sync.lastPullAt)
        }
    }

    @Test
    fun `disabled sync opens local progress without contacting storage`() = runBlocking {
        withFixture(enabled = false) { f ->
            f.awaitStartup(assertNotNull(f.state.open()))

            assertTrue(f.state.ready)
            assertFalse(f.state.startupSyncing)
            assertNull(f.state.startupError)
            assertNull(f.state.toast)
            assertEquals("Local movies", f.state.libraries.single().name)
            assertEquals(LOCAL_POSITION, f.state.home.resume.single().userData.positionMs)
            assertTrue(f.requests.isEmpty(), "Disabled sync made a network request: ${f.requests}")
            assertEquals(f.previousPullAt, f.core.config.sync.lastPullAt)
        }
    }

    @Test
    fun `unavailable remote progress preserves local home and reports the startup failure`() = runBlocking {
        withFixture(enabled = true, status = 503) { f ->
            val job = assertNotNull(f.state.open())
            f.awaitRequest()
            f.releaseResponse()
            f.awaitStartup(job)

            assertTrue(f.state.ready, "A failed sync must still open the local library")
            assertFalse(f.state.startupSyncing)
            assertNull(f.state.startupError)
            assertEquals(LOCAL_POSITION, f.state.home.resume.single().userData.positionMs)
            assertEquals("Local movies", f.state.libraries.single().name)
            val message = assertNotNull(f.state.toast).message
            assertTrue(message.contains("同步失败"), message)
            assertTrue(message.contains("本地进度"), message)
            assertTrue(message.contains("503"), "The toast must retain the cause: $message")
            assertNotNull(f.core.config.sync.lastError)
            assertEquals(f.previousPullAt, f.core.config.sync.lastPullAt)
            assertEquals(listOf("GET /daview-sync.json"), f.requests.toList())
        }
    }

    private suspend fun CoroutineScope.withFixture(
        enabled: Boolean,
        status: Int = 200,
        block: suspend (StartupFixture) -> Unit
    ) {
        val fixture = StartupFixture(this, enabled, status)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class StartupFixture(parent: CoroutineScope, enabled: Boolean, status: Int) {
        private val dir = createTempDirectory("daview-startup-sync")
        private val received = CountDownLatch(1)
        private val responsePermit = CountDownLatch(1)
        private val uncaught = ConcurrentLinkedQueue<Throwable>()
        val previousPullAt = if (enabled) System.currentTimeMillis() else null
        val requests = ConcurrentLinkedQueue<String>()
        val sql = CatalogueObservedSql(JdbcSqlDatabase(dir))
        val core = ServerContext(dir, sql)
        private val scope = CoroutineScope(parent.coroutineContext +
            SupervisorJob(parent.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, error -> uncaught += error })
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
                    received.countDown()
                    check(responsePermit.await(15, TimeUnit.SECONDS)) { "Test did not release the sync response" }
                    val body = if (status == 200) remotePayload() else "fixture unavailable"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        val state: AppState

        init {
            core.updateConfig { config ->
                config.copy(
                    storage = StorageConfig(url = "http://127.0.0.1:${server.address.port}"),
                    scraper = ScraperConfig(),
                    sync = config.sync.copy(enabled = enabled,
                        lastPullAt = previousPullAt, lastError = null)
                )
            }
            core.repository.upsertLibrary(LibraryDto(
                id = LIBRARY_ID, name = "Local movies", kind = LibraryKind.MOVIE,
                path = "/movies", updatedAt = 100
            ))
            core.repository.upsertItem(ItemRecord(MediaItemDto(
                id = ITEM_ID, libraryId = LIBRARY_ID, kind = ItemKind.MOVIE,
                name = "Isolated movie", path = "/movies/test.mkv", runtimeMs = 100_000
            )))
            core.repository.restoreUserData(ITEM_ID,
                UserDataDto(positionMs = LOCAL_POSITION, lastPlayedAt = 100), updatedAt = 100)
            val settings = MemorySettings().apply {
                putString("library.scanOnStartup", "0")
                putString("update.checkOnStartup", "0")
            }
            state = AppState(scope, settings, openContext = { core })
        }

        suspend fun awaitRequest() {
            assertTrue(withContext(Dispatchers.IO) { received.await(10, TimeUnit.SECONDS) },
                "Startup never requested remote progress")
        }

        fun releaseResponse() = responsePermit.countDown()

        suspend fun awaitStartup(job: Job) = withTimeout(10_000) {
            job.join()
            // Home reads are sibling jobs of open(), so joining open alone is insufficient.
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            assertTrue(state.homeLoaded, "Startup did not load the home shelves")
            assertNull(state.homeError)
            assertTrue(uncaught.isEmpty(), "Unexpected startup coroutine failures: $uncaught")
        }

        suspend fun close() {
            releaseResponse()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            core.close()
            server.stop(0)
            check(dir.toFile().deleteRecursively()) { "Unable to remove isolated test data: $dir" }
        }
    }

    /** Samples stored progress before actual catalogue reads, including reads hidden before ready. */
    private class CatalogueObservedSql(private val delegate: SqlDatabase) : SqlDatabase by delegate {
        val cataloguePositions = ConcurrentLinkedQueue<Long>()

        override fun <T> read(block: (SqlConnection) -> T): T = delegate.read { connection ->
            block(object : SqlConnection {
                override fun statement(sql: String) = connection.statement(sql).also {
                    if (sql.contains("FROM libraries l ORDER BY l.created_at")) {
                        val position = connection.statement("SELECT position_ms FROM user_data WHERE item_id = ?")
                            .apply { setString(1, ITEM_ID) }
                            .useQuery { cursor -> if (cursor.next()) cursor.getLong("position_ms") else -1L }
                        cataloguePositions += position
                    }
                }
            })
        }
    }

    companion object {
        private const val LIBRARY_ID = "startup-library"
        private const val ITEM_ID = "startup-movie"
        private const val LOCAL_POSITION = 9_000L
        private const val REMOTE_POSITION = 42_000L

        private fun remotePayload() = DaViewJson.encodeToString(BackupFileDto.serializer(), BackupFileDto(
            createdAt = 200,
            libraries = listOf(LibraryDto(
                id = LIBRARY_ID, name = "Remote movies", kind = LibraryKind.MOVIE,
                path = "/movies", updatedAt = 200
            )),
            userData = listOf(BackupUserDataDto(ITEM_ID,
                UserDataDto(positionMs = REMOTE_POSITION, lastPlayedAt = 200), updatedAt = 200))
        ))
    }
}
