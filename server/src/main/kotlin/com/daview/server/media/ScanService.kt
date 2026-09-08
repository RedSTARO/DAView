package com.daview.server.media

import com.daview.server.config.AppConfig
import com.daview.server.db.Repository
import com.daview.server.library.Scanner
import com.daview.server.scraper.MetadataService
import com.daview.server.storage.WebDavClient
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.ScanProgressDto
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Runs library scans one at a time and exposes their progress to the API. */
class ScanService(
    private val repository: Repository,
    private val metadata: MetadataService,
    private val streams: StreamService,
    private val davProvider: () -> WebDavClient?,
    private val configProvider: () -> AppConfig
) {
    private val log = LoggerFactory.getLogger(ScanService::class.java)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "daview-scan").apply { isDaemon = true }
    }
    private val progress = ConcurrentHashMap<String, ScanProgressDto>()

    fun status(): List<ScanProgressDto> = progress.values.sortedBy { it.libraryName }

    fun isRunning(libraryId: String): Boolean = progress[libraryId]?.running == true

    fun submit(library: LibraryDto, refreshMetadata: Boolean): ScanProgressDto {
        if (isRunning(library.id)) return progress.getValue(library.id)
        val initial = ScanProgressDto(
            libraryId = library.id,
            libraryName = library.name,
            phase = "queued",
            current = 0,
            total = 0,
            message = "排队中"
        )
        progress[library.id] = initial
        executor.submit { runScan(library, refreshMetadata) }
        return initial
    }

    private fun runScan(library: LibraryDto, refreshMetadata: Boolean) {
        val dav = davProvider()
        if (dav == null) {
            progress[library.id] = progress.getValue(library.id).copy(
                running = false, phase = "error", error = "WebDAV 未配置", finishedAt = System.currentTimeMillis()
            )
            return
        }
        try {
            val scanner = Scanner(dav, repository)
            val result = scanner.scan(library) { phase, current, total, message ->
                progress[library.id] = progress.getValue(library.id).copy(
                    phase = phase, current = current, total = total, message = message, running = true
                )
            }
            log.info("库 {} 扫描完成: {} 项，移除 {} 项", library.name, result.itemCount, result.removed)

            val config = configProvider()
            metadata.enrichLibrary(library, config.scraper.copy(language = library.language), refreshMetadata) { current, total, message ->
                progress[library.id] = progress.getValue(library.id).copy(
                    phase = "scraping", current = current, total = total, message = message, running = true
                )
            }

            streams.probeMissing(library.id, PROBE_BUDGET) { current, total, message ->
                progress[library.id] = progress.getValue(library.id).copy(
                    phase = "probing", current = current, total = total, message = message, running = true
                )
            }

            progress[library.id] = progress.getValue(library.id).copy(
                phase = "done",
                running = false,
                message = if (result.warnings.isEmpty()) "完成" else "完成（${result.warnings.size} 个警告）",
                finishedAt = System.currentTimeMillis()
            )
        } catch (t: Throwable) {
            log.error("扫描 {} 失败", library.name, t)
            progress[library.id] = progress.getValue(library.id).copy(
                phase = "error", running = false, error = t.message ?: t.toString(),
                finishedAt = System.currentTimeMillis()
            )
        }
    }

    private companion object {
        /** Container probing costs one HTTP round trip per file; cap it per scan. */
        const val PROBE_BUDGET = 400
    }
}
