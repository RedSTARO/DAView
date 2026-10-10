package com.daview.app.data

import com.daview.server.ServerContext
import com.daview.server.config.ScraperConfig
import com.daview.server.config.StorageConfig
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.SqlConnection
import com.daview.server.db.SqlCursor
import com.daview.server.db.SqlDatabase
import com.daview.server.db.SqlStatement
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.BackupUserDataDto
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType
import com.daview.shared.model.UserDataDto
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppStateStartupSyncTest {
    @Test
    fun `startup shows local home while sync is pending then refreshes the same state`() = runBlocking {
        withFixture(enabled = true) { f ->
            val job = assertNotNull(f.state.open())
            f.awaitRequest()
            f.awaitInitial(job)

            assertTrue(f.state.startupSyncing)
            assertTrue(f.state.ready, "The pending HTTP response must not block local startup")
            assertNotNull(f.state.serverInfo)
            assertEquals("Local movies", f.state.libraries.single().name)
            assertEquals(LOCAL_POSITION, f.state.home.resume.single().userData.positionMs)
            assertTrue(f.sql.cataloguePositions.isNotEmpty())
            assertTrue(f.sql.cataloguePositions.all { it == LOCAL_POSITION })
            val entry = f.state.currentEntry
            assertNull(f.state.open(), "Reopening an existing context must not start a second pull")

            f.releaseResponse()
            f.awaitMergedHome()

            assertTrue(f.state.ready)
            assertFalse(f.state.startupSyncing)
            assertNull(f.state.startupError)
            assertNull(f.state.toast)
            assertSame(entry, f.state.currentEntry, "Sync must refresh the existing page")
            assertEquals("Remote movies", f.state.libraries.single().name)
            assertEquals(REMOTE_POSITION, f.state.home.resume.single().userData.positionMs)
            assertEquals(REMOTE_POSITION, f.sql.cataloguePositions.last())
            assertEquals(listOf("GET /daview-sync.json"), f.requests.toList())
            assertNotNull(f.core.config.sync.lastPullAt)
        }
    }

    @Test
    fun `sync refreshes the active library while preserving its filters search and navigation`() = runBlocking {
        withFixture(enabled = true) { f ->
            f.awaitInitial(assertNotNull(f.state.open()))
            f.awaitRequest()
            f.state.navigate(Screen.Library(LIBRARY_ID))
            f.state.updateView(LIBRARY_ID) { it.copy(onlyFavourite = true, descending = true) }
            f.state.setLibrarySearch(LIBRARY_ID, "Isolated")
            f.awaitState {
                !f.state.libraryLoading && !f.state.libraryRefreshing &&
                    f.state.libraryItems.singleOrNull()?.userData?.positionMs == LOCAL_POSITION
            }
            val entry = f.state.currentEntry
            val stack = f.state.backStack.toList()
            val view = f.state.viewOf(LIBRARY_ID)
            val scrollChanges = f.state.libraryScrollToTop
            assertEquals(1, f.state.libraryTotal, "Both search and favourite filter must be applied")

            f.releaseResponse()
            f.awaitState {
                !f.state.startupSyncing && !f.state.libraryRefreshing &&
                    f.state.libraryItems.singleOrNull()?.userData?.positionMs == REMOTE_POSITION
            }
            f.awaitMergedHome()

            assertSame(entry, f.state.currentEntry)
            assertEquals(stack, f.state.backStack.toList())
            assertEquals(view, f.state.viewOf(LIBRARY_ID))
            assertEquals("Isolated", f.state.searchOf(LIBRARY_ID))
            assertEquals(scrollChanges, f.state.libraryScrollToTop, "Sync must not reset the library scroll")
            assertEquals(listOf(ITEM_ID), f.state.libraryItems.map { it.id })
            assertEquals(1, f.state.libraryTotal)
            assertNull(f.state.libraryError)
            assertEquals("Remote movies", f.state.libraries.single().name)
        }
    }

    @Test
    fun `sync refreshes details opened during the pending pull without replacing their page`() = runBlocking {
        withFixture(enabled = true) { f ->
            f.awaitInitial(assertNotNull(f.state.open()))
            f.awaitRequest()
            f.state.navigate(Screen.Detail(ITEM_ID))
            f.awaitState { !f.state.detailLoading && f.state.detailItem?.userData?.positionMs == LOCAL_POSITION }
            val entry = f.state.currentEntry
            val stack = f.state.backStack.toList()

            f.releaseResponse()
            f.awaitState { !f.state.startupSyncing && !f.state.detailLoading &&
                f.state.detailItem?.userData?.positionMs == REMOTE_POSITION }
            f.awaitMergedHome()

            assertSame(entry, f.state.currentEntry)
            assertEquals(stack, f.state.backStack.toList())
            assertEquals(Screen.Detail(ITEM_ID), f.state.current)
            assertNull(f.state.detailError)
        }
    }

    @Test
    fun `background merge leaves the active player and its navigation entry intact`() = runBlocking {
        withFixture(enabled = true) { f ->
            f.awaitInitial(assertNotNull(f.state.open()))
            f.awaitRequest()
            f.state.navigate(Screen.Player(ITEM_ID))
            val entry = f.state.currentEntry
            val stack = f.state.backStack.toList()
            var stopped = 0
            f.state.leavingPlayer = { stopped++ }

            f.releaseResponse()
            f.awaitMergedHome()

            assertSame(entry, f.state.currentEntry)
            assertEquals(stack, f.state.backStack.toList())
            assertEquals(Screen.Player(ITEM_ID), f.state.current)
            assertEquals(0, stopped, "Refreshing synced data must not close playback")
        }
    }

    @Test
    fun `a slow initial home read cannot overwrite the home published after sync`() = runBlocking {
        withFixture(enabled = true) { f ->
            f.sql.holdFirstResume.set(true)
            val job = assertNotNull(f.state.open())
            f.awaitRequest()
            f.awaitState { f.state.ready }
            f.sql.awaitHeldResume()
            assertTrue(f.state.ready, "Initial home IO must not block opening the app")

            f.releaseResponse()
            f.awaitMergedHome()
            f.sql.releaseResume()
            f.awaitAllWork()

            assertTrue(job.isCompleted)
            assertEquals(REMOTE_POSITION, f.state.home.resume.single().userData.positionMs)
            assertEquals("Remote movies", f.state.libraries.single().name)
            assertFalse(f.state.homeRefreshing)
        }
    }

    @Test
    fun `an older resume shelf response cannot overwrite its refreshed synced data`() = runBlocking {
        withFixture(enabled = true) { f ->
            f.awaitInitial(assertNotNull(f.state.open()))
            f.awaitRequest()
            f.sql.holdFirstResume.set(true)
            val shelf = Screen.Shelf(ShelfKind.RESUME)
            f.state.navigate(shelf)
            f.sql.awaitHeldResume()
            val entry = f.state.currentEntry

            f.releaseResponse()
            f.awaitState { !f.state.startupSyncing && !f.state.shelfLoading &&
                f.state.shelfItems.singleOrNull()?.userData?.positionMs == REMOTE_POSITION }
            f.awaitMergedHome()
            f.sql.releaseResume()
            f.awaitAllWork()

            assertSame(entry, f.state.currentEntry)
            assertEquals(shelf, f.state.current)
            assertEquals(shelf, f.state.shelfOf)
            assertEquals(REMOTE_POSITION, f.state.shelfItems.single().userData.positionMs)
            assertFalse(f.state.shelfLoading)
            assertNull(f.state.shelfError)
        }
    }

    @Test
    fun `old pagination cannot append after sync refreshes the same search query`() = runBlocking {
        withFixture(enabled = true) { f ->
            f.seedSearchItems()
            f.additionalRemoteUserData += BackupUserDataDto("paging-00", UserDataDto(favorite = true), 200)
            f.additionalRemoteUserData += BackupUserDataDto("paging-64", UserDataDto(favorite = true), 200)
            f.awaitInitial(assertNotNull(f.state.open()))
            f.awaitRequest()
            f.state.searchFor("Paging")
            f.awaitState { !f.state.searchLoading && f.state.searchResults.size == SEARCH_PAGE_SIZE }
            assertEquals(65, f.state.searchTotal)
            assertFalse(f.state.searchResults.first().userData.favorite)
            val entry = f.state.currentEntry
            val stack = f.state.backStack.toList()

            f.sql.holdSearchPage.set(true)
            f.state.loadMoreSearch()
            f.sql.awaitHeldSearchPage()
            assertTrue(f.state.searchLoadingMore)
            f.releaseResponse()
            f.awaitState { !f.state.startupSyncing && !f.state.searchLoading &&
                f.state.searchResults.firstOrNull()?.userData?.favorite == true }
            f.awaitMergedHome()
            f.sql.releaseSearchPage()
            f.awaitAllWork()

            assertSame(entry, f.state.currentEntry)
            assertEquals(stack, f.state.backStack.toList())
            assertEquals(Screen.Search, f.state.current)
            assertEquals("Paging", f.state.searchQuery)
            assertEquals(SEARCH_PAGE_SIZE, f.state.searchResults.size,
                "The stale second page appended after the same-query refresh")
            assertFalse(f.state.searchLoadingMore)

            f.state.loadMoreSearch()
            f.awaitState { !f.state.searchLoadingMore && f.state.searchResults.size == 65 }
            assertEquals(65, f.state.searchResults.map { it.id }.toSet().size)
            assertTrue(f.state.searchResults.single { it.id == "paging-64" }.userData.favorite,
                "Fresh pagination must read the synced state rather than reusing its held rows")
        }
    }

    @Test
    fun `background search refresh preserves all previously loaded pages`() = runBlocking {
        withFixture(enabled = true) { f ->
            f.seedSearchItems()
            f.additionalRemoteUserData += BackupUserDataDto("paging-00", UserDataDto(favorite = true), 200)
            f.additionalRemoteUserData += BackupUserDataDto("paging-64", UserDataDto(favorite = true), 200)
            f.awaitInitial(assertNotNull(f.state.open()))
            f.awaitRequest()
            f.state.searchFor("Paging")
            f.awaitState { !f.state.searchLoading && f.state.searchResults.size == SEARCH_PAGE_SIZE }
            f.state.loadMoreSearch()
            f.awaitState { !f.state.searchLoadingMore && f.state.searchResults.size == 65 }
            val loadedIds = f.state.searchResults.map { it.id }
            val entry = f.state.currentEntry

            f.releaseResponse()
            f.awaitState { !f.state.startupSyncing && !f.state.searchLoading &&
                f.state.searchResults.firstOrNull()?.userData?.favorite == true &&
                f.state.searchResults.lastOrNull()?.userData?.favorite == true }
            f.awaitMergedHome()

            assertSame(entry, f.state.currentEntry)
            assertEquals("Paging", f.state.searchQuery)
            assertEquals(loadedIds, f.state.searchResults.map { it.id },
                "A data refresh must retain loaded rows that may be under the current scroll position")
            assertEquals(65, f.state.searchTotal)
            assertFalse(f.state.searchLoadingMore)
        }
    }

    @Test
    fun `sync refreshes cached search while viewing details before returning to search`() = runBlocking {
        withFixture(enabled = true) { f ->
            f.seedSearchItems()
            f.additionalRemoteUserData += BackupUserDataDto("paging-00", UserDataDto(favorite = true), 200)
            f.awaitInitial(assertNotNull(f.state.open()))
            f.awaitRequest()
            f.state.searchFor("Paging")
            f.awaitState { !f.state.searchLoading && f.state.searchResults.size == SEARCH_PAGE_SIZE }
            val searchEntry = f.state.currentEntry
            assertFalse(f.state.searchResults.first().userData.favorite)
            f.state.navigate(Screen.Detail(ITEM_ID))
            f.awaitState { !f.state.detailLoading && f.state.detailItem?.userData?.positionMs == LOCAL_POSITION }
            val detailEntry = f.state.currentEntry

            f.releaseResponse()
            f.awaitState { !f.state.startupSyncing && !f.state.searchLoading && !f.state.detailLoading &&
                f.state.searchResults.firstOrNull()?.userData?.favorite == true &&
                f.state.detailItem?.userData?.positionMs == REMOTE_POSITION }
            f.awaitMergedHome()

            assertSame(detailEntry, f.state.currentEntry, "Refreshing cached search must not navigate away")
            assertTrue(f.state.back())
            assertSame(searchEntry, f.state.currentEntry)
            assertEquals(Screen.Search, f.state.current)
            assertEquals("Paging", f.state.searchQuery)
            assertEquals(SEARCH_PAGE_SIZE, f.state.searchResults.size)
            assertTrue(f.state.searchResults.first().userData.favorite,
                "Returning to Search must not reuse the cached pre-sync result")
            assertEquals(65, f.state.searchTotal)
        }
    }

    @Test
    fun `disabled sync opens local progress without contacting storage`() = runBlocking {
        withFixture(enabled = false) { f ->
            f.awaitInitial(assertNotNull(f.state.open()))
            f.awaitState { !f.state.startupSyncing }

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
            f.awaitInitial(job)
            assertTrue(f.state.ready, "A pending failed request must not block local startup")
            f.releaseResponse()
            f.awaitState { !f.state.startupSyncing && f.state.toast != null }

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
        private val unexpectedRequests = ConcurrentLinkedQueue<String>()
        val previousPullAt = if (enabled) System.currentTimeMillis() else null
        val requests = ConcurrentLinkedQueue<String>()
        val additionalRemoteUserData = ConcurrentLinkedQueue<BackupUserDataDto>()
        val sql = CatalogueObservedSql(JdbcSqlDatabase(dir))
        val core = ServerContext(dir, sql)
        private val scope = CoroutineScope(parent.coroutineContext +
            SupervisorJob(parent.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, error -> uncaught += error })
        private val httpExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "daview-startup-fixture-http").apply { isDaemon = true }
        }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = httpExecutor
            createContext("/") { exchange ->
                try {
                    val request = "${exchange.requestMethod} ${exchange.requestURI}"
                    requests += request
                    if (exchange.requestMethod != "GET" || exchange.requestURI.toString() != "/daview-sync.json") {
                        // An unexpected media probe must fail immediately even while sync is held.
                        unexpectedRequests += request
                        exchange.sendResponseHeaders(if (exchange.requestMethod == "GET") 404 else 405, -1)
                        return@createContext
                    }
                    received.countDown()
                    check(responsePermit.await(15, TimeUnit.SECONDS)) { "Test did not release the sync response" }
                    val body = if (status == 200) remotePayload(additionalRemoteUserData.toList()) else "fixture unavailable"
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
                name = "Isolated movie", path = "/movies/test.mkv", runtimeMs = 100_000,
                // Detail reads should use known local metadata, without probing a fake media URL.
                mediaStreams = listOf(MediaStreamDto(index = 0, type = StreamType.VIDEO, codec = "h264"))
            ), probedAt = 100))
            core.repository.restoreUserData(ITEM_ID,
                UserDataDto(positionMs = LOCAL_POSITION, lastPlayedAt = 100, favorite = true), updatedAt = 100)
            // Each decoy fails a different predicate, so losing either search or favourite
            // filtering during a refresh changes the result rather than passing unnoticed.
            core.repository.upsertItem(ItemRecord(MediaItemDto(
                id = "other-favourite", libraryId = LIBRARY_ID, kind = ItemKind.MOVIE,
                name = "Unrelated favourite", path = "/movies/favourite.mkv"
            )))
            core.repository.restoreUserData("other-favourite", UserDataDto(favorite = true), updatedAt = 100)
            core.repository.upsertItem(ItemRecord(MediaItemDto(
                id = "other-search-match", libraryId = LIBRARY_ID, kind = ItemKind.MOVIE,
                name = "Isolated non favourite", path = "/movies/non-favourite.mkv"
            )))
            val settings = MemorySettings().apply {
                putString("library.scanOnStartup", "0")
                putString("update.checkOnStartup", "0")
            }
            state = AppState(scope, settings, openContext = { core })
        }

        fun seedSearchItems() = core.repository.upsertItems((0 until 65).map { index ->
            val number = index.toString().padStart(2, '0')
            ItemRecord(MediaItemDto(
                id = "paging-$number", libraryId = LIBRARY_ID, kind = ItemKind.MOVIE,
                name = "Paging $number", sortName = "Paging $number", path = "/movies/paging-$number.mkv"
            ))
        })

        suspend fun awaitRequest() {
            assertTrue(withContext(Dispatchers.IO) { received.await(10, TimeUnit.SECONDS) },
                "Startup never requested remote progress")
            assertHealthy()
        }

        fun releaseResponse() = responsePermit.countDown()

        suspend fun awaitInitial(job: Job) = withTimeout(10_000) {
            job.join()
            awaitState { state.ready && state.homeLoaded && !state.homeRefreshing }
            assertTrue(state.homeLoaded, "Startup did not load the home shelves")
            assertNull(state.homeError)
            assertHealthy()
        }

        suspend fun awaitMergedHome() = awaitState {
            !state.startupSyncing && !state.homeRefreshing &&
                state.home.resume.singleOrNull()?.userData?.positionMs == REMOTE_POSITION &&
                state.libraries.singleOrNull()?.name == "Remote movies"
        }

        suspend fun awaitState(condition: () -> Boolean) = withTimeout(10_000) {
            while (true) {
                assertHealthy()
                if (condition()) return@withTimeout
                delay(5)
            }
        }

        suspend fun awaitAllWork() = withTimeout(10_000) {
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            assertHealthy()
        }

        private fun assertHealthy() {
            assertTrue(uncaught.isEmpty(), "Unexpected startup coroutine failures: $uncaught")
            assertTrue(unexpectedRequests.isEmpty(), "Unexpected fixture HTTP requests: $unexpectedRequests")
        }

        suspend fun close() {
            releaseResponse()
            sql.releaseResume()
            sql.releaseSearchPage()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            core.close()
            server.stop(0)
            httpExecutor.shutdownNow()
            check(dir.toFile().deleteRecursively()) { "Unable to remove isolated test data: $dir" }
            assertHealthy()
        }
    }

    /** Records real catalogue read values and can withhold one completed stale resume result. */
    private class CatalogueObservedSql(private val delegate: SqlDatabase) : SqlDatabase by delegate {
        val cataloguePositions = ConcurrentLinkedQueue<Long>()
        val holdFirstResume = AtomicBoolean(false)
        val holdSearchPage = AtomicBoolean(false)
        private val resumeHeld = CountDownLatch(1)
        private val resumePermit = CountDownLatch(1)
        private val searchPageHeld = CountDownLatch(1)
        private val searchPagePermit = CountDownLatch(1)

        suspend fun awaitHeldResume() {
            assertTrue(withContext(Dispatchers.IO) { resumeHeld.await(10, TimeUnit.SECONDS) },
                "Initial home never read the local resume rows")
        }

        fun releaseResume() = resumePermit.countDown()

        suspend fun awaitHeldSearchPage() {
            assertTrue(withContext(Dispatchers.IO) { searchPageHeld.await(10, TimeUnit.SECONDS) },
                "Search pagination never materialized its second page")
        }

        fun releaseSearchPage() = searchPagePermit.countDown()

        override fun <T> read(block: (SqlConnection) -> T): T = delegate.read { connection ->
            block(object : SqlConnection {
                override fun statement(sql: String): SqlStatement {
                    if (sql.contains("FROM libraries l ORDER BY l.created_at")) {
                        val position = connection.statement("SELECT position_ms FROM user_data WHERE item_id = ?")
                            .apply { setString(1, ITEM_ID) }
                            .useQuery { cursor -> if (cursor.next()) cursor.getLong("position_ms") else -1L }
                        cataloguePositions += position
                    }
                    val statement = connection.statement(sql)
                    val isResume = sql.contains("WHERE u.position_ms > 0")
                    val isSearchPage = sql.contains("ORDER BY CASE WHEN lower(i.name)") &&
                        sql.contains("i.parent_id IS NULL") && sql.endsWith("LIMIT ? OFFSET ?")
                    if (!isResume && !isSearchPage) return statement
                    val offsetIndex = sql.count { it == '?' }
                    return object : SqlStatement by statement {
                        private var offset = 0

                        override fun setInt(index: Int, value: Int) {
                            statement.setInt(index, value)
                            if (isSearchPage && index == offsetIndex) offset = value
                        }

                        override fun <T> useQuery(block: (SqlCursor) -> T): T {
                            val result = statement.useQuery(block)
                            if (isResume && holdFirstResume.compareAndSet(true, false)) {
                                resumeHeld.countDown()
                                check(resumePermit.await(15, TimeUnit.SECONDS)) {
                                    "Test did not release the stale resume result"
                                }
                            }
                            if (isSearchPage && offset == SEARCH_PAGE_SIZE &&
                                holdSearchPage.compareAndSet(true, false)) {
                                searchPageHeld.countDown()
                                check(searchPagePermit.await(15, TimeUnit.SECONDS)) {
                                    "Test did not release the stale search page"
                                }
                            }
                            return result
                        }
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
        private const val SEARCH_PAGE_SIZE = 60

        private fun remotePayload(additionalUserData: List<BackupUserDataDto> = emptyList()) =
            DaViewJson.encodeToString(BackupFileDto.serializer(), BackupFileDto(
                createdAt = 200,
                libraries = listOf(LibraryDto(
                    id = LIBRARY_ID, name = "Remote movies", kind = LibraryKind.MOVIE,
                    path = "/movies", updatedAt = 200
                )),
                userData = listOf(BackupUserDataDto(ITEM_ID,
                    UserDataDto(positionMs = REMOTE_POSITION, lastPlayedAt = 200, favorite = true), updatedAt = 200)) +
                    additionalUserData
            ))
    }
}
