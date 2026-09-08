package com.daview.server.sync

import com.daview.server.ServerContext
import com.daview.server.api.BackupOptions
import com.daview.server.api.applyBackup
import com.daview.server.api.buildBackup
import com.daview.server.storage.WebDavClient
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.BackupFileDto
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
     * Uploads when something has actually changed and the interval has elapsed.
     * The fingerprint (newest timestamp + row count) means no call site has to
     * remember to notify this service when progress is written.
     */
    private fun tick() {
        val config = context.config.sync
        if (!config.enabled) return
        val elapsed = System.currentTimeMillis() - (config.lastUploadAt ?: 0L)
        if (elapsed < config.minIntervalMinutes.coerceAtLeast(1) * 60_000L) return
        if (context.repository.userDataFingerprint() == uploadedFingerprint) return
        runCatching { upload(automatic = true) }
            .onFailure { log.warn("自动同步失败: {}", it.message) }
    }

    /** Builds the sync payload and writes it to the share. */
    fun upload(automatic: Boolean = false): SyncResultDto {
        if (!running.compareAndSet(false, true)) {
            return SyncResultDto(ok = false, message = "同步正在进行中", at = System.currentTimeMillis())
        }
        try {
            val dav = davProvider()
                ?: return fail("WebDAV 未配置")
            val path = context.config.sync.remotePath.ifBlank { DEFAULT_PATH }
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
        val dav = davProvider() ?: return fail("WebDAV 未配置")
        val path = context.config.sync.remotePath.ifBlank { DEFAULT_PATH }
        val raw = dav.readIfPresent(path)
            ?: return fail("$path 上还没有同步文件")

        val backup = runCatching {
            DaViewJson.decodeFromString(BackupFileDto.serializer(), raw.decodeToString())
        }.getOrElse { return fail("同步文件解析失败: ${it.message}") }

        val summary = applyBackup(context, backup, mergeUserDataByTimestamp = true)
        val now = System.currentTimeMillis()
        context.updateConfig { it.copy(sync = it.sync.copy(lastPullAt = now, lastError = null)) }
        // What we just merged is now also what the file holds.
        uploadedFingerprint = context.repository.userDataFingerprint()
        return SyncResultDto(
            ok = true,
            message = "已合并 ${summary.userData} 条观看记录、${summary.libraries} 个媒体库",
            at = now,
            libraries = summary.libraries,
            userData = summary.userData
        )
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
         * Settings, libraries and watch state only. The catalogue is left out on
         * purpose: it is reproducible by scanning and would make every upload
         * three orders of magnitude bigger.
         */
        val SYNC_SECTIONS = BackupOptions(
            settings = true,
            libraries = true,
            items = false,
            userData = true,
            secrets = false
        )
    }
}
