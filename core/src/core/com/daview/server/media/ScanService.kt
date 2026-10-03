package com.daview.server.media

import com.daview.server.config.AppConfig
import com.daview.server.config.ScraperConfig
import com.daview.server.db.Repository
import com.daview.server.library.Scanner
import com.daview.server.scraper.MetadataService
import com.daview.server.storage.DirectoryLister
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.ScanMode
import com.daview.shared.model.ScanProgressDto
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Runs library scans one at a time and exposes their progress to the API. */
class ScanService(
    private val repository: Repository,
    private val metadata: MetadataService,
    private val streams: StreamService,
    private val davProvider: () -> DirectoryLister?,
    private val configProvider: () -> AppConfig
) {
    private val log = LoggerFactory.getLogger(ScanService::class.java)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "daview-scan").apply { isDaemon = true }
    }
    private val progress = ConcurrentHashMap<String, ScanProgressDto>()
    private val cancellations = ConcurrentHashMap<String, Cancellation>()
    private val submissionLock = Any()

    fun status(): List<ScanProgressDto> = progress.values.sortedBy { it.libraryName }

    fun isRunning(libraryId: String): Boolean = progress[libraryId]?.running == true

    fun anyRunning(): Boolean = progress.values.any { it.running }

    /**
     * Asks a scan to stop at its next step.
     *
     * There is no safe point to interrupt a thread that is mid-request to the
     * storage or mid-write to SQLite, so the flag is read where progress is
     * reported, and polled by the probe coordinator while readers are blocked.
     */
    fun cancel(libraryId: String) {
        synchronized(submissionLock) {
            if (isRunning(libraryId)) cancellations[libraryId]?.cancel()
        }
    }

    private class ScanCancelled : RuntimeException("已取消")

    /** One token per submitted scan; cancelling and committing share a lock. */
    internal class Cancellation {
        @Volatile private var cancelled = false

        fun cancel() = synchronized(this) { cancelled = true }

        fun checkCancelled() {
            if (cancelled) throw ScanCancelled()
        }

        fun <T> whileActive(action: () -> T): T = synchronized(this) {
            checkCancelled()
            action()
        }
    }

    /**
     * Queues a scan. [automatic] marks one nobody asked for — the check the
     * app runs on start-up — so the interface can stay quiet about it unless
     * it turns something up.
     */
    fun submit(library: LibraryDto, mode: ScanMode, automatic: Boolean = false): ScanProgressDto = synchronized(submissionLock) {
        if (isRunning(library.id)) return@synchronized progress.getValue(library.id)
        val initial = ScanProgressDto(
            libraryId = library.id,
            libraryName = library.name,
            phase = "queued",
            current = 0,
            total = 0,
            // The phase already says it is queued; every page puts the phase
            // in front of the message, so repeating it here read 「排队中 · 排队中」.
            message = if (mode == ScanMode.MISSING) "仅刮削未刮削的条目" else "",
            automatic = automatic
        )
        val cancellation = Cancellation()
        cancellations[library.id] = cancellation
        progress[library.id] = initial
        executor.submit { runScan(library, mode, cancellation) }
        initial
    }

    private fun update(libraryId: String, phase: String, current: Int, total: Int, message: String) {
        cancellations.getValue(libraryId).whileActive {
            progress[libraryId] = progress.getValue(libraryId).copy(
                phase = phase, current = current, total = total, message = message, running = true
            )
        }
    }

    private fun runScan(library: LibraryDto, mode: ScanMode, cancellation: Cancellation) {
        try {
            cancellation.checkCancelled()
            val dav = davProvider() ?: error("WebDAV 未配置")
            val config = configProvider()
            val scraper = config.scraper.copy(
                // Blank means "whatever the app is set to". Libraries used to
                // be created with a hardcoded language, so the setting on
                // screen never reached a scraper.
                language = library.language.ifBlank { config.scraper.language }
            )
            val sink = Scanner.ProgressSink { phase, current, total, message ->
                update(library.id, phase, current, total, message)
            }

            val result = when (mode) {
                // MISSING skips the file walk on purpose: nothing about the
                // files has changed, and walking 118 folders to fill in a
                // handful of unmatched titles is the slow way round.
                ScanMode.MISSING -> {
                    enrichLibrary(library, scraper, force = false)
                    null
                }
                ScanMode.FULL -> incremental(library, dav, scraper, sink)
                ScanMode.REFRESH -> {
                    val walk = Scanner(dav, repository).begin(library, sink)
                    walk.scanNew()
                    walk.scanExisting()
                    walk.finish().also { enrichLibrary(library, scraper, force = true) }
                }
            }
            result?.let {
                log.info(
                    "库 {} 扫描完成: {} 项，新增 {} 部 / {} 集，移除 {} 项",
                    library.name, it.itemCount, it.newTitles, it.newEpisodes, it.removed
                )
            }

            if (mode != ScanMode.MISSING) {
                streams.probeMissing(library.id, PROBE_BUDGET, cancellation = cancellation) { current, total, message ->
                    update(library.id, "probing", current, total, message)
                }
            }

            cancellation.whileActive {
                progress[library.id] = progress.getValue(library.id).copy(
                    phase = "done",
                    running = false,
                    message = doneMessage(result),
                    newTitles = result?.newTitles ?: 0,
                    newEpisodes = result?.newEpisodes ?: 0,
                    removed = result?.removed ?: 0,
                    finishedAt = System.currentTimeMillis()
                )
            }
        } catch (cancel: ScanCancelled) {
            log.info("库 {} 的扫描已取消", library.name)
            progress[library.id] = progress.getValue(library.id).copy(
                phase = "cancelled", running = false, message = "已取消",
                finishedAt = System.currentTimeMillis()
            )
        } catch (t: Throwable) {
            log.error("扫描 {} 失败", library.name, t)
            progress[library.id] = progress.getValue(library.id).copy(
                phase = "error", running = false, error = t.message ?: t.toString(),
                finishedAt = System.currentTimeMillis()
            )
        } finally {
            cancellations.remove(library.id, cancellation)
        }
    }

    /**
     * The everyday scan, one level at a time.
     *
     * Folders the library has never seen come first and are scraped as soon
     * as they have been read, so a new show has its poster while the rest of
     * the share is still being checked. Only then are the folders it already
     * knew read again for new seasons and episodes; a show that gained some
     * has its episode titles fetched again, which a plain "scrape what has no
     * metadata" never did — the show itself was scraped long ago.
     */
    private fun incremental(
        library: LibraryDto,
        dav: DirectoryLister,
        scraper: ScraperConfig,
        sink: Scanner.ProgressSink
    ): Scanner.Result {
        val walk = Scanner(dav, repository).begin(library, sink)
        val attempted = HashSet<String>()

        val fresh = walk.scanNew()
        scrape(library, scraper, fresh, attempted)

        val changes = walk.scanExisting()
        changes.seriesWithNewEpisodes.forEachIndexed { index, seriesId ->
            val series = repository.item(seriesId) ?: return@forEachIndexed
            // Episodes of a folder merged into another show belong to the
            // show it was merged into; that is the one whose ids fetch them.
            val target = series.mergedInto?.let { repository.item(it) } ?: series
            update(library.id, "scraping", index + 1, changes.seriesWithNewEpisodes.size, "更新分集：${target.name}")
            runCatching { metadata.refreshEpisodes(target, library, scraper) }
                .onFailure { log.warn("更新 {} 的分集失败: {}", target.name, it.message) }
        }

        // Titles that turned up inside folders that already existed, plus
        // whatever earlier scans could not match: a new key or a renamed
        // folder is its chance. What was tried a moment ago is not tried twice.
        val remaining = (changes.newTitleIds + repository.itemsNeedingScrape(library.id, force = false).map { it.id })
            .distinct()
            .filter { it !in attempted }
        scrape(library, scraper, remaining, attempted)

        return walk.finish()
    }

    private fun scrape(library: LibraryDto, scraper: ScraperConfig, ids: List<String>, attempted: MutableSet<String>) {
        if (ids.isEmpty()) return
        val items = ids.mapNotNull { repository.item(it) }
        attempted += ids
        metadata.enrichItems(library, scraper, items) { current, total, message ->
            update(library.id, "scraping", current, total, message)
        }
    }

    private fun enrichLibrary(library: LibraryDto, scraper: ScraperConfig, force: Boolean) {
        metadata.enrichLibrary(library, scraper, force) { current, total, message ->
            update(library.id, "scraping", current, total, message)
        }
    }

    /** What the card says once the scan is over: the changes, or that there were none. */
    private fun doneMessage(result: Scanner.Result?): String {
        if (result == null) return "完成"
        val parts = buildList {
            if (result.newTitles > 0) add("新增 ${result.newTitles} 部")
            if (result.newEpisodes > 0) add("新增 ${result.newEpisodes} 集")
            if (result.removed > 0) add("移除 ${result.removed} 项")
        }
        val summary = if (parts.isEmpty()) "完成，没有变化" else "完成：" + parts.joinToString("，")
        return if (result.warnings.isEmpty()) summary else "$summary（${result.warnings.size} 个警告）"
    }

    private companion object {
        /** Container probing costs a round trip or two per file (a transport stream's tail is the second); cap it per scan. */
        const val PROBE_BUDGET = 400
    }
}
