package com.daview.server.media

import com.daview.server.db.ItemRecord
import com.daview.server.db.Repository
import com.daview.server.library.MkvProbe
import com.daview.server.library.Mp4Probe
import com.daview.server.storage.WebDavClient
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MediaStreamDto
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything that needs to touch the actual media bytes: resolving playable
 * URLs, caching the short-lived CDN links, and probing containers for their
 * track list, duration and cue index.
 */
class StreamService(
    private val davProvider: () -> WebDavClient?,
    private val repository: Repository
) {
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
    fun openRange(path: String, start: Long, end: Long?): WebDavClient.RangeStream {
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

    fun fileSize(path: String): Long? =
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
        val direct = directUrl(path)
        return MkvProbe.RangeReader { start, length ->
            val stream = if (direct != null) {
                dav().openRangeAt(direct, start, start + length - 1, useAuth = false)
            } else {
                dav().openRange(path, start, start + length - 1)
            }
            stream.use { it.stream.readNBytes(length) }
        }
    }

    /**
     * Reads the container header once and stores the embedded tracks and the
     * runtime on the item. External subtitle streams found by the scanner are
     * preserved.
     */
    fun probeItem(item: MediaItemDto, force: Boolean = false): MediaItemDto {
        val path = item.path ?: return item
        val record = repository.itemRecord(item.id) ?: return item
        if (!force && record.probedAt != null) return item

        val now = System.currentTimeMillis()
        val reader = rangeReader(path)
        val probed: Pair<Long?, List<MediaStreamDto>>? = when {
            MkvProbe.isMatroska(path) -> runCatching { MkvProbe.probe(reader) }
                .onFailure { log.warn("解析 Matroska {} 失败: {}", path, it.message) }
                .getOrNull()
                ?.let { it.durationMs to MkvProbe.toMediaStreams(it.tracks) }

            Mp4Probe.isMp4(path) -> runCatching {
                Mp4Probe.probe(reader, item.sizeBytes ?: fileSize(path))
            }
                .onFailure { log.warn("解析 MP4 {} 失败: {}", path, it.message) }
                .getOrNull()
                ?.let { it.durationMs to Mp4Probe.toMediaStreams(it.tracks) }

            else -> null
        }

        if (probed == null) {
            repository.upsertItem(record.copy(probedAt = now))
            return item
        }

        val (durationMs, embedded) = probed
        val external = item.mediaStreams.filter { it.isExternal }
        val updated = item.copy(
            runtimeMs = durationMs ?: item.runtimeMs,
            mediaStreams = embedded + external
        )
        repository.upsertItem(record.copy(dto = updated, probedAt = now))
        return updated
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
     * Probes pending files with a small amount of concurrency. Each probe is a
     * handful of short range reads over the network, so the work is latency
     * bound and a few parallel readers cut wall-clock time dramatically without
     * putting real load on the storage backend.
     */
    /**
     * Plain platform threads rather than virtual ones: Android has no virtual
     * threads, and the pool is bounded by [parallelism] anyway.
     */
    private val probeThreadFactory = java.util.concurrent.ThreadFactory { runnable ->
        Thread(runnable, "daview-probe").apply { isDaemon = true }
    }

    fun probeMissing(libraryId: String, limit: Int, parallelism: Int = 6, onProgress: (Int, Int, String) -> Unit) {
        val pending = repository.itemsNeedingProbe(libraryId, limit)
        if (pending.isEmpty()) return
        val done = java.util.concurrent.atomic.AtomicInteger()
        val gate = java.util.concurrent.Semaphore(parallelism.coerceAtLeast(1))
        val threads = pending.map { item ->
            probeThreadFactory.newThread {
                gate.acquire()
                try {
                    runCatching { probeItem(item) }
                    onProgress(done.incrementAndGet(), pending.size, item.name)
                } finally {
                    gate.release()
                }
            }
        }
        threads.forEach { it.start() }
        // A single unreadable file must never stall a library scan, so each
        // probe gets a deadline and anything still running is abandoned.
        val deadline = System.currentTimeMillis() + PROBE_DEADLINE_MS
        threads.forEach { thread ->
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return@forEach
            runCatching { thread.join(remaining) }
        }
        threads.filter { it.isAlive }.forEach {
            log.warn("探测线程超时，已放弃: {}", it.name)
            runCatching { it.interrupt() }
        }
    }

    private companion object {
        const val DIRECT_URL_TTL_MS = 30L * 60 * 1000
        const val PROBE_DEADLINE_MS = 15L * 60 * 1000
    }
}
