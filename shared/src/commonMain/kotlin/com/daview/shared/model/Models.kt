package com.daview.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What kind of content a user-defined library holds. Drives scanner + scraper choice. */
@Serializable
enum class LibraryKind {
    @SerialName("movie") MOVIE,
    @SerialName("series") SERIES,
    @SerialName("anime") ANIME,
    @SerialName("other") OTHER;

    val isSeriesLike: Boolean get() = this == SERIES || this == ANIME
}

@Serializable
enum class ItemKind {
    @SerialName("movie") MOVIE,
    @SerialName("series") SERIES,
    @SerialName("season") SEASON,
    @SerialName("episode") EPISODE
}

@Serializable
enum class StreamType {
    @SerialName("video") VIDEO,
    @SerialName("audio") AUDIO,
    @SerialName("subtitle") SUBTITLE
}

/** Metadata source. [NONE] means "do not scrape, use folder names only". */
@Serializable
enum class MetadataProvider {
    @SerialName("tmdb") TMDB,
    @SerialName("tvdb") TVDB,
    @SerialName("bangumi") BANGUMI,
    @SerialName("none") NONE;

    val displayName: String
        get() = when (this) {
            TMDB -> "TMDB"
            TVDB -> "TheTVDB"
            BANGUMI -> "bangumi.tv"
            NONE -> "不刮削"
        }

    /** Where the numeric id sits in that site's own URLs, shown next to the input. */
    val idHint: String
        get() = when (this) {
            TMDB -> "themoviedb.org/movie/<id> 或 /tv/<id>"
            TVDB -> "thetvdb.com 条目的数字 id"
            BANGUMI -> "bgm.tv/subject/<id>"
            NONE -> ""
        }
}

@Serializable
data class LibraryDto(
    val id: String,
    val name: String,
    val kind: LibraryKind,
    /** WebDAV path relative to the configured root, e.g. `/Ani`. */
    val path: String,
    val providerOrder: List<MetadataProvider> = emptyList(),
    val language: String = "zh-CN",
    val itemCount: Int = 0,
    val lastScanAt: Long? = null,
    val imageUrl: String? = null
)

@Serializable
data class PersonDto(
    val name: String,
    val role: String? = null,
    val type: String = "Actor",
    val imageUrl: String? = null
)

@Serializable
data class MediaStreamDto(
    val index: Int,
    val type: StreamType,
    val codec: String? = null,
    val language: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isExternal: Boolean = false,
    /** WebDAV path for external subtitle files. */
    val externalPath: String? = null,
    val channels: Int? = null,
    val width: Int? = null,
    val height: Int? = null
) {
    val displayTitle: String
        get() = buildString {
            append(languageDisplayName(language))
            title?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            codec?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it.uppercase()) }
            if (isExternal) append(" · 外挂")
            if (isForced) append(" · 强制")
        }
}

@Serializable
data class UserDataDto(
    val positionMs: Long = 0,
    val playedPercentage: Double = 0.0,
    val played: Boolean = false,
    val playCount: Int = 0,
    val favorite: Boolean = false,
    val lastPlayedAt: Long? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null
)

@Serializable
data class MediaItemDto(
    val id: String,
    val libraryId: String,
    val kind: ItemKind,
    val parentId: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val name: String,
    val originalName: String? = null,
    val sortName: String = "",
    val overview: String? = null,
    val year: Int? = null,
    val premiereDate: String? = null,
    val runtimeMs: Long? = null,
    val communityRating: Double? = null,
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val people: List<PersonDto> = emptyList(),
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val providerIds: Map<String, String> = emptyMap(),
    /**
     * Set when the user pinned an id by hand. Scraping then reuses that id and
     * never searches again, so a rescan cannot undo the correction.
     */
    val lockedProvider: MetadataProvider? = null,
    val childCount: Int? = null,
    /** Episodes under this item: within a season, across a whole series. */
    val episodeCount: Int? = null,
    /** How many of those are marked watched. */
    val playedEpisodeCount: Int? = null,
    val path: String? = null,
    val sizeBytes: Long? = null,
    val mediaStreams: List<MediaStreamDto> = emptyList(),
    val userData: UserDataDto = UserDataDto()
) {
    val isPlayable: Boolean get() = kind == ItemKind.MOVIE || kind == ItemKind.EPISODE

    /**
     * Watched state for any kind of item. A film or episode carries its own
     * flag; a series or season is read from its episodes, which keeps the two
     * from disagreeing after a scan adds or removes files.
     */
    val playedState: PlayedState
        get() = when {
            isPlayable -> when {
                userData.played -> PlayedState.PLAYED
                userData.positionMs > 0 -> PlayedState.PARTIAL
                else -> PlayedState.NONE
            }
            (episodeCount ?: 0) == 0 -> PlayedState.NONE
            playedEpisodeCount == episodeCount -> PlayedState.PLAYED
            (playedEpisodeCount ?: 0) > 0 -> PlayedState.PARTIAL
            else -> PlayedState.NONE
        }

    /** `S01E03` style label, or null for non-episodes. */
    val episodeLabel: String?
        get() = if (kind != ItemKind.EPISODE) null else buildString {
            parentIndexNumber?.let { append("S").append(it.toString().padStart(2, '0')) }
            indexNumber?.let { append("E").append(it.toString().padStart(2, '0')) }
        }.takeIf { it.isNotEmpty() }
}

enum class PlayedState { NONE, PARTIAL, PLAYED }

@Serializable
data class ItemPage(
    val items: List<MediaItemDto>,
    val total: Int,
    val offset: Int
)

// ---------------------------------------------------------------- manual identify

/** One search hit from a metadata provider, shown as-is for the user to choose. */
@Serializable
data class ScrapeCandidateDto(
    val provider: MetadataProvider,
    val providerId: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null
)

/** What the client needs to open the identify dialog for one item. */
@Serializable
data class IdentifyContextDto(
    val itemId: String,
    val kind: ItemKind,
    /** Title parsed from the folder name, i.e. before any scraper renamed it. */
    val defaultQuery: String,
    val defaultYear: Int? = null,
    /** Providers with usable credentials on this server. */
    val providers: List<MetadataProvider> = emptyList(),
    val providerIds: Map<String, String> = emptyMap(),
    val lockedProvider: MetadataProvider? = null
)

@Serializable
data class IdentifyRequest(
    val provider: MetadataProvider,
    val providerId: String
)

// ---------------------------------------------------------------- playback

@Serializable
enum class PlayerKind {
    @SerialName("internal") INTERNAL,
    @SerialName("potplayer") POTPLAYER,
    @SerialName("vlc") VLC,
    @SerialName("mpv") MPV,
    @SerialName("external") EXTERNAL
}

@Serializable
data class PlaybackStartRequest(
    val itemId: String,
    val player: PlayerKind = PlayerKind.INTERNAL,
    val deviceName: String = "unknown",
    /** Ask the server to stream bytes itself so external players can be tracked. */
    val trackThroughProxy: Boolean = false
)

@Serializable
data class PlaybackInfoDto(
    val sessionId: String,
    val item: MediaItemDto,
    /** Stable URL served by DAView. Safe to hand to an external player. */
    val streamUrl: String,
    /** Short-lived direct CDN URL, when the storage backend exposes one. */
    val directUrl: String? = null,
    val startPositionMs: Long,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    /** Absolute URLs for external subtitle files, keyed by stream index. */
    val subtitleUrls: Map<Int, String> = emptyMap(),
    val container: String? = null,
    val runtimeMs: Long? = null
)

@Serializable
data class PlaybackProgressRequest(
    val sessionId: String,
    val positionMs: Long,
    val paused: Boolean = false,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null
)

@Serializable
data class PlaybackStopRequest(
    val sessionId: String,
    val positionMs: Long
)

@Serializable
data class SessionStateDto(
    val sessionId: String,
    val itemId: String,
    val itemName: String,
    val player: PlayerKind,
    val deviceName: String,
    val positionMs: Long,
    val runtimeMs: Long? = null,
    val paused: Boolean = false,
    val startedAt: Long,
    val updatedAt: Long,
    /** How the position was derived; external players are estimated. */
    val positionSource: String = "client"
)

// ---------------------------------------------------------------- setup / admin

@Serializable
data class WebDavEntryDto(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: String? = null
)

@Serializable
data class StorageSettingsDto(
    val url: String = "",
    val username: String = "",
    /** Write-only. Reads always return an empty string. */
    val password: String = "",
    val passwordSet: Boolean = false
)

@Serializable
data class ScraperSettingsDto(
    val tmdbApiKey: String = "",
    val tmdbApiKeySet: Boolean = false,
    val tvdbApiKey: String = "",
    val tvdbApiKeySet: Boolean = false,
    val bangumiToken: String = "",
    val bangumiTokenSet: Boolean = false,
    val language: String = "zh-CN",
    val tmdbImageBase: String = "https://image.tmdb.org/t/p"
)

@Serializable
data class ServerSettingsDto(
    val storage: StorageSettingsDto = StorageSettingsDto(),
    val scraper: ScraperSettingsDto = ScraperSettingsDto(),
    val serverName: String = "DAView",
    val version: String = "1.0.0"
)

@Serializable
data class ScanProgressDto(
    val libraryId: String,
    val libraryName: String,
    val phase: String,
    val current: Int,
    val total: Int,
    val message: String = "",
    val running: Boolean = true,
    val finishedAt: Long? = null,
    val error: String? = null
)

@Serializable
data class ServerInfoDto(
    val name: String,
    val version: String,
    val storageConfigured: Boolean,
    val libraryCount: Int,
    val itemCount: Int
)

// ---------------------------------------------------------------- backup

const val BACKUP_FORMAT = "daview-backup"
const val BACKUP_VERSION = 1

/**
 * Server configuration as it travels between installations. Secrets are only
 * filled in when the export explicitly asked for them; an empty string means
 * "keep whatever the importing server already has".
 */
@Serializable
data class BackupSettingsDto(
    val serverName: String = "DAView",
    val storage: StorageSettingsDto = StorageSettingsDto(),
    val scraper: ScraperSettingsDto = ScraperSettingsDto(),
    val trackExternalPlayers: Boolean = true,
    val externalSessionIdleTimeoutSec: Int = 300
)

/** One catalogue row plus the bookkeeping the API normally hides. */
@Serializable
data class BackupItemDto(
    val item: MediaItemDto,
    val dateCreated: Long = 0,
    val dateModified: Long = 0,
    val etag: String? = null,
    val scrapedAt: Long? = null,
    val probedAt: Long? = null
)

/** Watch state, keyed by item id. Item ids are derived from library id + path,
 *  so they line up again on the other machine as long as the libraries match. */
@Serializable
data class BackupUserDataDto(
    val itemId: String,
    val data: UserDataDto,
    /** When this row last changed. Sync keeps the newer side; a restore ignores it. */
    val updatedAt: Long = 0
)

@Serializable
data class BackupFileDto(
    val format: String = BACKUP_FORMAT,
    val version: Int = BACKUP_VERSION,
    val createdAt: Long = 0,
    val serverVersion: String = "",
    /** True when credentials are inside; the file is then as sensitive as they are. */
    val containsSecrets: Boolean = false,
    val settings: BackupSettingsDto? = null,
    val libraries: List<LibraryDto> = emptyList(),
    val userData: List<BackupUserDataDto> = emptyList(),
    val items: List<BackupItemDto> = emptyList()
)

@Serializable
data class BackupSummaryDto(
    val settingsApplied: Boolean = false,
    val libraries: Int = 0,
    val items: Int = 0,
    val userData: Int = 0,
    val containsSecrets: Boolean = false,
    val createdAt: Long = 0
)

// ---------------------------------------------------------------- sync

@Serializable
data class SyncSettingsDto(
    val enabled: Boolean = false,
    val remotePath: String = "/daview-sync.json",
    val minIntervalMinutes: Int = 10,
    val lastUploadAt: Long? = null,
    val lastPullAt: Long? = null,
    val lastError: String? = null,
    /** Whether the storage accepts writes. Null until something has tried. */
    val writable: Boolean? = null
)

@Serializable
data class SyncResultDto(
    val ok: Boolean,
    val message: String,
    val at: Long = 0,
    val bytes: Int? = null,
    val libraries: Int? = null,
    val userData: Int? = null,
    /** Set when the storage itself refused the write, as opposed to a network error. */
    val readOnlyStorage: Boolean = false
)

@Serializable
data class ApiError(val error: String, val detail: String? = null)

/** ISO-639 (and a few BCP-47) codes seen in real media libraries. */
fun languageDisplayName(code: String?): String {
    if (code.isNullOrBlank()) return "未知"
    return when (code.lowercase()) {
        "zh", "chi", "zho", "cmn" -> "中文"
        "zh-hans", "zhs", "chs", "zh-cn", "zh-hans-cn" -> "简体中文"
        "zh-hant", "zht", "cht", "zh-tw", "zh-hk", "zh-hant-tw" -> "繁体中文"
        "ja", "jpn", "jp" -> "日语"
        "en", "eng" -> "英语"
        "ko", "kor" -> "韩语"
        "fr", "fra", "fre" -> "法语"
        "de", "deu", "ger" -> "德语"
        "ru", "rus" -> "俄语"
        "es", "spa" -> "西班牙语"
        "it", "ita" -> "意大利语"
        "pt", "por" -> "葡萄牙语"
        "th", "tha" -> "泰语"
        "vi", "vie" -> "越南语"
        "ar", "ara" -> "阿拉伯语"
        "und", "unknown" -> "未知"
        else -> code
    }
}
