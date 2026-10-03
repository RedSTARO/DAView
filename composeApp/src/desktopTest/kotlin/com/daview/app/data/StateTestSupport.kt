package com.daview.app.data

import com.daview.app.platform.SettingsStore
import com.daview.shared.model.ItemKind
import com.daview.shared.model.ItemPage
import com.daview.shared.model.MediaItemDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.assertTrue

/** No real threads, database, player, or coroutines-test dependency. */
internal class QueuedDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(tasks) { tasks.addLast(block) }
    }
    fun drain() {
        var count = 0
        while (true) {
            val next = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() } ?: return
            check(++count < 10_000) { "Coroutine queue did not settle" }
            next.run()
        }
    }
}

/** Deliberately ignores cancellation, like a disk/native call already in flight. */
internal class PendingRead<T> {
    private var continuation: Continuation<T>? = null
    val waiting get() = continuation != null
    suspend fun await(): T = suspendCoroutine {
        check(continuation == null)
        continuation = it
    }
    fun complete(value: T) {
        val pending = checkNotNull(continuation)
        continuation = null
        pending.resume(value)
    }
    fun fail(error: Exception) {
        val pending = checkNotNull(continuation)
        continuation = null
        pending.resumeWithException(error)
    }
}

internal class MemorySettings : SettingsStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

internal fun media(id: String, kind: ItemKind = ItemKind.MOVIE) = MediaItemDto(
    id = id, libraryId = "library", kind = kind, name = id,
    path = if (kind == ItemKind.MOVIE || kind == ItemKind.EPISODE) "/$id.mkv" else "/$id"
)

internal class CatalogStub : CatalogReads {
    var readItem: suspend (String) -> MediaItemDto = { media(it) }
    var readChildren: suspend (String) -> List<MediaItemDto> = { emptyList() }
    var readNext: suspend (String) -> MediaItemDto? = { null }
    var readPage: suspend (LibraryQuery, Int, Int) -> ItemPage = { _, offset, _ -> ItemPage(emptyList(), 0, offset) }
    override suspend fun item(id: String) = readItem(id)
    override suspend fun children(id: String) = readChildren(id)
    override suspend fun nextEpisode(id: String) = readNext(id)
    override suspend fun page(query: LibraryQuery, offset: Int, limit: Int) = readPage(query, offset, limit)
    override suspend fun genres(libraryId: String) = emptyList<String>()
    override suspend fun years(libraryId: String) = emptyList<Int>()
}

internal class StateFixture : AutoCloseable {
    val dispatcher = QueuedDispatcher()
    val uncaught = mutableListOf<Throwable>()
    val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> uncaught += e })
    val catalog = CatalogStub()
    val state = AppState(scope, MemorySettings(), catalog)
    fun drain() = dispatcher.drain()
    override fun close() {
        scope.cancel()
        drain()
        assertTrue(uncaught.isEmpty(), "Unexpected coroutine failures: $uncaught")
    }
}
