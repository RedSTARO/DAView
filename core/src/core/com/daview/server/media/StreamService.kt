package com.daview.server.media

import com.daview.server.db.ItemRecord
import com.daview.server.db.Repository
import com.daview.server.io.readUpTo
import com.daview.server.library.MkvProbe
import com.daview.server.library.Mp4Probe
import com.daview.server.library.TsProbe
import com.daview.server.storage.WebDavClient
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MediaStreamDto
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything that needs to touch the actual media bytes: resolving playable
 * URLs, caching the short-lived CDN links, and probing containers for their
 * track list, duration and cue index.
 */
class StreamService(
    private val davProvider: () -> WebDavClient?,
    private val repository: Repository
) : RangeSource {
    /**
     * Where to look for a copy already on this device, set once the offline
     * library exists. It is a lambda rather than a constructor argument because
     * the offline library reads bytes through this service, and one of the two
     * has to be built first.
     */
    var offlineFile: ((String) -> java.nio.file.Path?)? = null

    private val log = LoggerFactory.getLogger(StreamService::class.java)

    private data class CachedUrl(val url: String, val expiresAt: Long)

    private val directUrls = ConcurrentHashMap<String, CachedUrl>()
    private val cueIndexes = ConcurrentHashMap<String, MkvProbe.CueIndex>()

    private fun dav(): WebDavClient = davProvider() ?: error("WebDAV 尚未配置")

    /**
     * The signed CDN link the storage backend returns is valid for far longer
     * than an hour, but it is re-resolved well before expiry so a long film
     * never dies half way through.
     */
    fun directUrl(path: String): String? {
        // A file already on this device has no direct link, and offering one
        // would send a player back to the network for bytes that are on disk.
        if (offlineFile?.invoke(path) != null) return null
        val cached = directUrls[path]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached.url
        val resolved = runCatching { dav().resolveDirectUrl(path) }
            .onFailure { log.warn("解析直链失败 {}: {}", path, it.message) }
            .getOrNull() ?: return null
        directUrls[path] = CachedUrl(resolved, System.currentTimeMillis() + DIRECT_URL_TTL_MS)
        return resolved
    }

    fun invalidate(path: String) {
        directUrls.remove(path)
    }

    fun absoluteUrl(path: String): String = dav().absoluteUrl(path)

    /**
     * Opens a byte range, reusing the cached CDN link so a seek costs one
     * request instead of two (redirect + fetch).
     */
    override fun openRange(path: String, start: Long, end: Long?): WebDavClient.RangeStream {
        // The whole point of downloading something is that this read stops
        // going to the network. Both players and the local pipe come through
        // here, so one check covers all of them — and progress tracking for an
        // external player keeps working exactly as it did.
        offlineFile?.invoke(path)?.let { return localRange(it, start, end) }
        val direct = directUrl(path)
        return if (direct != null) {
            runCatching { dav().openRangeAt(direct, start, end, useAuth = false) }
                .getOrElse {
                    // The signed link can expire mid-playback; drop it and retry.
                    invalidate(path)
                    dav().openRange(path, start, end)
                }
        } else {
            dav().openRange(path, start, end)
        }
    }

    override fun fileSize(path: String): Long? =
        offlineFile?.invoke(path)?.let { runCatching { java.nio.file.Files.size(it) }.getOrNull() }
            ?: dav().size(path)

    /** A range over a file on disk, shaped like the one the share would return. */
    private fun localRange(file: java.nio.file.Path, start: Long, end: Long?): WebDavClient.RangeStream {
        val size = java.nio.file.Files.size(file)
        val stream = java.nio.file.Files.newInputStream(file)
        stream.skip(start)
        val limit = (end?.plus(1) ?: size) - start
        return WebDavClient.RangeStream(
            stream = java.io.BufferedInputStream(stream).let { buffered ->
                object : java.io.FilterInputStream(buffered) {
                    private var left = limit
                    override fun read(): Int {
                        if (left <= 0) return -1
                        val value = super.read()
                        if (value >= 0) left--
                        return value
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (left <= 0) return -1
                        val read = super.read(b, off, minOf(len.toLong(), left).toInt())
                        if (read > 0) left -= read
                        return read
                    }
                }
            },
            totalSize = size,
            partial = start > 0 || end != null,
            response = null
        )
    }

    /**
     * Reader that resolves the direct link once and then issues plain range
     * requests against it: probing a container needs several small reads, and
     * re-following the redirect for each one dominated the scan time.
     */
    private fun rangeReader(path: String): MkvProbe.RangeReader {
        // A file already here is probed here: chapters, cues and the track list
        // of a downloaded film used to go to the share for their few kilobytes,
        // and offline that is a failed read for something sitting on disk.
        offlineFile?.invoke(path)?.let { local ->
            return MkvProbe.RangeReader { start, length ->
                java.io.RandomAccessFile(local.toFile(), "r").use { file ->
                    val available = (file.length() - start).coerceIn(0L, length.toLong()).toInt()
                    val bytes = ByteArray(available)
                    if (available > 0) {
                        file.seek(start)
                        file.readFully(bytes)
                    }
                    bytes
                }
            }
        }
        val direct = directUrl(path)
        return MkvProbe.RangeReader { start, length ->
            val stream = if (direct != null) {
                dav().openRangeAt(direct, start, start + length - 1, useAuth = false)
            } else {
                dav().openRange(path, start, start + length - 1)
            }
            stream.use { it.stream.readUpTo(length) }
        }
    }

    /**
     * Reads the container header once and stores the embedded tracks and the
     * runtime on the item. External subtitle streams found by the scanner are
     * preserved.
     */
    fun probeItem(item: MediaItemDto, force: Boolean = false): MediaItemDto {
        val result = readProbe(item, force)
        result.record?.let(repository::upsertItem)
        return result.item
    }

    /** Workers only read; the scan coordinator decides whether a result may be stored. */
    private data class ProbeResult(val item: MediaItemDto, val record: ItemRecord? = null)

    private fun readProbe(
        item: MediaItemDto,
        force: Boolean = false,
        checkActive: () -> Unit = {}
    ): ProbeResult {
        checkActive()
        val path = item.path ?: return ProbeResult(item)
        val record = repository.itemRecord(item.id) ?: return ProbeResult(item)
        if (!force && record.probedAt != null) return ProbeResult(item)

        val now = System.currentTimeMillis()
        checkActive()
        val source = rangeReader(path)
        checkActive()
        val reader = MkvProbe.RangeReader { start, length ->
            checkActive()
            source.read(start, length).also { checkActive() }
        }
        var failure: Throwable? = null
        val probed: Pair<Long?, List<MediaStreamDto>>? = when {
            MkvProbe.isMatroska(path) -> runCatching { MkvProbe.probe(reader) }
                .onFailure { failure = it; log.warn("解析 Matroska {} 失败: {}", path, it.message) }
                .getOrNull()
                ?.let { it.durationMs to MkvProbe.toMediaStreams(it.tracks) }

            Mp4Probe.isMp4(path) -> runCatching {
                Mp4Probe.probe(reader, item.sizeBytes ?: fileSize(path))
            }
                .onFailure { failure = it; log.warn("解析 MP4 {} 失败: {}", path, it.message) }
                .getOrNull()
                ?.let { it.durationMs to Mp4Probe.toMediaStreams(it.tracks) }

            TsProbe.isTransportStream(path) -> runCatching {
                TsProbe.probe(reader, item.sizeBytes ?: fileSize(path))
            }
                .onFailure { failure = it; log.warn("解析 TS {} 失败: {}", path, it.message) }
                .getOrNull()
                ?.let { it.durationMs to it.streams }

            else -> null
        }
        // Container parsers catch malformed input and I/O errors. Cancellation
        // and deadlines must still win even if a parser swallowed the signal.
        checkActive()

        if (probed == null) {
            // A read that failed — on the network, or answered with an error
            // status by the share or its CDN — says nothing about the file, and
            // stamping it would leave it without tracks or a runtime for good.
            // Left unstamped, it is tried again on the next scan or the next
            // time it is opened, as chapters are.
            val unread = failure is java.io.IOException || failure is com.daview.server.storage.WebDavException
            return ProbeResult(item, if (unread) null else record.copy(probedAt = now))
        }

        val (durationMs, embedded) = probed
        val external = item.mediaStreams.filter { it.isExternal }
        val updated = item.copy(
            runtimeMs = durationMs ?: item.runtimeMs,
            mediaStreams = embedded + external
        )
        return ProbeResult(updated, record.copy(dto = updated, probedAt = now))
    }

    /**
     * The file's chapters, read once and kept. Only Matroska carries them in a
     * form worth reading; anything else is recorded as having none. A read that
     * fails on the network is not recorded, so it is tried again next time.
     */
    fun chapters(item: MediaItemDto): List<com.daview.shared.model.ChapterDto> {
        if (repository.chaptersKnown(item.id)) return item.chapters
        val path = item.path ?: return emptyList()
        if (!MkvProbe.isMatroska(path)) {
            repository.setChapters(item.id, emptyList())
            return emptyList()
        }
        val found = runCatching {
            val reader = rangeReader(path)
            val info = MkvProbe.probe(reader) ?: return@runCatching emptyList()
            MkvProbe.probeChapters(reader, info, item.sizeBytes ?: fileSize(path) ?: Long.MAX_VALUE)
                .map { com.daview.shared.model.ChapterDto(it.startMs, it.title) }
        }.getOrElse {
            log.warn("读取章节 {} 失败: {}", path, it.message)
            return emptyList()
        }
        repository.setChapters(item.id, found)
        return found
    }

    /** Byte offset to timestamp mapping, used to follow external players. */
    fun cueIndex(item: MediaItemDto): MkvProbe.CueIndex? {
        val path = item.path ?: return null
        if (!MkvProbe.isMatroska(path)) return null
        cueIndexes[item.id]?.let { return it }
        val size = item.sizeBytes ?: fileSize(path) ?: return null
        val reader = rangeReader(path)
        val info = runCatching { MkvProbe.probe(reader) }.getOrNull() ?: return null
        val index = runCatching { MkvProbe.probeCues(reader, info, size) }
            .onFailure { log.warn("读取 Cues 失败 {}: {}", path, it.message) }
            .getOrNull() ?: return null
        if (index.isEmpty) return null
        cueIndexes[item.id] = index
        return index
    }

    /**
     * Plain platform threads rather than virtual ones: Android has no virtual
     * threads, and the pool is bounded by [parallelism] anyway.
     */
    private val probeThreadFactory = java.util.concurrent.ThreadFactory { runnable ->
        Thread(runnable, "daview-probe").apply { isDaemon = true }
    }

    fun probeMissing(libraryId: String, limit: Int, parallelism: Int = 6, onProgress: (Int, Int, String) -> Unit) {
        probeMissing(libraryId, limit, parallelism, ScanService.Cancellation(), onProgress = onProgress)
    }

    /**
     * At most [parallelism] probes are in flight. Results, writes and callbacks
     * are consumed on the calling thread, including failures from callbacks.
     * The deadline covers this whole batch, not each file separately.
     */
    internal fun probeMissing(
        libraryId: String,
        limit: Int,
        parallelism: Int = 6,
        cancellation: ScanService.Cancellation,
        timeoutMillis: Long = PROBE_DEADLINE_MS,
        nanoTime: () -> Long = System::nanoTime,
        onProgress: (Int, Int, String) -> Unit
    ) {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val started = nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val stopped = AtomicBoolean()
        fun remainingNanos() = budget - (nanoTime() - started)
        fun checkActive() {
            cancellation.checkCancelled()
            if (stopped.get()) throw CancellationException("探测已停止")
            if (Thread.currentThread().isInterrupted) throw InterruptedException("探测已中断")
            if (remainingNanos() <= 0) throw TimeoutException("媒体探测超时")
        }

        checkActive()
        val pending = repository.itemsNeedingProbe(libraryId, limit)
        checkActive()
        if (pending.isEmpty()) return

        val workers = minOf(parallelism.coerceAtLeast(1), pending.size)
        val executor = Executors.newFixedThreadPool(workers, probeThreadFactory)
        val completed = ExecutorCompletionService<Pair<MediaItemDto, Result<ProbeResult>>>(executor)
        val inFlight = HashSet<Future<Pair<MediaItemDto, Result<ProbeResult>>>>()
        var next = 0
        var done = 0
        fun submitNext() = cancellation.whileActive {
            checkActive()
            val item = pending[next++]
            inFlight += completed.submit(java.util.concurrent.Callable {
                checkActive()
                item to runCatching { readProbe(item, checkActive = ::checkActive) }
            })
        }

        try {
            repeat(workers) { submitNext() }
            while (inFlight.isNotEmpty()) {
                checkActive()
                // Poll even if every reader is blocked: cancellation cannot
                // depend on a network request completing or on a callback.
                val ready = completed.poll(
                    minOf(remainingNanos().coerceAtLeast(1), TimeUnit.MILLISECONDS.toNanos(PROBE_POLL_MS)),
                    TimeUnit.NANOSECONDS
                ) ?: continue
                inFlight -= ready
                checkActive()
                val (item, outcome) = ready.get()
                cancellation.whileActive {
                    checkActive()
                    outcome.getOrNull()?.record?.let(repository::upsertItem)
                }
                outcome.exceptionOrNull()?.let { log.warn("探测 {} 失败: {}", item.path, it.message) }
                checkActive()
                onProgress(++done, pending.size, item.name)
                checkActive()
                if (next < pending.size) submitNext()
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            // Readers may ignore interruption. They have no write path, and
            // this per-batch flag also prevents their subsequent range reads.
            stopped.set(true)
            inFlight.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }

    private companion object {
        const val DIRECT_URL_TTL_MS = 30L * 60 * 1000
        const val PROBE_DEADLINE_MS = 15L * 60 * 1000
        const val PROBE_POLL_MS = 50L
    }
}
