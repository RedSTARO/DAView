package com.daview.server.sync

import com.daview.server.ServerContext
import com.daview.server.api.BackupOptions
import com.daview.server.api.applyBackup
import com.daview.server.api.buildBackup
import com.daview.server.storage.WebDavClient
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.BackupSummaryDto
import com.daview.shared.model.SyncResultDto
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-device sync through the share itself.
 *
 * There is no second server to talk to, so the devices agree through one file
 * on the storage they already share. It carries the settings, the library
 * definitions and the watch state — not the scraped catalogue, which every
 * device can rebuild by scanning and which would turn a 2 KB upload into 4 MB.
 *
 * Whether the storage accepts writes at all cannot be asked politely: the
 * 123pan gateway leaves `PUT` out of the `OPTIONS` `Allow` header even when it
 * honours it, and answers 403 when it does not. So the first upload is the
 * test, and a refusal is reported to the user rather than retried.
 */
class SyncService(
    private val context: ServerContext,
    private val davProvider: () -> WebDavClient?
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(SyncService::class.java)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "daview-sync").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    /** Watch-state fingerprint at the last successful upload. */
    @Volatile
    private var uploadedFingerprint: Pair<Long, Int>? = null

    @Volatile
    var storageWritable: Boolean? = null
        private set

    init {
        scheduler.scheduleWithFixedDelay(::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Pulls on a schedule and uploads when something has actually changed.
     *
     * Both halves have to be automatic. Every device now scans and scrapes on
     * its own and the file is the only thing they share, so a device that only
     * ever wrote would never learn what the others watched — which is what
     * "press 从云端合并 yourself" amounted to.
     *
     * The upload fingerprint (newest timestamp + row count) means no call site
     * has to remember to notify this service when progress is written.
     */
    private fun tick() {
        val config = context.config.sync
        if (!config.enabled) return
        val now = System.currentTimeMillis()

        if (now - (config.lastPullAt ?: 0L) >= pullIntervalMs(config.minIntervalMinutes)) {
            runCatching { pull() }.onFailure { log.warn("自动拉取失败: {}", it.message) }
        }

        val elapsed = System.currentTimeMillis() - (context.config.sync.lastUploadAt ?: 0L)
        if (elapsed < config.minIntervalMinutes.coerceAtLeast(1) * 60_000L) return
        if (context.repository.userDataFingerprint() == uploadedFingerprint) return
        runCatching { upload(automatic = true) }
            .onFailure { log.warn("自动同步失败: {}", it.message) }
    }

    /** Reading is cheap and idempotent, so it runs more often than writing. */
    private fun pullIntervalMs(uploadIntervalMinutes: Int): Long =
        (uploadIntervalMinutes.coerceAtLeast(1) * 60_000L) / 2

    /**
     * Merges what is already on the share, then writes the result back.
     *
     * The merge is not optional. `PUT` replaces the whole file and this gateway
     * has no conditional write (no `If-Match`), so uploading the local state
     * blind would drop every row another device wrote since this one last read
     * — silently, on a timer. Reading first narrows the window where that can
     * happen to the gap between this read and this write.
     */
    fun upload(automatic: Boolean = false): SyncResultDto {
        if (!running.compareAndSet(false, true)) {
            return SyncResultDto(ok = false, message = "同步正在进行中", at = System.currentTimeMillis())
        }
        try {
            val dav = davProvider()
                ?: return fail("WebDAV 未配置")
            val path = context.config.sync.remotePath.ifBlank { DEFAULT_PATH }
            when (val merged = mergeRemote(dav, path)) {
                is Merge.Applied, Merge.Missing -> Unit
                // Replacing a file we could not read would discard whatever the
                // other devices put in it, so this upload does not happen.
                is Merge.Unreachable -> return fail("读取云端同步文件失败，已跳过这次上传: ${merged.reason}")
                // Garbled is different: nothing can be salvaged from it, and
                // writing valid content over it is the repair.
                is Merge.Unreadable -> log.warn("云端同步文件无法解析，将被覆盖: {}", merged.reason)
            }
            val bytes = buildBackup(context, SYNC_SECTIONS).toByteArray(Charsets.UTF_8)

            val result = dav.put(path, bytes)
            val now = System.currentTimeMillis()
            if (!result.ok) {
                storageWritable = if (result.forbidden) false else storageWritable
                val message = if (result.forbidden) {
                    "这个 WebDAV 不允许写入（${result.status}），无法同步"
                } else {
                    "上传失败: ${result.message ?: result.status}"
                }
                context.updateConfig { it.copy(sync = it.sync.copy(lastError = message)) }
                return SyncResultDto(
                    ok = false, message = message, at = now, readOnlyStorage = result.forbidden
                )
            }

            storageWritable = true
            uploadedFingerprint = context.repository.userDataFingerprint()
            context.updateConfig { it.copy(sync = it.sync.copy(lastUploadAt = now, lastError = null)) }
            if (!automatic) log.info("同步已上传 {} 字节到 {}", bytes.size, path)
            return SyncResultDto(
                ok = true,
                message = "已上传 ${bytes.size} 字节到 $path",
                at = now,
                bytes = bytes.size
            )
        } finally {
            running.set(false)
        }
    }

    /** Reads the file back and merges it, keeping whichever side of a row is newer. */
    fun pull(): SyncResultDto {
        if (!running.compareAndSet(false, true)) {
            return SyncResultDto(ok = false, message = "同步正在进行中", at = System.currentTimeMillis())
        }
        try {
            val dav = davProvider() ?: return fail("WebDAV 未配置")
            val path = context.config.sync.remotePath.ifBlank { DEFAULT_PATH }
            val outcome = mergeRemote(dav, path)
            val now = System.currentTimeMillis()
            return when (outcome) {
                is Merge.Applied -> SyncResultDto(
                    ok = true,
                    message = "已合并 ${outcome.summary.userData} 条观看记录、" +
                        "${outcome.summary.libraries} 个媒体库",
                    at = now,
                    libraries = outcome.summary.libraries,
                    userData = outcome.summary.userData
                )

                Merge.Missing -> fail("$path 上还没有同步文件")
                is Merge.Unreadable -> fail("同步文件解析失败: ${outcome.reason}")
                is Merge.Unreachable -> fail("读取同步文件失败: ${outcome.reason}")
            }
        } finally {
            running.set(false)
        }
    }

    /**
     * Reads the file and merges it into the local database.
     *
     * The three failure shapes are kept apart because [upload] treats them
     * differently: a file that is absent or garbled can be replaced, while one
     * that could not be read must not be — that would trade a network blip for
     * every row the other devices wrote.
     */
    private fun mergeRemote(dav: WebDavClient, path: String): Merge {
        val read = dav.read(path)
        read.error?.let { return Merge.Unreachable(it) }
        val raw = read.bytes ?: return Merge.Missing
        val backup = runCatching {
            DaViewJson.decodeFromString(BackupFileDto.serializer(), raw.decodeToString())
        }.getOrElse { return Merge.Unreadable(it.message ?: it::class.simpleName ?: "未知错误") }

        val summary = runCatching { applyBackup(context, backup, mergeUserDataByTimestamp = true, machineLocal = true) }
            .getOrElse { return Merge.Unreadable(it.message ?: "格式不符") }
        context.updateConfig {
            it.copy(sync = it.sync.copy(lastPullAt = System.currentTimeMillis(), lastError = null))
        }
        // Deliberately not touching uploadedFingerprint here. Rows this device
        // holds and the file does not are still unsent, so calling the merged
        // state "uploaded" would suppress the upload that carries them.
        return Merge.Applied(summary)
    }

    private sealed interface Merge {
        data class Applied(val summary: BackupSummaryDto) : Merge
        data object Missing : Merge
        data class Unreadable(val reason: String) : Merge
        data class Unreachable(val reason: String) : Merge
    }

    /**
     * Turns sync on only if the storage really takes the file. Leaving it on
     * against a read-only share would mean a failing background job and no
     * explanation, so the switch stays off and the reason is returned.
     */
    fun enable(): SyncResultDto {
        context.updateConfig { it.copy(sync = it.sync.copy(enabled = true)) }
        val result = upload()
        if (!result.ok) {
            context.updateConfig { it.copy(sync = it.sync.copy(enabled = false)) }
        }
        return result
    }

    fun disable() {
        context.updateConfig { it.copy(sync = it.sync.copy(enabled = false, lastError = null)) }
    }

    private fun fail(message: String): SyncResultDto {
        context.updateConfig { it.copy(sync = it.sync.copy(lastError = message)) }
        return SyncResultDto(ok = false, message = message, at = System.currentTimeMillis())
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    private companion object {
        const val DEFAULT_PATH = "/daview-sync.json"
        const val TICK_SECONDS = 60L

        /**
         * Settings, libraries, watch state and the hand-picked scrape entries.
         * The catalogue is left out on purpose: it is reproducible by scanning
         * and would make every upload three orders of magnitude bigger. The pins
         * are the exception because they are not reproducible — they are the
         * corrections to what scraping got wrong.
         */
        val SYNC_SECTIONS = BackupOptions(
            settings = true,
            libraries = true,
            items = false,
            userData = true,
            pins = true,
            secrets = false
        )
    }
}
