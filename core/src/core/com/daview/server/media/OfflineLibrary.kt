package com.daview.server.media

import com.daview.server.db.Repository
import com.daview.server.db.DOWNLOAD_KIND_SUBTITLE
import com.daview.server.db.DOWNLOAD_KIND_VIDEO
import com.daview.server.db.Repository.DownloadFileRow
import com.daview.server.storage.WebDavClient
import com.daview.server.storage.WebDavException
import com.daview.shared.model.DownloadDto
import com.daview.shared.model.DownloadState
import com.daview.shared.model.MediaItemDto
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory

/**
 * Where the bytes of a media file come from, as far as a download cares: a
 * size, and a range. [StreamService] is the one that answers from the share;
 * a test can answer from memory.
 */
interface RangeSource {
    fun fileSize(path: String): Long?
    fun openRange(path: String, start: Long, end: Long?): WebDavClient.RangeStream
}

/**
 * Keeps whole files on this device so they can be watched with no network.
 *
 * The app is otherwise entirely a window onto a share: everything streams, and
 * away from Wi-Fi there is nothing to watch. This is the one place bytes are
 * kept rather than passed through.
 *
 * A download is the video *and* every external subtitle the scan found beside
 * it. A film kept without its subtitles is only half kept: the subtitle files
 * are separate objects on the share, and a player pointed at the local video
 * would have gone back to the network for them — which offline is exactly
 * where it cannot go. They land beside the video under the names the share
 * gives them, so an external player opening the folder picks them up as well.
 *
 * Downloads are resumable by construction — the partial file on disk is the
 * progress, and a new attempt asks for the range after whatever is already
 * there — so a dropped connection or a killed process costs nothing but the
 * partial block. What a killed process does lose is the queue in memory, which
 * is why [resumePending] exists and the app calls it on start. Nothing here
 * writes to the share.
 */
class OfflineLibrary(
    dataDir: Path,
    private val repository: Repository,
    private val source: RangeSource,
    /** Artwork is fetched alongside the file, so the detail page renders offline too. */
    private val images: ImageCache? = null,
    /** The directory the user chose, or null for the default under the data directory. */
    private val configuredDirectory: () -> String? = { null }
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(OfflineLibrary::class.java)
    private val defaultDir: Path = dataDir.resolve("offline")

    /**
     * Whether a transfer may run right now. The phone asks this before every
     * block, so a download waits rather than eats a mobile allowance: false
     * puts the queue on hold with [transferGateReason] against it, and the
     * next answer of true picks up where the partial file ends.
     */
    @Volatile
    var transferGate: () -> Boolean = { true }

    @Volatile
    var transferGateReason: String = "等待网络"

    /**
     * One at a time. Two downloads over one share share the same bandwidth and
     * finish in the same total time, but sequentially the first one becomes
     * watchable while the second is still going.
     */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "daview-download").apply { isDaemon = true }
    }

    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** Items handed to the executor by this process. A row without one is stale. */
    private val active = ConcurrentHashMap.newKeySet<String>()

    /** Live byte counts, so progress does not cost a write per block. */
    private val progress = ConcurrentHashMap<String, Long>()

    /** Where downloads are written right now. */
    fun directory(): Path =
        configuredDirectory()?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: defaultDir

    fun defaultDirectory(): Path = defaultDir

    /**
     * Proves a directory can take downloads before it is chosen: created if
     * need be, and a file written and removed. A path that is not writable is
     * refused here, with the reason, instead of failing the first download.
     */
    fun checkWritable(path: Path) {
        path.createDirectories()
        if (!path.isDirectory()) throw IOException("不是目录")
        val probe = path.resolve(".daview-write-test")
        Files.write(probe, ByteArray(0))
        probe.deleteIfExists()
    }

    /** Where a finished file lives, addressed by the media path it came from. */
    fun localFile(mediaPath: String): Path? =
        repository.downloadFilesForPath(mediaPath)
            .asSequence()
            .filter { it.state == DownloadState.DONE }
            .map { Path.of(it.file) }
            .firstOrNull { it.exists() }

    fun all(): List<DownloadDto> = repository.downloads().map { row ->
        progress[row.itemId]?.let { row.copy(downloadedBytes = it) } ?: row
    }

    fun status(itemId: String): DownloadDto? = all().firstOrNull { it.itemId == itemId }

    /** Whether every file the item is made of is here. */
    fun isComplete(itemId: String): Boolean {
        val item = repository.item(itemId) ?: return false
        if (repository.download(itemId)?.state != DownloadState.DONE) return false
        val rows = repository.downloadFiles(itemId)
        return wanted(item).all { w -> rows.any { it.mediaPath == w.path && it.isWhole() } }
    }

    /**
     * Queues an item. Already queued or running, this does nothing — pressing
     * download twice should not start a second copy. Finished, it does nothing
     * either, unless a file is missing: a subtitle the scan found since, or
     * one an earlier version of the app never fetched. Then only that is
     * fetched, and the video is left alone.
     */
    fun start(itemId: String) {
        val item = repository.item(itemId) ?: return
        if (!item.isPlayable) return
        val mediaPath = item.path?.takeIf { it.isNotBlank() } ?: return
        if (itemId in active) return

        val existing = repository.download(itemId)
        val rows = repository.downloadFiles(itemId)
        val wanted = wanted(item)
        if (existing?.state == DownloadState.DONE &&
            wanted.all { w -> rows.any { it.mediaPath == w.path && it.isWhole() } }
        ) return

        cancelled -= itemId
        val known = rows.associateBy { it.mediaPath }
        val videoTarget = known[mediaPath]?.file?.let(Path::of) ?: targetFor(mediaPath)
        val taken = HashSet<String>()
        taken += videoTarget.toString()

        val files = wanted.map { w ->
            val row = known[w.path]
            when {
                row == null -> DownloadFileRow(
                    itemId = itemId,
                    mediaPath = w.path,
                    file = if (w.isVideo) videoTarget.toString() else sidecarTargetFor(videoTarget, w.path, taken),
                    kind = w.kind,
                    state = DownloadState.QUEUED,
                    totalBytes = if (w.isVideo) item.sizeBytes ?: 0L else 0L
                )
                row.isWhole() -> row
                // Failed last time, or left half way: tried again from where it got to.
                else -> row.copy(state = DownloadState.QUEUED, error = null)
            }.also { taken += it.file }
        }

        repository.saveDownload(
            DownloadDto(
                itemId = itemId,
                // An episode's own title is often just "第 3 集"; on a list of
                // downloads that says nothing about which show it belongs to.
                name = listOfNotNull(item.seriesName, item.episodeLabel, item.name)
                    .distinct().joinToString(" · "),
                state = DownloadState.QUEUED,
                totalBytes = files.sumOf { it.totalBytes },
                downloadedBytes = files.sumOf { onDisk(it) }
            ),
            file = videoTarget.toString(),
            mediaPath = mediaPath
        )
        files.forEach(repository::saveDownloadFile)

        active += itemId
        executor.submit { run(itemId) }
    }

    /**
     * Stops a download in flight and forgets it: what arrived is deleted, and
     * the row goes as if never asked for, rather than staying behind as a red
     * "download failed: cancelled". A row left over from a process that died
     * has nothing running for it, so it is cleared here and now.
     */
    fun cancel(itemId: String) {
        cancelled += itemId
        if (itemId !in active) {
            val row = repository.download(itemId) ?: return
            if (row.state != DownloadState.DONE) forget(itemId)
        }
    }

    /** Cancels everything queued or in flight. Finished copies stay. */
    fun cancelAll(): Int {
        val pending = repository.downloads().filter { it.active }
        pending.forEach { cancel(it.itemId) }
        return pending.size
    }

    /** Forgets a download and deletes its bytes. */
    fun remove(itemId: String) {
        // Something still running holds its file open, which on Windows makes
        // it undeletable; the runner deletes on its way out instead.
        if (itemId in active) {
            cancel(itemId)
            return
        }
        cancelled += itemId
        forget(itemId)
    }

    /**
     * Picks the queue back up after a restart: rows a dead process left queued
     * or running, and finished downloads whose subtitles were never fetched
     * because they were made before subtitles came along. Returns how many it
     * put back on the queue, so the caller knows whether to watch it.
     */
    fun resumePending(): Int {
        var count = 0
        repository.downloads().forEach { row ->
            if (row.itemId in active) return@forEach
            val again = when (row.state) {
                DownloadState.QUEUED, DownloadState.RUNNING -> true
                DownloadState.DONE -> hasUntriedSubtitles(row.itemId)
                DownloadState.FAILED -> false
            }
            if (again) {
                start(row.itemId)
                if (row.itemId in active) count++
            }
        }
        return count
    }

    /** Total bytes held on this device, in the current directory and the default one. */
    fun usedBytes(): Long = setOf(defaultDir, directory()).sumOf { dir ->
        if (!dir.exists()) return@sumOf 0L
        runCatching {
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                    .sum()
            }
        }.getOrDefault(0L)
    }

    /** Room left on the volume downloads go to, or null when it cannot be asked. */
    fun freeBytes(): Long? = runCatching {
        val dir = directory().also { it.createDirectories() }
        Files.getFileStore(dir).usableSpace
    }.getOrNull()

    // ------------------------------------------------------------ the transfer

    private fun run(itemId: String) {
        try {
            if (itemId in cancelled) {
                forget(itemId)
                return
            }
            repository.updateDownloadState(itemId, DownloadState.RUNNING, null)
            repository.item(itemId)?.let(::keepArtwork)

            // Subtitles first: they are kilobytes, and with them in place the
            // film is watchable the moment the video lands.
            val files = repository.downloadFiles(itemId).sortedBy { if (it.isVideo) 1 else 0 }
            var failedSubtitles = 0
            for (file in files) {
                if (file.isWhole()) continue
                while (true) {
                    if (itemId in cancelled) {
                        forget(itemId)
                        return
                    }
                    if (!waitUntilAllowed(itemId)) {
                        forget(itemId)
                        return
                    }
                    val outcome = try {
                        fetch(itemId, file)
                    } catch (t: Throwable) {
                        if (file.isVideo) throw t
                        // A subtitle the share will not give up does not hold
                        // the film hostage: noted, and tried again next time.
                        log.warn("字幕 {} 下载失败: {}", file.mediaPath, t.message)
                        failedSubtitles++
                        repository.saveDownloadFile(file.copy(state = DownloadState.FAILED, error = t.message))
                        break
                    }
                    when (outcome) {
                        Outcome.DONE -> break
                        Outcome.CANCELLED -> {
                            forget(itemId)
                            return
                        }
                        // The network went away under it; wait, then carry on
                        // from where the partial file ends.
                        Outcome.PAUSED -> continue
                    }
                }
            }
            finish(itemId, failedSubtitles)
        } catch (t: Throwable) {
            if (itemId in cancelled) {
                forget(itemId)
                return
            }
            log.warn("下载 {} 失败", itemId, t)
            repository.saveProgressBytes(itemId, progress[itemId] ?: 0L)
            repository.updateDownloadState(itemId, DownloadState.FAILED, t.message ?: "下载失败")
        } finally {
            active -= itemId
        }
    }

    private enum class Outcome { DONE, CANCELLED, PAUSED }

    /**
     * Copies one file, from wherever a previous attempt stopped. The bytes go
     * to a `.part` file, so a half-copied video in a folder the user can open
     * is not mistaken for a whole one; it takes its real name on the last byte.
     */
    private fun fetch(itemId: String, file: DownloadFileRow): Outcome {
        val target = Path.of(file.file)
        val partial = partialOf(target)
        target.parent?.createDirectories()
        // A copy left under its final name — an earlier version wrote straight
        // to it — is the partial file, whatever the row says about it.
        if (target.exists() && !partial.exists()) Files.move(target, partial, StandardCopyOption.REPLACE_EXISTING)

        var written = if (partial.exists()) partial.fileSize() else 0L
        val base = repository.downloadFiles(itemId)
            .filter { it.mediaPath != file.mediaPath }
            .sumOf { onDisk(it) }
        progress[itemId] = base + written

        var total = file.totalBytes.takeIf { it > 0 }
            ?: runCatching { source.fileSize(file.mediaPath) }.getOrNull()
            ?: 0L
        repository.saveDownloadFile(file.copy(state = DownloadState.RUNNING, totalBytes = total, downloadedBytes = written))
        if (total > 0) repository.refreshDownloadTotal(itemId)

        if (total > 0 && written >= total) {
            complete(itemId, file, partial, target, written, total)
            return Outcome.DONE
        }

        val range = try {
            source.openRange(file.mediaPath, written, null)
        } catch (e: WebDavException) {
            // Asked for the byte after the last one: the share says there is
            // nothing past the partial file, so it was the whole file.
            if (e.status == 416 && written > 0) {
                complete(itemId, file, partial, target, written, written)
                return Outcome.DONE
            }
            throw e
        }

        var outcome = Outcome.DONE
        range.use { stream ->
            if (total <= 0) {
                stream.totalSize?.let {
                    total = it
                    repository.saveDownloadFile(file.copy(state = DownloadState.RUNNING, totalBytes = total, downloadedBytes = written))
                    repository.refreshDownloadTotal(itemId)
                }
            }
            RandomAccessFile(partial.toFile(), "rw").use { out ->
                out.seek(written)
                val buffer = ByteArray(BLOCK)
                var lastPersist = written
                while (true) {
                    if (itemId in cancelled) {
                        outcome = Outcome.CANCELLED
                        break
                    }
                    if (!transferGate()) {
                        outcome = Outcome.PAUSED
                        break
                    }
                    val read = stream.stream.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    written += read
                    progress[itemId] = base + written
                    // The row is the crash-resistant copy; the map is what the
                    // interface reads. Writing the row per block would be a
                    // database transaction every few milliseconds.
                    if (written - lastPersist > PERSIST_EVERY) {
                        persist(itemId, file, written, total, base)
                        lastPersist = written
                    }
                }
            }
        }
        // After the file is closed: an open file cannot be deleted on Windows.
        if (outcome != Outcome.DONE) {
            persist(itemId, file, written, total, base)
            return outcome
        }
        if (total > 0 && written < total) {
            persist(itemId, file, written, total, base)
            throw IllegalStateException("下载中断：$written / $total 字节")
        }
        complete(itemId, file, partial, target, written, total.takeIf { it > 0 } ?: written)
        return Outcome.DONE
    }

    private fun persist(itemId: String, file: DownloadFileRow, written: Long, total: Long, base: Long) {
        repository.saveDownloadFile(file.copy(state = DownloadState.RUNNING, totalBytes = total, downloadedBytes = written))
        repository.saveProgressBytes(itemId, base + written)
    }

    private fun complete(itemId: String, file: DownloadFileRow, partial: Path, target: Path, written: Long, total: Long) {
        if (partial.exists()) Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        repository.saveDownloadFile(
            file.copy(state = DownloadState.DONE, totalBytes = total, downloadedBytes = written, error = null)
        )
        repository.refreshDownloadTotal(itemId)
        val sum = repository.downloadFiles(itemId).sumOf { onDisk(it) }
        progress[itemId] = sum
        repository.saveProgressBytes(itemId, sum)
    }

    private fun finish(itemId: String, failedSubtitles: Int) {
        val rows = repository.downloadFiles(itemId)
        val sum = rows.sumOf { onDisk(it) }
        progress[itemId] = sum
        repository.refreshDownloadTotal(itemId)
        repository.saveProgressBytes(itemId, sum)
        repository.updateDownloadState(
            itemId,
            DownloadState.DONE,
            error = null,
            note = if (failedSubtitles > 0) "$failedSubtitles 个字幕未能下载" else null
        )
    }

    /**
     * Holds until [transferGate] opens, saying so on the row meanwhile. Returns
     * false if the download was cancelled while it waited.
     */
    private fun waitUntilAllowed(itemId: String): Boolean {
        if (transferGate()) return true
        repository.updateDownloadState(itemId, DownloadState.QUEUED, null, note = transferGateReason)
        while (!transferGate()) {
            if (itemId in cancelled) return false
            Thread.sleep(GATE_POLL_MS)
        }
        repository.updateDownloadState(itemId, DownloadState.RUNNING, null)
        return true
    }

    /** Drops every file and row of a download that was cancelled or removed. */
    private fun forget(itemId: String) {
        repository.downloadFiles(itemId).forEach { row ->
            val target = Path.of(row.file)
            runCatching { partialOf(target).deleteIfExists() }
            runCatching { target.deleteIfExists() }
            pruneEmptyDirectories(target.parent)
        }
        repository.deleteDownload(itemId)
        progress -= itemId
    }

    /**
     * Takes the folders a download made, back up to the download directory,
     * once nothing is left in them; a show removed from the device should not
     * leave its empty season folders behind.
     */
    private fun pruneEmptyDirectories(from: Path?) {
        val roots = setOf(defaultDir.toAbsolutePath().normalize(), directory().toAbsolutePath().normalize())
        var dir = from?.toAbsolutePath()?.normalize()
        while (dir != null) {
            val current: Path = dir
            if (current in roots || roots.none { current.startsWith(it) }) return
            // `Optional.isEmpty` is newer than Android's minimum; `isPresent` is not.
            val empty = runCatching { Files.list(current).use { !it.findAny().isPresent } }.getOrDefault(false)
            if (!empty) return
            runCatching { Files.deleteIfExists(current) }
            dir = current.parent
        }
    }

    /**
     * The poster and backdrop, into the same cache the pages read from, so the
     * detail page has a picture when there is no network to fetch one.
     */
    private fun keepArtwork(item: MediaItemDto) {
        val cache = images ?: return
        val urls = listOfNotNull(item.posterUrl, item.backdropUrl) +
            listOfNotNull(item.seriesId?.let { repository.item(it) }?.posterUrl)
        urls.distinct().forEach { url -> runCatching { cache.get(url) } }
    }

    // ------------------------------------------------------------ files

    private data class Wanted(val path: String, val kind: String) {
        val isVideo: Boolean get() = kind == DOWNLOAD_KIND_VIDEO
    }

    /** The video, then every external subtitle the scan found for it. */
    private fun wanted(item: MediaItemDto): List<Wanted> {
        val mediaPath = item.path ?: return emptyList()
        val subtitles = item.mediaStreams
            .filter { it.isExternal }
            .mapNotNull { it.externalPath?.takeIf { p -> p.isNotBlank() && p != mediaPath } }
            .distinct()
        return listOf(Wanted(mediaPath, DOWNLOAD_KIND_VIDEO)) +
            subtitles.map { Wanted(it, DOWNLOAD_KIND_SUBTITLE) }
    }

    private fun hasUntriedSubtitles(itemId: String): Boolean {
        val item = repository.item(itemId) ?: return false
        val rows = repository.downloadFiles(itemId).map { it.mediaPath }.toSet()
        return wanted(item).any { !it.isVideo && it.path !in rows }
    }

    private fun DownloadFileRow.isWhole(): Boolean =
        state == DownloadState.DONE && Path.of(file).exists()

    /** Bytes of a file that are on disk, finished or not. */
    private fun onDisk(row: DownloadFileRow): Long {
        val target = Path.of(row.file)
        if (row.state == DownloadState.DONE) {
            return runCatching { target.fileSize() }.getOrDefault(row.downloadedBytes)
        }
        val partial = partialOf(target)
        return when {
            partial.exists() -> runCatching { partial.fileSize() }.getOrDefault(0L)
            target.exists() -> runCatching { target.fileSize() }.getOrDefault(0L)
            else -> 0L
        }
    }

    private fun partialOf(target: Path): Path = Path.of(target.toString() + PART_SUFFIX)

    /**
     * Where a media path lands: the share's own folders, mirrored under the
     * download directory, with each name made safe for the file system here.
     * Readable in a file manager, and unique because the share path is.
     */
    private fun targetFor(mediaPath: String): Path =
        mediaPath.trim('/').split('/').filter { it.isNotBlank() }
            .fold(directory()) { dir, segment -> dir.resolve(safeName(segment)) }

    /**
     * A subtitle goes beside the video whatever folder it was in on the share
     * (a `Subs/` folder, say), under its own name: that is where every player
     * looks for one, and the name is what ties it to the video.
     */
    private fun sidecarTargetFor(videoTarget: Path, subtitlePath: String, taken: Set<String>): String {
        val dir = videoTarget.parent ?: directory()
        val name = safeName(subtitlePath.substringAfterLast('/'))
        var candidate = dir.resolve(name)
        var n = 2
        while (candidate.toString() in taken) {
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            candidate = dir.resolve(if (ext.isEmpty()) "$stem.$n" else "$stem.$n.$ext")
            n++
        }
        return candidate.toString()
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val BLOCK = 1 shl 16
        private const val PERSIST_EVERY = 8L * 1024 * 1024
        private const val GATE_POLL_MS = 3_000L

        private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
        private val RESERVED = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOption.IGNORE_CASE)
        private const val MAX_NAME = 180

        /**
         * A share name as this file system will take it. Windows is the strict
         * one: nine characters it refuses outright, names it reserves, and no
         * trailing dots or spaces. Applied everywhere, so a download made on
         * one machine has the same layout as on another.
         */
        fun safeName(name: String): String {
            var out = name.replace(UNSAFE, "_").trimEnd('.', ' ').trim()
            if (out.isEmpty()) out = "_"
            if (RESERVED.matches(out.substringBefore('.'))) out = "_$out"
            if (out.length > MAX_NAME) {
                val ext = out.substringAfterLast('.', "")
                out = if (ext.isNotEmpty() && ext.length < 12) {
                    out.substring(0, MAX_NAME - ext.length - 1).trimEnd('.', ' ') + "." + ext
                } else {
                    out.substring(0, MAX_NAME).trimEnd('.', ' ')
                }
            }
            return out
        }
    }
}
