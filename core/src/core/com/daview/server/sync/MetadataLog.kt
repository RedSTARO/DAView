package com.daview.server.sync

import com.daview.server.ServerContext
import com.daview.server.db.MetadataLogRow
import com.daview.server.db.MetadataRow
import com.daview.server.db.MetadataTarget
import com.daview.server.storage.WebDavClient
import com.daview.shared.model.MetadataLogDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.withLock

/**
 * Scraped metadata, shared a change at a time.
 *
 * The share has no conditional write and no append, which rules out one file
 * that everybody rewrites: each writer would have to read and replace the whole
 * catalogue to change one row, and two of them at once would lose one's work.
 * So every device keeps a log of its own, and nobody else ever writes to it.
 *
 *  - A **segment** holds the rows that changed here since the last one. It is
 *    never rewritten, which is why publishing needs no read first and two
 *    devices cannot overwrite each other.
 *  - The **sync file** says how far each log reaches ([MetadataLogDto]). Every
 *    device reads that file anyway, so a device with nothing to catch up on
 *    makes no extra request, and one that has sees exactly which segments.
 *  - A **base** file is a device's log rolled into one, so that a new device
 *    does not have to replay years of segments. Devices that are keeping up
 *    never read it.
 *
 * Rows are merged newest-first by their scrape stamp, so reading one twice, or
 * two logs in either order, comes to the same thing. That is what lets the
 * pieces above fail or overlap freely: an interrupted publish is repeated, and
 * a device that is behind reads whatever it missed.
 *
 * Nothing here runs on the sync thread. A segment can take minutes on a phone,
 * and that must not hold up the watch state.
 */
class MetadataLog(
    private val context: ServerContext,
    private val davProvider: () -> WebDavClient?,
    /** Publishes the sync file. A new segment does not exist for anyone until this has run. */
    private val announce: () -> Boolean
) : AutoCloseable {

    /** What one pass moved. */
    data class Moved(val sent: Int = 0, val received: Int = 0)

    private val log = LoggerFactory.getLogger(MetadataLog::class.java)
    private val repository get() = context.repository

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "daview-metadata").apply { isDaemon = true }
    }
    private val queued = AtomicBoolean(false)
    private val queuedForce = AtomicBoolean(false)

    /** One device's files are read or written by one thing at a time. */
    private val lock = ReentrantLock()

    @Volatile
    private var lastPublishAt = 0L

    @Volatile
    private var closed = false

    /** Set when the sync file shows somebody else writing this device's log. */
    @Volatile
    private var takenOver: String? = null

    /** How many times the sync file has been read; see [Strike]. */
    private val generation = AtomicLong()
    private val strikes = HashMap<String, Strike>()

    /** Told when rows from another device have landed, so a page can read again. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private val active: Boolean get() = context.config.sync.let { it.enabled && it.metadata }

    /** Queues a pass, unless one is already waiting. [force] ignores the publishing interval. */
    fun schedule(force: Boolean = false) {
        if (closed || !active) return
        if (force) queuedForce.set(true)
        if (!queued.compareAndSet(false, true)) return
        runCatching {
            worker.execute {
                queued.set(false)
                val forced = queuedForce.getAndSet(false)
                try {
                    cycle(forced)
                } catch (t: Throwable) {
                    log.warn("同步刮削数据失败: {}", t.message)
                }
            }
        }.onFailure { queued.set(false) }
    }

    /** Waits for whatever has been queued. */
    internal fun settle() {
        worker.submit { }.get()
    }

    /** Reads what is new, then sends what changed here. Blocks for as long as that takes. */
    fun cycle(force: Boolean = false): Moved {
        if (closed || !active) return Moved()
        val dav = davProvider() ?: return Moved()
        val failures = ArrayList<String>()
        val received = lock.withLock { read(dav, failures) }
        val sent = lock.withLock {
            val interval = context.config.sync.minIntervalMinutes.coerceAtLeast(1) * 60_000L
            if (!force && System.currentTimeMillis() - lastPublishAt < interval) return@withLock 0
            startOverIfTaken()
            val rows = publish(dav, failures)
            val compacted = failures.isEmpty() && compact(dav, failures)
            if (rows > 0 || compacted) {
                lastPublishAt = System.currentTimeMillis()
                announce()
            }
            trim(dav)
            rows
        }
        report(received + sent > 0, failures.firstOrNull())
        return Moved(sent, received)
    }

    /**
     * Brings this device up to date before a scan, so the scan has fewer titles
     * to ask a provider about. On a device that is keeping up this is one
     * lookup in the database.
     */
    fun catchUp(status: (String) -> Unit) {
        if (closed || !active) return
        val dav = davProvider() ?: return
        val failures = ArrayList<String>()
        val received = lock.withLock {
            if (behind().isEmpty()) return
            status("同步刮削数据")
            read(dav, failures)
        }
        report(received > 0, failures.firstOrNull())
    }

    /**
     * Takes in what the sync file says of each log.
     *
     * An entry for this device's own log that is ahead of this device means
     * something else is writing to it: a copied data directory, or a database
     * restored from before those segments. The next segment would overwrite
     * one of them, so this device starts a log of its own.
     */
    fun onManifest(entries: List<MetadataLogDto>) {
        generation.incrementAndGet()
        val mine = context.config.sync.deviceId
        entries.firstOrNull { it.writer == mine }?.let { remote ->
            val local = repository.metadataLog(mine)
            if (local == null || remote.seq > local.seq ||
                (remote.seq == local.seq && remote.updatedAt > local.updatedAt)
            ) takenOver = mine
        }
        entries.forEach(repository::mergeMetadataLog)
    }

    /**
     * The sync file has moved, and the logs live beside it, so at the new place
     * none of them exist. What this device published goes out again from there.
     */
    fun restart() = lock.withLock {
        repository.resetMetadataLogs()
        strikes.clear()
        lastPublishAt = 0
    }

    private fun report(moved: Boolean, failure: String?) {
        val sync = context.config.sync
        val at = if (moved) System.currentTimeMillis() else sync.lastMetadataAt
        if (failure == sync.metadataError && at == sync.lastMetadataAt) return
        context.updateConfig { it.copy(sync = it.sync.copy(metadataError = failure, lastMetadataAt = at)) }
    }

    // ------------------------------------------------------------ reading

    private fun behind(): List<MetadataLogRow> {
        val mine = context.config.sync.deviceId
        return repository.metadataLogs().filter { it.writer != mine && it.appliedSeq < it.seq }
    }

    private fun targets(): Map<String, MetadataTarget> {
        val language = context.config.scraper.language
        return repository.libraries().associate {
            it.id to MetadataTarget(it.language.ifBlank { language }, it.lastScanAt)
        }
    }

    private fun read(dav: WebDavClient, failures: MutableList<String>): Int {
        val pending = behind()
        if (pending.isEmpty()) return 0
        val targets = targets()
        val received = pending.sumOf { readLog(dav, it, targets, failures) }
        if (received > 0) onChanged?.invoke()
        return received
    }

    /**
     * Reads one log from where this device left off, one segment at a time, and
     * records each as it finishes so an interruption costs only that segment.
     *
     * The base stands in for segments that are gone from the share, and for a
     * device starting from nothing, where it is the same rows in one request.
     */
    private fun readLog(
        dav: WebDavClient,
        entry: MetadataLogRow,
        targets: Map<String, MetadataTarget>,
        failures: MutableList<String>
    ): Int {
        var position = entry.appliedSeq
        var received = 0
        if (entry.baseSeq > position && (position == 0L || position + 1 < entry.firstSeq)) {
            when (val step = readFile(dav, entry, baseName(entry.writer, entry.baseSeq), targets)) {
                is Step.Applied -> {
                    received += step.rows
                    position = entry.baseSeq
                    repository.setMetadataLogApplied(entry.writer, position)
                }
                is Step.Failed -> {
                    failures += step.reason
                    return received
                }
                Step.Lost -> {
                    if (!giveUpOn(baseName(entry.writer, entry.baseSeq))) return received
                    position = entry.baseSeq
                    repository.setMetadataLogApplied(entry.writer, position)
                }
            }
        }
        while (position < entry.seq && !closed) {
            val next = position + 1
            val name = segmentName(entry.writer, next)
            when (val step = readFile(dav, entry, name, targets)) {
                is Step.Applied -> received += step.rows
                is Step.Failed -> {
                    failures += step.reason
                    return received
                }
                Step.Lost -> if (!giveUpOn(name)) return received
            }
            position = next
            repository.setMetadataLogApplied(entry.writer, position)
        }
        return received
    }

    private sealed interface Step {
        data class Applied(val rows: Int) : Step
        /** Not read this time; the file is expected to be there next time. */
        data class Failed(val reason: String) : Step
        /** Gone or damaged. */
        data object Lost : Step
    }

    private class NewerFormat(val version: Int) : RuntimeException()

    private fun readFile(
        dav: WebDavClient,
        entry: MetadataLogRow,
        name: String,
        targets: Map<String, MetadataTarget>
    ): Step {
        val read = dav.read(path(entry.flat, name), limit = READ_LIMIT, callTimeoutMs = TRANSFER_TIMEOUT_MS)
        read.error?.let { return Step.Failed("读取刮削数据失败: $it") }
        val bytes = read.bytes ?: return Step.Lost
        return try {
            Step.Applied(apply(entry.writer, bytes, targets))
        } catch (e: NewerFormat) {
            Step.Failed("刮削数据的格式 ${e.version} 比这个版本的 DAView 新，先更新应用")
        } catch (e: Exception) {
            log.warn("刮削数据 {} 读取失败: {}", name, e.message)
            Step.Lost
        }
    }

    /**
     * Whether to stop waiting for a file that is not there.
     *
     * Usually it has been compacted away and this device is working from a sync
     * file read just before that, so it waits for a newer one. Only after
     * several of those still point at the file is it really gone — a person
     * tidying the share, say — and a log must not stay stuck behind it for ever.
     */
    private fun giveUpOn(name: String): Boolean {
        val current = generation.get()
        val strike = strikes.getOrPut(name) { Strike(generation = current - 1) }
        if (strike.generation != current) {
            strike.generation = current
            strike.count++
        }
        if (strike.count < MAX_STRIKES) return false
        strikes.remove(name)
        log.warn("刮削数据 {} 不在存储上，已跳过", name)
        return true
    }

    private class Strike(var count: Int = 0, var generation: Long)

    /** Rows are applied in batches as they are decoded, so a large base never sits in memory. */
    private fun apply(writer: String, bytes: ByteArray, targets: Map<String, MetadataTarget>): Int {
        var applied = 0
        GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { reader ->
            val header = json.decodeFromString(SegmentHeader.serializer(), reader.readLine() ?: error("空文件"))
            require(header.format == FORMAT) { "不是刮削数据文件" }
            if (header.version > VERSION) throw NewerFormat(header.version)

            val batch = ArrayList<MetadataRow>(APPLY_BATCH)
            for (line in reader.lineSequence()) {
                if (line.isBlank()) continue
                batch += json.decodeFromString(MetadataRow.serializer(), line)
                if (batch.size == APPLY_BATCH) {
                    applied += repository.applyMetadataRows(batch, writer, targets)
                    batch.clear()
                }
            }
            applied += repository.applyMetadataRows(batch, writer, targets)
        }
        return applied
    }

    // ------------------------------------------------------------ writing

    /** Looked up on the first row: a pass with nothing to send should touch nothing. */
    private fun languageOf(): (String) -> String {
        val fallback = context.config.scraper.language
        val languages by lazy { repository.libraries().associate { it.id to it.language.ifBlank { fallback } } }
        return { libraryId -> languages[libraryId] ?: fallback }
    }

    /** Everything this device had in the shared log goes out again, under a name of its own. */
    private fun startOverIfTaken() {
        val taken = takenOver ?: return
        takenOver = null
        if (taken != context.config.sync.deviceId) return
        log.warn("刮削日志 {} 被另一台设备写入，改用新的日志", taken)
        repository.republishMetadata(taken)
        context.updateConfig { it.copy(sync = it.sync.copy(deviceId = newDeviceId())) }
    }

    private fun ownLog(): MetadataLogRow {
        val writer = context.config.sync.deviceId.ifBlank {
            newDeviceId().also { id -> context.updateConfig { it.copy(sync = it.sync.copy(deviceId = id)) } }
        }
        return repository.metadataLog(writer) ?: MetadataLogRow(writer)
    }

    private fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    /**
     * Sends everything waiting, in segments of a bounded size, so one failed
     * request costs one segment rather than a whole library's first upload.
     *
     * The segment goes first and the local record after it. Failing in between
     * means the next attempt writes the same name again, with those same rows
     * in it, before anybody has been told it exists.
     */
    private fun publish(dav: WebDavClient, failures: MutableList<String>): Int {
        val language = languageOf()
        var sent = 0
        while (!closed) {
            val rows = repository.unpublishedMetadata(SEGMENT_ROWS, language)
            if (rows.isEmpty()) break
            val own = ownLog()
            val seq = own.seq + 1
            val bytes = encode(own.writer, seq, base = false, rows.asSequence())
            val flat = place(dav, own, segmentName(own.writer, seq), bytes, failures) ?: break
            context.database.transaction {
                repository.saveMetadataLog(
                    own.copy(seq = seq, flat = flat, updatedAt = System.currentTimeMillis(), appliedSeq = seq)
                )
                repository.markMetadataPublished(rows, own.writer)
            }
            sent += rows.size
        }
        return sent
    }

    /**
     * Rolls this device's log into a new base, holding only the rows it is
     * still the last word on. Those another device has since replaced, and
     * those whose files have left the share, drop out here.
     *
     * The segments it covers stay until the next time round, so a device that
     * is a little behind finishes on segments rather than downloading the base.
     */
    private fun compact(dav: WebDavClient, failures: MutableList<String>): Boolean {
        val own = repository.metadataLog(context.config.sync.deviceId) ?: return false
        if (own.seq - own.baseSeq < COMPACT_AFTER) return false
        // The last round's files are still to go; their names would be lost.
        if (own.retiredBase != 0L || own.trimmedSeq + 1 < own.firstSeq) return false

        val language = languageOf()
        val rows = sequence {
            var after = ""
            while (true) {
                val page = repository.publishedMetadata(own.writer, after, SEGMENT_ROWS, language)
                yieldAll(page)
                if (page.size < SEGMENT_ROWS) break
                after = page.last().id
            }
        }
        val bytes = encode(own.writer, own.seq, base = true, rows)
        val flat = place(dav, own, baseName(own.writer, own.seq), bytes, failures) ?: return false
        repository.saveMetadataLog(
            own.copy(
                baseSeq = own.seq,
                firstSeq = own.baseSeq + 1,
                flat = flat,
                updatedAt = System.currentTimeMillis(),
                retiredBase = own.baseSeq
            )
        )
        return true
    }

    /**
     * Deletes what the last compaction replaced, once a published sync file
     * sends readers elsewhere. A refused delete leaves a stray file, not a
     * problem, so it is not retried.
     */
    private fun trim(dav: WebDavClient) {
        val own = repository.metadataLog(context.config.sync.deviceId) ?: return
        val segments = (own.trimmedSeq + 1) until own.firstSeq
        if (segments.isEmpty() && own.retiredBase == 0L) return
        if ((context.config.sync.lastUploadAt ?: 0L) < own.updatedAt) return
        segments.forEach { dav.delete(path(own.flat, segmentName(own.writer, it))) }
        if (own.retiredBase != 0L) dav.delete(path(own.flat, baseName(own.writer, own.retiredBase)))
        repository.saveMetadataLog(own.copy(trimmedSeq = own.firstSeq - 1, retiredBase = 0))
    }

    /**
     * Writes one file, and works out where this device's files can go.
     *
     * Whether the share takes a new folder cannot be asked, any more than
     * whether it takes a write, so the writes are the test: into the folder,
     * then again after creating it, then beside the sync file, where it has
     * already been proved that files can be put.
     *
     * @return whether the file went beside the sync file; null when it went nowhere.
     */
    private fun place(
        dav: WebDavClient,
        own: MetadataLogRow,
        name: String,
        bytes: ByteArray,
        failures: MutableList<String>
    ): Boolean? {
        fun put(flat: Boolean) = dav.put(path(flat, name), bytes, CONTENT_TYPE, TRANSFER_TIMEOUT_MS)

        val first = put(own.flat)
        if (first.ok) return own.flat
        if (!own.flat && first.status != null) {
            dav.mkcol(folder())
            if (put(flat = false).ok) return false
            val beside = put(flat = true)
            if (beside.ok) return true
            failures += "上传刮削数据失败: ${beside.message ?: beside.status}"
            return null
        }
        failures += "上传刮削数据失败: ${first.message ?: first.status}"
        return null
    }

    private fun encode(writer: String, seq: Long, base: Boolean, rows: Sequence<MetadataRow>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).bufferedWriter(Charsets.UTF_8).use { text ->
            val header = SegmentHeader(FORMAT, VERSION, writer, seq, base, System.currentTimeMillis())
            text.write(json.encodeToString(SegmentHeader.serializer(), header))
            text.write("\n")
            rows.forEach { row ->
                text.write(json.encodeToString(MetadataRow.serializer(), row))
                text.write("\n")
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ names

    private fun folder(): String = context.config.sync.remotePath.ifBlank { DEFAULT_PATH }.removeSuffix(".json") + ".metadata"

    private fun path(flat: Boolean, name: String): String = folder() + (if (flat) "-" else "/") + name

    private fun segmentName(writer: String, seq: Long) = "$writer-$seq.jsonl.gz"

    private fun baseName(writer: String, seq: Long) = "$writer-base-$seq.jsonl.gz"

    override fun close() {
        closed = true
        worker.shutdownNow()
    }

    /** The first line of every file. The rows follow, one JSON object to a line. */
    @Serializable
    private data class SegmentHeader(
        val format: String,
        val version: Int,
        val writer: String,
        val seq: Long,
        val base: Boolean = false,
        val createdAt: Long = 0
    )

    private companion object {
        const val FORMAT = "daview-metadata"
        const val VERSION = 1
        const val DEFAULT_PATH = "/daview-sync.json"
        const val CONTENT_TYPE = "application/gzip"

        /** A share is slower to reach than the sync file's two kilobytes suggest. */
        const val TRANSFER_TIMEOUT_MS = 180_000L
        const val READ_LIMIT = 64L * 1024 * 1024

        /** About a megabyte compressed. A first upload of a large library takes a few of these. */
        const val SEGMENT_ROWS = 2_000

        /** Rows applied in one transaction, so reads and playback writes are not kept waiting. */
        const val APPLY_BATCH = 200

        /** Segments between one base and the next, which also bounds a new device's reads. */
        const val COMPACT_AFTER = 16L

        /** Sync files that have to agree a file is missing before it is written off. */
        const val MAX_STRIKES = 3

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
    }
}
