package com.daview.server.media

import com.daview.server.config.AppConfig
import com.daview.server.db.Database
import com.daview.server.db.ItemRecord
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.server.library.Scanner
import com.daview.server.library.TransportStreams
import com.daview.server.scraper.MetadataService
import com.daview.server.storage.DavEntry
import com.daview.server.storage.DirectoryLister
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.ScanMode
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real SQLite rows and container parsing; latches establish every race's ordering. */
class ProbeCancellationTest {
    private val dir = createTempDirectory("daview-probe-cancellation")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private val bytes = TransportStreams.kaiji(packetSize = 192)
    private val local = dir.resolve("clip.m2ts").also { it.writeBytes(bytes) }
    private val streams = StreamService({ null }, repository)
    private val library = LibraryDto("lib", "Films", LibraryKind.MOVIE, "/Films")

    @AfterTest
    fun close() = database.close()

    private fun items(count: Int) = (1..count).map { index ->
        val path = "/Films/Film $index (2020).m2ts"
        MediaItemDto(
            id = Scanner.itemId(library.id, path), libraryId = library.id,
            kind = ItemKind.MOVIE, name = "Film $index", path = path, sizeBytes = bytes.size.toLong()
        )
    }

    private fun seed(count: Int) = items(count).also { items ->
        repository.upsertItems(items.map { ItemRecord(it) })
    }

    private fun assertUnprobed(items: List<MediaItemDto>) {
        items.forEach { item ->
            val record = assertNotNull(repository.itemRecord(item.id))
            assertNull(record.probedAt, item.name)
            assertNull(record.dto.runtimeMs, item.name)
            assertTrue(record.dto.mediaStreams.isEmpty(), item.name)
        }
    }

    private class Async<T>(block: () -> T) {
        private val task = FutureTask { runCatching(block) }
        val thread = Thread(task, "probe-test-coordinator").apply { isDaemon = true; start() }
        fun outcome(): Result<T> = task.get(5, TimeUnit.SECONDS)
    }

    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS), "latch timed out")

    private fun join(threads: Collection<Thread>) {
        threads.forEach { thread ->
            thread.join(5_000)
            assertFalse(thread.isAlive, "thread did not finish: ${thread.name}")
        }
    }

    /** Simulates an I/O operation that returns successfully even after interrupt(). */
    private class BlockedFiles(count: Int, private val local: Path) {
        val entered = CountDownLatch(count)
        val release = CountDownLatch(1)
        val threads = ConcurrentHashMap.newKeySet<Thread>()
        val paths = ConcurrentHashMap.newKeySet<String>()

        fun file(path: String): Path {
            threads += Thread.currentThread()
            paths += path
            entered.countDown()
            while (true) {
                try {
                    release.await()
                    return local
                } catch (_: InterruptedException) {
                    // Deliberately ignore interruption to exercise late success.
                }
            }
        }
    }

    @Test
    fun `a fixed number of workers parses all items and callbacks run on the coordinator`() {
        val items = seed(9)
        val blocked = BlockedFiles(3, local)
        streams.offlineFile = blocked::file
        val progress = mutableListOf<Int>()
        val callbackThreads = mutableSetOf<Thread>()
        val call = Async {
            streams.probeMissing(library.id, 100, parallelism = 3) { current, total, _ ->
                progress += current
                callbackThreads += Thread.currentThread()
                assertEquals(items.size, total)
            }
        }
        try {
            await(blocked.entered)
            assertEquals(3, blocked.paths.size)
            assertUnprobed(items)
            blocked.release.countDown()
            call.outcome().getOrThrow()
            join(blocked.threads)
            assertEquals(3, blocked.threads.size, "reuse workers instead of creating one thread per item")
            assertEquals((1..items.size).toList(), progress)
            assertEquals(setOf(call.thread), callbackThreads)
            items.forEach {
                val record = assertNotNull(repository.itemRecord(it.id))
                assertNotNull(record.probedAt)
                assertEquals(1_357_354L, record.dto.runtimeMs)
                assertEquals(listOf(4113, 4352, 4608), record.dto.mediaStreams.map { stream -> stream.index })
            }
        } finally {
            blocked.release.countDown()
            join(listOf(call.thread) + blocked.threads)
        }
    }

    @Test
    fun `callback failure is rethrown without dispatching another item or accepting late results`() {
        val items = seed(5)
        val pending = repository.itemsNeedingProbe(library.id, 100)
        val blocked = BlockedFiles(1, local)
        val started = ConcurrentHashMap.newKeySet<String>()
        streams.offlineFile = { path ->
            started += path
            if (path == pending.first().path) {
                await(blocked.entered)
                local
            } else blocked.file(path)
        }
        val failure = IllegalStateException("progress failed")
        val callbacks = AtomicInteger()
        val callbackThread = LinkedBlockingQueue<Thread>()
        val call = Async {
            streams.probeMissing(library.id, 100, parallelism = 2) { _, _, _ ->
                callbackThread.add(Thread.currentThread())
                callbacks.incrementAndGet()
                throw failure
            }
        }
        try {
            assertSame(failure, call.outcome().exceptionOrNull())
            assertSame(call.thread, callbackThread.poll())
            assertEquals(2, started.size)
            assertEquals(1, callbacks.get())
            assertNotNull(repository.itemRecord(pending.first().id)?.probedAt)
            assertUnprobed(items.filter { it.id != pending.first().id })
        } finally {
            blocked.release.countDown()
            join(listOf(call.thread) + blocked.threads)
        }
        assertEquals(2, started.size)
        assertEquals(1, callbacks.get())
        assertUnprobed(items.filter { it.id != pending.first().id })
    }

    @Test
    fun `deadline expires while all readers are blocked and late results stay unprobed`() {
        val items = seed(5)
        val blocked = BlockedFiles(2, local)
        streams.offlineFile = blocked::file
        val now = AtomicLong()
        val callbacks = AtomicInteger()
        val call = Async {
            streams.probeMissing(
                library.id, 100, parallelism = 2, cancellation = ScanService.Cancellation(),
                timeoutMillis = 1_000, nanoTime = now::get
            ) { _, _, _ -> callbacks.incrementAndGet() }
        }
        try {
            await(blocked.entered)
            now.set(TimeUnit.SECONDS.toNanos(1))
            assertIs<TimeoutException>(call.outcome().exceptionOrNull())
            assertEquals(2, blocked.paths.size)
            assertEquals(0, callbacks.get())
            assertUnprobed(items)
        } finally {
            blocked.release.countDown()
            join(listOf(call.thread) + blocked.threads)
        }
        assertEquals(2, blocked.paths.size)
        assertEquals(0, callbacks.get())
        assertUnprobed(items)
    }

    @Test
    fun `interrupting the coordinator stops dispatch and preserves its interrupt flag`() {
        val items = seed(4)
        val blocked = BlockedFiles(1, local)
        streams.offlineFile = blocked::file
        val interrupted = AtomicBoolean()
        val call = Async {
            try {
                streams.probeMissing(library.id, 100, parallelism = 1) { _, _, _ -> error("unexpected progress") }
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        try {
            await(blocked.entered)
            call.thread.interrupt()
            assertIs<InterruptedException>(call.outcome().exceptionOrNull())
            assertTrue(interrupted.get())
            assertEquals(1, blocked.paths.size)
        } finally {
            blocked.release.countDown()
            join(listOf(call.thread) + blocked.threads)
        }
        assertEquals(1, blocked.paths.size)
        assertUnprobed(items)
    }

    private class ScanStep(val lister: DirectoryLister?) {
        val entered = CountDownLatch(1)
    }

    private fun newScans(steps: LinkedBlockingQueue<ScanStep>, config: () -> AppConfig = { AppConfig() }) = ScanService(
        repository, MetadataService(repository, emptyMap()), streams,
        davProvider = {
            val step = steps.remove()
            step.entered.countDown()
            step.lister
        },
        configProvider = config
    )

    private fun tree(items: List<MediaItemDto>) = object : DirectoryLister {
        override fun list(relativePath: String) = if (relativePath == library.path) items.map {
            val path = requireNotNull(it.path)
            DavEntry(path.substringAfterLast('/'), path, false, it.sizeBytes, null, null)
        } else emptyList()
    }

    /** A following queued scan entering proves the preceding scan has finished its finally block. */
    private fun awaitScan(scans: ScanService, steps: LinkedBlockingQueue<ScanStep>, marker: String) {
        val barrier = ScanStep(null)
        steps.add(barrier)
        scans.submit(library.copy(id = marker), ScanMode.MISSING)
        await(barrier.entered)
    }

    @Test
    fun `cancel during real probing finishes promptly and late workers cannot overwrite a rescan`() {
        val items = items(12)
        val blocked = BlockedFiles(6, local)
        streams.offlineFile = blocked::file
        val steps = LinkedBlockingQueue<ScanStep>()
        val scans = newScans(steps)
        steps.add(ScanStep(tree(items)))
        scans.submit(library, ScanMode.FULL)
        try {
            await(blocked.entered)
            scans.cancel(library.id)
            awaitScan(scans, steps, "after-cancel")
            val cancelled = scans.status().single { it.libraryId == library.id }
            assertEquals("cancelled", cancelled.phase)
            assertFalse(cancelled.running)
            assertNull(cancelled.error)
            assertNotNull(cancelled.finishedAt)
            assertEquals(6, blocked.paths.size, "the other six probes must not start")
            assertUnprobed(items)

            // Old I/O is still blocked while the same library completes a new scan.
            streams.offlineFile = { local }
            steps.add(ScanStep(tree(items)))
            scans.submit(library, ScanMode.FULL)
            awaitScan(scans, steps, "after-rescan")
            val done = scans.status().single { it.libraryId == library.id }
            assertEquals("done", done.phase)
            assertFalse(done.running)
            items.forEach { assertNotNull(repository.itemRecord(it.id)?.probedAt) }
            val edited = assertNotNull(repository.itemRecord(items.first().id))
            repository.upsertItem(edited.copy(dto = edited.dto.copy(name = "Kept after rescan")))
            val records = items.map { repository.itemRecord(it.id) }

            blocked.release.countDown()
            join(blocked.threads)
            assertEquals(records, items.map { repository.itemRecord(it.id) }, "late probes must not write")
            assertEquals(done, scans.status().single { it.libraryId == library.id }, "late progress must not revive a scan")
            assertEquals(6, blocked.paths.size)
        } finally {
            scans.cancel(library.id)
            blocked.release.countDown()
            join(blocked.threads)
            awaitScan(scans, steps, "cleanup")
        }
    }

    @Test
    fun `cancellation before the final done update wins even when there are no progress callbacks`() {
        val steps = LinkedBlockingQueue<ScanStep>()
        lateinit var scans: ScanService
        scans = newScans(steps) {
            scans.cancel(library.id)
            AppConfig()
        }
        steps.add(ScanStep(tree(emptyList())))
        scans.submit(library, ScanMode.MISSING)
        awaitScan(scans, steps, "after-missing")
        val result = scans.status().single { it.libraryId == library.id }
        assertEquals("cancelled", result.phase)
        assertFalse(result.running)
        assertNull(result.error)
    }

    @Test
    fun `an already cancelled batch starts no readers and reports no progress`() {
        val items = seed(3)
        val started = AtomicInteger()
        streams.offlineFile = { started.incrementAndGet(); local }
        val cancellation = ScanService.Cancellation().apply { cancel() }
        val callbacks = AtomicInteger()

        val outcome = runCatching {
            streams.probeMissing(library.id, 100, cancellation = cancellation) { _, _, _ ->
                callbacks.incrementAndGet()
            }
        }

        assertEquals("已取消", assertNotNull(outcome.exceptionOrNull()).message)
        assertEquals(0, started.get())
        assertEquals(0, callbacks.get())
        assertUnprobed(items)
    }
}
