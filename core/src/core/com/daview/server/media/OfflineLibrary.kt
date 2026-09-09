package com.daview.server.media

import com.daview.server.db.Repository
import com.daview.shared.model.DownloadDto
import com.daview.shared.model.DownloadState
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/**
 * Keeps whole files on this device so they can be watched with no network.
 *
 * The app is otherwise entirely a window onto a share: everything streams, and
 * away from Wi-Fi there is nothing to watch. This is the one place bytes are
 * kept rather than passed through.
 *
 * Downloads are resumable by construction — the file on disk is the progress,
 * and a new attempt asks the share for the range after whatever is already
 * there — so a dropped connection or a killed process costs nothing but the
 * partial block. Nothing here writes to the share.
 */
class OfflineLibrary(
    dataDir: Path,
    private val repository: Repository,
    private val streams: StreamService
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(OfflineLibrary::class.java)
    private val dir: Path = dataDir.resolve("offline").also { it.createDirectories() }

    /**
     * One at a time. Two downloads over one share share the same bandwidth and
     * finish in the same total time, but sequentially the first one becomes
     * watchable while the second is still going.
     */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "daview-download").apply { isDaemon = true }
    }

    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** Live byte counts, so progress does not cost a write per block. */
    private val progress = ConcurrentHashMap<String, Long>()

    /** Where a finished download lives, addressed by the media path it came from. */
    fun localFile(mediaPath: String): Path? {
        val row = repository.downloadForPath(mediaPath) ?: return null
        if (row.state != DownloadState.DONE) return null
        val file = Path.of(row.file)
        return file.takeIf { it.exists() }
    }

    fun all(): List<DownloadDto> = repository.downloads().map { row ->
        row.copy(downloadedBytes = progress[row.itemId] ?: row.downloadedBytes)
    }

    fun status(itemId: String): DownloadDto? = all().firstOrNull { it.itemId == itemId }

    /**
     * Queues an item. Already queued, running or finished, this does nothing —
     * pressing download twice should not start a second copy.
     */
    fun start(itemId: String) {
        val item = repository.item(itemId) ?: return
        val mediaPath = item.path?.takeIf { it.isNotBlank() } ?: return
        val existing = repository.download(itemId)
        if (existing != null && existing.state != DownloadState.FAILED) return

        cancelled -= itemId
        val target = dir.resolve(itemId + "." + mediaPath.substringAfterLast('.', "bin"))
        repository.saveDownload(
            DownloadDto(
                itemId = itemId,
                name = item.name,
                state = DownloadState.QUEUED,
                totalBytes = item.sizeBytes ?: 0L,
                downloadedBytes = if (target.exists()) target.fileSize() else 0L
            ),
            file = target.toString(),
            mediaPath = mediaPath
        )
        executor.submit { run(itemId, mediaPath, target) }
    }

    /** Stops a download in flight. What has arrived stays, ready to resume. */
    fun cancel(itemId: String) {
        cancelled += itemId
    }

    /** Forgets a download and deletes its bytes. */
    fun remove(itemId: String) {
        cancel(itemId)
        repository.item(itemId)?.path
            ?.let { repository.downloadForPath(it) }
            ?.let { runCatching { Path.of(it.file).deleteIfExists() } }
        repository.deleteDownload(itemId)
        progress -= itemId
    }

    private fun run(itemId: String, mediaPath: String, target: Path) {
        if (itemId in cancelled) {
            repository.updateDownloadState(itemId, DownloadState.FAILED, "已取消")
            return
        }
        repository.updateDownloadState(itemId, DownloadState.RUNNING, null)

        try {
            var written = if (target.exists()) target.fileSize() else 0L
            val total = streams.fileSize(mediaPath) ?: repository.item(itemId)?.sizeBytes ?: 0L
            if (total > 0 && written >= total) {
                finish(itemId, total)
                return
            }

            streams.openRange(mediaPath, written, null).use { range ->
                val size = total.takeIf { it > 0 } ?: ((range.totalSize ?: 0L))
                RandomAccessFile(target.toFile(), "rw").use { file ->
                    file.seek(written)
                    val buffer = ByteArray(BLOCK)
                    var lastPersist = 0L
                    while (true) {
                        if (itemId in cancelled) {
                            repository.saveProgressBytes(itemId, written)
                            repository.updateDownloadState(itemId, DownloadState.FAILED, "已取消")
                            return
                        }
                        val read = range.stream.read(buffer)
                        if (read <= 0) break
                        file.write(buffer, 0, read)
                        written += read
                        progress[itemId] = written
                        // The row is the crash-resistant copy; the map is what
                        // the interface reads. Writing the row per block would
                        // be a database transaction every few milliseconds.
                        if (written - lastPersist > PERSIST_EVERY) {
                            repository.saveProgressBytes(itemId, written)
                            lastPersist = written
                        }
                    }
                    if (size > 0 && written < size) {
                        throw IllegalStateException("下载中断：$written / $size 字节")
                    }
                }
            }
            finish(itemId, written)
        } catch (t: Throwable) {
            log.warn("下载 {} 失败", itemId, t)
            repository.saveProgressBytes(itemId, progress[itemId] ?: 0L)
            repository.updateDownloadState(itemId, DownloadState.FAILED, t.message ?: "下载失败")
        }
    }

    private fun finish(itemId: String, bytes: Long) {
        progress[itemId] = bytes
        repository.saveProgressBytes(itemId, bytes)
        repository.updateDownloadState(itemId, DownloadState.DONE, null)
    }

    /** Total bytes held on this device. */
    fun usedBytes(): Long = runCatching {
        Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                .sum()
        }
    }.getOrDefault(0L)

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val BLOCK = 1 shl 16
        const val PERSIST_EVERY = 8L * 1024 * 1024
    }
}
