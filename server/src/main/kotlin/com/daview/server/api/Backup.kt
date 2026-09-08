package com.daview.server.api

import com.daview.server.DAVIEW_VERSION
import com.daview.server.ServerContext
import com.daview.server.db.ItemRecord
import com.daview.shared.model.BACKUP_FORMAT
import com.daview.shared.model.BACKUP_VERSION
import com.daview.shared.model.BackupFileDto
import com.daview.shared.model.BackupItemDto
import com.daview.shared.model.BackupSettingsDto
import com.daview.shared.model.BackupSummaryDto
import com.daview.shared.model.BackupUserDataDto
import com.daview.shared.model.LibraryDto
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
                    context.repository.allUserData().map { BackupUserDataDto(it.first, it.second) }
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
 * Restores a backup on top of whatever is already here. Sections missing from
 * the file are left alone, and a blank secret means "keep the local one", so a
 * secret-free export can be imported without wiping the target's credentials.
 */
fun applyBackup(context: ServerContext, backup: BackupFileDto): BackupSummaryDto {
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
    backup.items.chunked(ITEM_PAGE).forEach { chunk ->
        context.repository.upsertItems(chunk.map { it.toRecord() })
    }
    backup.userData.forEach { context.repository.restoreUserData(it.itemId, it.data) }

    return BackupSummaryDto(
        settingsApplied = settings != null,
        libraries = backup.libraries.size,
        items = backup.items.size,
        userData = backup.userData.size,
        containsSecrets = backup.containsSecrets,
        createdAt = backup.createdAt
    )
}

private fun ServerContext.backupSettings(secrets: Boolean) = BackupSettingsDto(
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

/** The item as stored, minus the watch state — that travels in its own section. */
private fun ItemRecord.toBackup() = BackupItemDto(
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
