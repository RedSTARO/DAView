package com.daview.server.sync

import com.daview.server.ServerContext
import com.daview.server.api.BackupOptions
import com.daview.server.api.applyBackup
import com.daview.server.api.buildBackup
import com.daview.server.storage.WebDavClient
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.BACKUP_FORMAT
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.BackupSummaryDto
import com.daview.shared.model.SyncResultDto
import com.daview.shared.model.MetadataProvider
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
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
    private val startupClaimed = AtomicBoolean(false)
    private val startupResult = CompletableFuture<SyncResultDto?>()

    /** Fingerprint of everything the file carries, at the last successful upload. */
    @Volatile
    private var uploadedFingerprint: Long? = null

    /**
     * When a pull was last tried, whether or not it worked. Only a successful
     * one moves `lastPullAt`, so with the share out of reach every tick — one
     * a minute — tried again and wrote the same error into the config file.
     */
    @Volatile
    private var lastPullAttemptAt = 0L

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
     * The database revision means no call site has to remember to notify
     * this service when progress is written, even when timestamps tie.
     */
    internal fun tick() {
        // Nothing may escape: a scheduled task that throws once is never run
        // again, and the sync would stop for the rest of the process's life
        // with the switch still showing on.
        try {
            tickOnce()
        } catch (t: Throwable) {
            log.warn("同步检查失败: {}", t.message)
        }
    }

    private fun tickOnce() {
        pullOnStartup()
        val config = context.config.sync
        if (!config.enabled) return
        val now = System.currentTimeMillis()

        val pullInterval = pullIntervalMs(config.minIntervalMinutes)
        if (now - (config.lastPullAt ?: 0L) >= pullInterval && now - lastPullAttemptAt >= pullInterval) {
            lastPullAttemptAt = now
            runCatching { pull() }.onFailure { log.warn("自动拉取失败: {}", it.message) }
        }

        val elapsed = System.currentTimeMillis() - (context.config.sync.lastUploadAt ?: 0L)
        if (elapsed < config.minIntervalMinutes.coerceAtLeast(1) * 60_000L) return
        if (context.repository.syncFingerprint() == uploadedFingerprint) return
        runCatching { upload(automatic = true) }
            .onFailure { log.warn("自动同步失败: {}", it.message) }
    }

    /** Reading is cheap and idempotent, so it runs more often than writing. */
    private fun pullIntervalMs(uploadIntervalMinutes: Int): Long =
        (uploadIntervalMinutes.coerceAtLeast(1) * 60_000L) / 2

    /**
     * Reads once before this context's initial library load, ignoring the last
     * process's pull time. Every other sync entry point crosses the same barrier,
     * so a timer or manual upload cannot take the operation guard first and
     * cause startup to return "busy" without merging the remote progress.
     *
     * Activity recreation shares this completed result. Disabled sync returns
     * null without a request; an unavailable share finishes within the network
     * deadline and leaves its failure available to the caller and settings.
     */
    fun pullOnStartup(): SyncResultDto? {
        if (startupClaimed.compareAndSet(false, true)) {
            try {
                startupResult.complete(if (context.config.sync.enabled) {
                    lastPullAttemptAt = System.currentTimeMillis()
                    pullOnce(callTimeoutMs = STARTUP_PULL_TIMEOUT_MS)
                } else null)
            } catch (e: Exception) {
                // A recoverable startup failure must not poison the one-shot
                // barrier: later manual and periodic sync still need to run.
                val message = "启动同步失败: ${e.message ?: e::class.simpleName}"
                log.warn("{}", message)
                val failed = runCatching { fail(message) }.getOrElse {
                    log.warn("记录启动同步失败信息时出错: {}", it.message)
                    SyncResultDto(ok = false, message = message, at = System.currentTimeMillis())
                }
                startupResult.complete(failed)
            } catch (t: Throwable) {
                startupResult.completeExceptionally(t)
                throw t
            }
        }
        return startupResult.join()
    }

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
        pullOnStartup()
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
                // A parse/apply failure can be a newer file format or locally
                // unsupported data. Keep the remote copy available for recovery.
                is Merge.Unreadable -> return fail("云端同步文件无法合并，已保留原文件并跳过上传: ${merged.reason}")
            }
            // Capture before serialisation. A local edit during PUT was not in
            // these bytes and must remain eligible for the next upload.
            val fingerprint = context.repository.syncFingerprint()
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
            uploadedFingerprint = fingerprint
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
        pullOnStartup()
        if (!running.compareAndSet(false, true)) {
            return SyncResultDto(ok = false, message = "同步正在进行中", at = System.currentTimeMillis())
        }
        try {
            return pullOnce()
        } finally {
            running.set(false)
        }
    }

    private fun pullOnce(callTimeoutMs: Long = 0): SyncResultDto {
        val dav = davProvider() ?: return fail("WebDAV 未配置")
        val path = context.config.sync.remotePath.ifBlank { DEFAULT_PATH }
        val outcome = mergeRemote(dav, path, callTimeoutMs)
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
    }

    /**
     * Reads the file and merges it into the local database.
     *
     * The three failure shapes are kept apart because [upload] treats them
     * differently: an absent file can be created; an unreadable, unsupported
     * or unreachable file must stay intact for a later retry or recovery.
     */
    private fun mergeRemote(dav: WebDavClient, path: String, callTimeoutMs: Long = 0): Merge {
        val read = dav.read(path, callTimeoutMs = callTimeoutMs)
        read.error?.let { return Merge.Unreachable(it) }
        val raw = read.bytes ?: run {
            // A file removed after our last upload must be recreated even if
            // nothing changed locally since that successful upload.
            uploadedFingerprint = null
            return Merge.Missing
        }
        val backup = runCatching {
            val document = DaViewJson.parseToJsonElement(raw.decodeToString()) as? JsonObject
                ?: error("不是 DAView 同步文件")
            // Defaults on the DTO serve old optional fields, not identification.
            // A gateway's {"error": ...} must not become an empty valid backup.
            require((document["format"] as? JsonPrimitive)?.contentOrNull == BACKUP_FORMAT &&
                (document["version"] as? JsonPrimitive)?.intOrNull != null) { "不是 DAView 同步文件" }
            DaViewJson.decodeFromJsonElement(BackupFileDto.serializer(), document)
        }.getOrElse { return Merge.Unreadable(it.message ?: it::class.simpleName ?: "未知错误") }

        val summary = runCatching { applyBackup(context, backup, mergeUserDataByTimestamp = true, machineLocal = true) }
            .getOrElse { return Merge.Unreadable(it.message ?: "格式不符") }
        context.updateConfig {
            it.copy(sync = it.sync.copy(lastPullAt = System.currentTimeMillis(), lastError = null))
        }
        // Another client's PUT can replace our successful upload with an older
        // snapshot. A merge may leave local data unchanged; the local revision
        // alone then cannot notice that the remote copy needs those rows back.
        if (hasUnsentRows(backup)) uploadedFingerprint = null
        return Merge.Applied(summary)
    }

    private fun hasUnsentRows(remote: BackupFileDto): Boolean {
        val userTimes = remote.userData.groupBy { it.itemId }.mapValues { (_, rows) -> rows.maxOf { it.updatedAt } }
        if (context.repository.allUserData().any { local ->
            val at = userTimes[local.itemId]
            at == null || local.updatedAt > at
        }) return true
        val libraryTimes = remote.libraries.groupBy { it.id }.mapValues { (_, rows) -> rows.maxOf { it.updatedAt } }
        if (context.repository.libraries().any { local ->
            val at = libraryTimes[local.id]
            at == null || local.updatedAt > at
        }) return true
        val pinTimes = remote.pins.groupBy { it.itemId }.mapValues { (_, rows) -> rows.maxOf { it.updatedAt } }
        return context.repository.allPins().any { local ->
            // Backup export deliberately skips future provider values this
            // client cannot represent. Do not retry forever trying to send one.
            if (MetadataProvider.entries.none { it.name == local.provider }) false
            else pinTimes[local.itemId]?.let { local.updatedAt > it } ?: true
        }
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
        // Only when it is news: the config file is rewritten on every change,
        // and the same failure repeating is not one.
        if (context.config.sync.lastError != message) {
            context.updateConfig { it.copy(sync = it.sync.copy(lastError = message)) }
        }
        return SyncResultDto(ok = false, message = message, at = System.currentTimeMillis())
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    private companion object {
        const val DEFAULT_PATH = "/daview-sync.json"
        const val TICK_SECONDS = 60L
        const val STARTUP_PULL_TIMEOUT_MS = 10_000L

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
