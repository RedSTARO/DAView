package com.daview.server.api

import com.daview.server.DAVIEW_VERSION
import com.daview.server.ServerContext
import com.daview.server.db.ItemRecord
import com.daview.shared.model.BACKUP_FORMAT
import com.daview.shared.model.BACKUP_VERSION
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.BackupItemDto
import com.daview.shared.model.BackupPinDto
import com.daview.shared.model.BackupSettingsDto
import com.daview.shared.model.BackupSummaryDto
import com.daview.shared.model.BackupUserDataDto
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.ScraperSettingsDto
import com.daview.shared.model.StorageSettingsDto
import com.daview.shared.model.UserDataDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which sections a backup carries. */
data class BackupOptions(
    val settings: Boolean = true,
    val libraries: Boolean = true,
    val items: Boolean = true,
    val userData: Boolean = true,
    /** Entries the user pinned by hand. Tiny, and not reproducible by scraping. */
    val pins: Boolean = true,
    /** Include the WebDAV password and the scraper API keys. Off by default. */
    val secrets: Boolean = false
)

private val backupJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/** Rows read per round trip while streaming; keeps peak memory flat. */
private const val ITEM_PAGE = 400

private val stampFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())

/**
 * Writes the backup straight to the socket instead of building it in memory.
 * A full catalogue is a few thousand items carrying overviews, cast lists and
 * track lists; the server is expected to run on a small heap, so the item array
 * is emitted a page at a time.
 */
suspend fun ApplicationCall.respondBackup(context: ServerContext, options: BackupOptions) {
    response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"daview-backup-${stampFormat.format(Instant.now())}.json\""
    )
    respondBytesWriter(contentType = ContentType.Application.Json) {
        suspend fun emit(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            writeFully(bytes, 0, bytes.size)
        }

        emit("{\"format\":\"$BACKUP_FORMAT\",\"version\":$BACKUP_VERSION")
        emit(",\"createdAt\":${System.currentTimeMillis()}")
        emit(",\"serverVersion\":\"$DAVIEW_VERSION\"")
        emit(",\"containsSecrets\":${options.secrets}")

        if (options.settings) {
            emit(",\"settings\":")
            emit(backupJson.encodeToString(BackupSettingsDto.serializer(), context.backupSettings(options.secrets)))
        }
        if (options.libraries) {
            emit(",\"libraries\":")
            emit(
                backupJson.encodeToString(
                    ListSerializer(LibraryDto.serializer()),
                    context.repository.libraries()
                )
            )
        }
        if (options.userData) {
            emit(",\"userData\":")
            emit(
                backupJson.encodeToString(
                    ListSerializer(BackupUserDataDto.serializer()),
                    context.repository.allUserData()
                        .map { BackupUserDataDto(it.itemId, it.data, it.updatedAt) }
                )
            )
        }
        if (options.pins) {
            emit(",\"pins\":")
            emit(
                backupJson.encodeToString(
                    ListSerializer(BackupPinDto.serializer()),
                    context.repository.allPins().mapNotNull { it.toBackup() }
                )
            )
        }
        if (options.items) {
            emit(",\"items\":[")
            var offset = 0
            var first = true
            while (true) {
                val page = context.repository.itemRecordsPage(ITEM_PAGE, offset)
                if (page.isEmpty()) break
                for (record in page) {
                    if (!first) emit(",")
                    first = false
                    emit(backupJson.encodeToString(BackupItemDto.serializer(), record.toBackup()))
                }
                offset += page.size
                if (page.size < ITEM_PAGE) break
            }
            emit("]")
        }
        emit("}")
        flush()
    }
}

/**
 * Builds a backup in memory, for callers that need the bytes rather than a
 * response — sync, which uploads them to the share.
 *
 * Fine for the sync payload, which leaves the catalogue out and comes to a
 * couple of kilobytes. Passing `items = true` here materialises every row, so
 * the HTTP export streams instead.
 */
fun buildBackup(context: ServerContext, options: BackupOptions): String {
    val file = BackupFileDto(
        createdAt = System.currentTimeMillis(),
        serverVersion = DAVIEW_VERSION,
        containsSecrets = options.secrets,
        settings = if (options.settings) context.backupSettings(options.secrets) else null,
        libraries = if (options.libraries) context.repository.libraries() else emptyList(),
        userData = if (options.userData) {
            context.repository.allUserData().map { BackupUserDataDto(it.itemId, it.data, it.updatedAt) }
        } else emptyList(),
        pins = if (options.pins) context.repository.allPins().mapNotNull { it.toBackup() } else emptyList(),
        items = if (options.items) {
            context.repository.itemRecordsPage(Int.MAX_VALUE, 0).map { it.toBackup() }
        } else emptyList()
    )
    return backupJson.encodeToString(BackupFileDto.serializer(), file)
}

/**
 * Restores a backup on top of whatever is already here. Sections missing from
 * the file are left alone, and a blank secret means "keep the local one", so a
 * secret-free export can be imported without wiping the target's credentials.
 */
fun applyBackup(
    context: ServerContext,
    backup: BackupFileDto,
    /**
     * Sync passes true so the newer side of each row wins. A restore leaves it
     * false: the file the user picked is meant to be authoritative.
     */
    mergeUserDataByTimestamp: Boolean = false
): BackupSummaryDto {
    require(backup.format == BACKUP_FORMAT) { "不是 DAView 备份文件" }
    require(backup.version <= BACKUP_VERSION) {
        "备份文件版本 ${backup.version} 比这个服务端（$BACKUP_VERSION）新"
    }

    val settings = backup.settings
    if (settings != null) {
        context.updateConfig { current ->
            current.copy(
                serverName = settings.serverName.ifBlank { current.serverName },
                storage = current.storage.copy(
                    url = settings.storage.url.ifBlank { current.storage.url },
                    username = settings.storage.username.ifBlank { current.storage.username },
                    password = settings.storage.password.ifBlank { current.storage.password }
                ),
                scraper = current.scraper.copy(
                    tmdbApiKey = settings.scraper.tmdbApiKey.ifBlank { current.scraper.tmdbApiKey },
                    tvdbApiKey = settings.scraper.tvdbApiKey.ifBlank { current.scraper.tvdbApiKey },
                    bangumiToken = settings.scraper.bangumiToken.ifBlank { current.scraper.bangumiToken },
                    language = settings.scraper.language.ifBlank { current.scraper.language },
                    tmdbImageBase = settings.scraper.tmdbImageBase.ifBlank { current.scraper.tmdbImageBase }
                ),
                trackExternalPlayers = settings.trackExternalPlayers,
                externalSessionIdleTimeoutSec = settings.externalSessionIdleTimeoutSec
            )
        }
    }

    backup.libraries.forEach { context.repository.upsertLibrary(it) }

    // Pins land before the items, so an item restored in the same file already
    // finds its correction in place. Newer wins, same as the watch state — the
    // two are both decisions the user made at a point in time.
    var mergedPins = 0
    backup.pins.forEach { row ->
        if (row.providerId.isBlank() || row.provider == MetadataProvider.NONE) return@forEach
        val local = context.repository.pin(row.itemId)
        if (local != null && local.updatedAt >= row.updatedAt) return@forEach
        context.repository.savePin(
            itemId = row.itemId,
            provider = row.provider.name,
            providerId = row.providerId,
            updatedAt = row.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
        mergedPins++
    }

    backup.items.chunked(ITEM_PAGE).forEach { chunk ->
        context.repository.upsertItems(chunk.map { it.toRecord() })
    }
    var mergedUserData = 0
    backup.userData.forEach { row ->
        val local = if (mergeUserDataByTimestamp) context.repository.userDataUpdatedAt(row.itemId) else null
        if (local != null && local >= row.updatedAt) return@forEach
        context.repository.restoreUserData(
            row.itemId,
            row.data,
            updatedAt = row.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
        mergedUserData++
    }

    return BackupSummaryDto(
        settingsApplied = settings != null,
        libraries = backup.libraries.size,
        items = backup.items.size,
        userData = mergedUserData,
        pins = mergedPins,
        containsSecrets = backup.containsSecrets,
        createdAt = backup.createdAt
    )
}

internal fun ServerContext.backupSettings(secrets: Boolean) = BackupSettingsDto(
    serverName = config.serverName,
    storage = StorageSettingsDto(
        url = config.storage.url,
        // The account name is a credential too — on the reference share it is the
        // user's phone number — so it travels only when secrets were asked for.
        username = if (secrets) config.storage.username else "",
        password = if (secrets) config.storage.password else "",
        passwordSet = config.storage.password.isNotBlank()
    ),
    scraper = ScraperSettingsDto(
        tmdbApiKey = if (secrets) config.scraper.tmdbApiKey else "",
        tmdbApiKeySet = config.scraper.tmdbApiKey.isNotBlank(),
        tvdbApiKey = if (secrets) config.scraper.tvdbApiKey else "",
        tvdbApiKeySet = config.scraper.tvdbApiKey.isNotBlank(),
        bangumiToken = if (secrets) config.scraper.bangumiToken else "",
        bangumiTokenSet = config.scraper.bangumiToken.isNotBlank(),
        language = config.scraper.language,
        tmdbImageBase = config.scraper.tmdbImageBase
    ),
    trackExternalPlayers = config.trackExternalPlayers,
    externalSessionIdleTimeoutSec = config.externalSessionIdleTimeoutSec
)

/** Null for a provider this build does not know, which is skipped rather than guessed at. */
internal fun com.daview.server.db.PinRow.toBackup(): BackupPinDto? {
    val known = MetadataProvider.entries.firstOrNull { it.name == provider } ?: return null
    return BackupPinDto(itemId = itemId, provider = known, providerId = providerId, updatedAt = updatedAt)
}

/** The item as stored, minus the watch state — that travels in its own section. */
internal fun ItemRecord.toBackup() = BackupItemDto(
    item = dto.copy(userData = UserDataDto()),
    dateCreated = dateCreated,
    dateModified = dateModified,
    etag = etag,
    scrapedAt = scrapedAt,
    probedAt = probedAt
)

private fun BackupItemDto.toRecord() = ItemRecord(
    dto = item,
    dateCreated = dateCreated,
    dateModified = dateModified,
    etag = etag,
    scrapedAt = scrapedAt,
    probedAt = probedAt
)
