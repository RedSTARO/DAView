package com.daview.server.api

import com.daview.server.DAVIEW_VERSION
import com.daview.server.ServerContext
import com.daview.server.config.StorageConfig
import com.daview.server.db.Repository
import com.daview.server.library.Scanner
import com.daview.server.storage.DavEntry
import com.daview.server.storage.WebDavClient
import com.daview.shared.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the app can ask of its library, in one place.
 *
 * This is the seam the HTTP layer used to be. Routes had grown real decisions
 * inside them — which episode "play" lands on, which audio track starts
 * selected, what marking a season watched means — and none of those are about
 * HTTP, so an app holding the core in its own process had no way to reach them
 * except by talking to itself over a socket.
 *
 * `:server` is now an adapter over this, and so is the desktop and Android
 * client. One implementation, two front ends; anything that lives in only one
 * of them is a transport concern and belongs there, not here.
 */
class MediaFacade(private val context: ServerContext) {

    /** What went wrong, in terms both a route and a UI can act on. */
    enum class Failure { NOT_FOUND, INVALID, UPSTREAM }

    class FacadeException(
        val failure: Failure,
        override val message: String,
        val detail: String? = null
    ) : Exception(message)

    private fun notFound(message: String, detail: String? = null): Nothing =
        throw FacadeException(Failure.NOT_FOUND, message, detail)

    private fun invalid(message: String, detail: String? = null): Nothing =
        throw FacadeException(Failure.INVALID, message, detail)

    /**
     * Runs [block] off whatever thread called in.
     *
     * Every public method below goes through this. The catalogue lives in
     * SQLite on the caller's own disk, and the caller is a Compose UI: a read
     * that takes 200 ms is 200 ms the window does not repaint. Putting the
     * boundary here rather than at each call site means a screen cannot forget
     * it, and it keeps the state assignments that follow a call on the thread
     * the caller was already on.
     *
     * Nesting is deliberate — an inner `io { }` inside a wrapped body is a
     * `withContext` to the dispatcher it is already on, which costs nothing.
     */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    // ------------------------------------------------------------ server info

    suspend fun info(): ServerInfoDto = io {
        ServerInfoDto(
            name = context.config.serverName,
            version = DAVIEW_VERSION,
            storageConfigured = context.config.storage.configured,
            libraryCount = context.repository.libraries().size,
            itemCount = context.repository.totalItemCount()
        )
    }

    suspend fun settings(): ServerSettingsDto = io { context.config.toSettingsDto() }

    suspend fun updateSettings(incoming: ServerSettingsDto): ServerSettingsDto = io {
        val updated = context.updateConfig { current ->
            current.copy(
                serverName = incoming.serverName.ifBlank { current.serverName },
                storage = current.storage.copy(
                    url = incoming.storage.url.ifBlank { current.storage.url },
                    username = incoming.storage.username.ifBlank { current.storage.username },
                    // An empty password means "keep the stored one".
                    password = incoming.storage.password.ifBlank { current.storage.password }
                ),
                scraper = current.scraper.copy(
                    tmdbApiKey = incoming.scraper.tmdbApiKey.ifBlank { current.scraper.tmdbApiKey },
                    tvdbApiKey = incoming.scraper.tvdbApiKey.ifBlank { current.scraper.tvdbApiKey },
                    bangumiToken = incoming.scraper.bangumiToken.ifBlank { current.scraper.bangumiToken },
                    language = incoming.scraper.language.ifBlank { current.scraper.language }
                )
            )
        }
        updated.toSettingsDto()
    }

    // ------------------------------------------------------------ storage

    suspend fun testStorage(incoming: StorageSettingsDto): List<WebDavEntryDto> = io {
        val effective = StorageConfig(
            url = incoming.url.ifBlank { context.config.storage.url },
            username = incoming.username.ifBlank { context.config.storage.username },
            password = incoming.password.ifBlank { context.config.storage.password }
        )
        if (!effective.configured) invalid("缺少 WebDAV 地址")
        WebDavClient(effective).probe().map { it.toDto() }
    }

    suspend fun browseStorage(path: String): List<WebDavEntryDto> = io {
        val dav = context.webdav() ?: invalid("WebDAV 未配置")
        dav.list(path).map { it.toDto() }
    }

    // ------------------------------------------------------------ libraries

    suspend fun libraries(): List<LibraryDto> = io { context.repository.libraries() }

    suspend fun createLibrary(incoming: LibraryDto): LibraryDto = io {
        // Adding it back by hand is the clearest possible statement that the
        // earlier delete no longer stands.
        context.repository.forgetDeletedLibrary(
            incoming.id.ifBlank { Scanner.libraryId(incoming.path) }
        )
        val library = incoming.copy(
            // Derived from the path, not random: another device pointed at the
            // same folder has to reach the same id by itself, or the item ids
            // built on top of it — and every watch record keyed by them — will
            // not line up across the sync file.
            id = incoming.id.ifBlank { Scanner.libraryId(incoming.path) },
            name = incoming.name.ifBlank { incoming.path.trim('/').substringAfterLast('/') },
            providerOrder = incoming.providerOrder.ifEmpty { context.metadata.defaultOrder(incoming.kind) }
        )
        context.repository.upsertLibrary(library)
        context.repository.library(library.id) ?: library
    }

    suspend fun updateLibrary(id: String, incoming: LibraryDto): LibraryDto = io {
        context.repository.library(id) ?: notFound("媒体库不存在")
        context.repository.upsertLibrary(incoming.copy(id = id))
        context.repository.library(id)!!
    }

    suspend fun deleteLibrary(id: String) = io { context.repository.deleteLibrary(id) }

    suspend fun scan(libraryId: String, mode: ScanMode): ScanProgressDto = io {
        val library = context.repository.library(libraryId) ?: notFound("媒体库不存在")
        context.scans.submit(library, mode)
    }

    /**
     * Scrapes one item again.
     *
     * A fallback match writes scraped_at, and the "只刮削未刮削的" pass keys off
     * exactly that — so an entry that landed on the wrong title could only be
     * revisited by re-scraping the whole library, which is minutes to hours of
     * round trips for one wrong poster.
     */
    suspend fun refreshItem(id: String, links: AssetLinks): MediaItemDto = io {
        val item = context.repository.item(id) ?: notFound("条目不存在")
        context.metadata.enrichItem(item, context.providerOrderFor(item), context.scraperConfigFor(item))
        (context.repository.item(id) ?: item).withAssetUrls(links)
    }

    suspend fun scanStatus(): List<ScanProgressDto> = io { context.scans.status() }

    suspend fun cancelScan(libraryId: String) = io { context.scans.cancel(libraryId) }

    // ------------------------------------------------------------ items

    suspend fun items(
        links: AssetLinks,
        libraryId: String? = null,
        parentId: String? = null,
        topLevelOnly: Boolean = false,
        kind: ItemKind? = null,
        search: String? = null,
        favorite: Boolean? = null,
        played: Boolean? = null,
        sort: String = "sortName",
        descending: Boolean = false,
        limit: Int = 100,
        offset: Int = 0
    ): ItemPage = io {
        val (items, total) = context.repository.query(
            Repository.Query(
                libraryId = libraryId,
                parentId = parentId,
                topLevelOnly = topLevelOnly,
                kind = kind,
                search = search,
                favorite = favorite,
                played = played,
                sort = sort,
                descending = descending,
                limit = limit.coerceIn(1, 500),
                offset = offset.coerceAtLeast(0)
            )
        )
        ItemPage(items.map { it.withAssetUrls(links) }, total, offset)
    }

    /**
     * Probes the container on first open. Track lists are what the detail page
     * and the player both need, and reading them costs a couple of range
     * requests, so it happens once per item rather than during the scan.
     */
    suspend fun item(id: String, links: AssetLinks): MediaItemDto = io {
        val item = context.repository.item(id) ?: notFound("条目不存在")
        val enriched = if (item.isPlayable && item.mediaStreams.none { !it.isExternal }) {
            io { runCatching { context.streams.probeItem(item) }.getOrDefault(item) }
        } else item
        enriched.withAssetUrls(links)
    }

    suspend fun children(id: String, links: AssetLinks): List<MediaItemDto> =
        io { context.repository.children(id).map { it.withAssetUrls(links) } }

    /** Takes an item off the continue-watching shelf, keeping its position. */
    suspend fun setHiddenFromResume(id: String, hidden: Boolean) = io {
        context.repository.setHiddenFromResume(id, hidden)
    }

    suspend fun setFavorite(id: String, value: Boolean): UserDataDto =
        io { context.repository.setFavorite(id, value) }

    /**
     * A series or a season has no bytes of its own, so marking one watched
     * means marking the episodes under it; its own row would just be a second
     * answer to the same question, free to drift from the episodes.
     */
    suspend fun setPlayed(id: String, value: Boolean): UserDataDto = io {
        val episodes = context.repository.episodeIdsUnder(id)
        if (episodes.isEmpty()) return@io context.repository.setPlayed(id, value)
        episodes.forEach { context.repository.setPlayed(it, value) }
        context.repository.userData(id)
    }

    // ------------------------------------------------------------ home rows

    suspend fun resume(limit: Int, links: AssetLinks) =
        io { context.repository.resume(limit).map { it.withAssetUrls(links) } }

    suspend fun nextUp(limit: Int, links: AssetLinks) =
        io { context.repository.nextUp(limit).map { it.withAssetUrls(links) } }

    suspend fun latest(libraryId: String?, limit: Int, links: AssetLinks) =
        io { context.repository.latest(libraryId, limit).map { it.withAssetUrls(links) } }

    suspend fun unwatched(libraryId: String?, limit: Int, links: AssetLinks) =
        io { context.repository.unwatched(libraryId, limit).map { it.withAssetUrls(links) } }

    // ------------------------------------------------------------ merging duplicates

    suspend fun mergedSources(id: String, links: AssetLinks) =
        io { context.repository.mergedSources(id).map { it.withAssetUrls(links) } }

    suspend fun merge(targetId: String, sourceIds: List<String>, links: AssetLinks): MediaItemDto = io {
        val target = context.repository.item(targetId) ?: notFound("条目不存在")
        val sources = sourceIds.filter { it != target.id }.mapNotNull { context.repository.item(it) }
        if (sources.isEmpty()) invalid("没有可合并的条目")
        sources.firstOrNull { it.kind != target.kind }?.let {
            invalid("只能合并同类条目", "${it.name} 是 ${it.kind}，目标是 ${target.kind}")
        }
        context.repository.mergeItems(target.id, sources.map { it.id })
        context.repository.item(target.id)!!.withAssetUrls(links)
    }

    suspend fun unmerge(id: String, links: AssetLinks): MediaItemDto = io {
        val source = context.repository.item(id)
        if (source?.mergedInto == null) invalid("这个条目没有被合并")
        context.repository.unmergeItem(id)
        context.repository.item(id)!!.withAssetUrls(links)
    }

    // ------------------------------------------------------------ manual identify

    /** Only whole films and series carry scraped metadata of their own. */
    private fun identifiable(id: String): MediaItemDto {
        val item = context.repository.item(id) ?: notFound("条目不存在")
        if (item.kind != ItemKind.MOVIE && item.kind != ItemKind.SERIES) {
            invalid("只有电影和剧集可以手动指定")
        }
        return item
    }

    suspend fun identifyContext(id: String): IdentifyContextDto = io {
        val item = identifiable(id)
        val parsed = context.metadata.folderTitle(item)
        IdentifyContextDto(
            itemId = item.id,
            kind = item.kind,
            defaultQuery = parsed.title,
            defaultYear = parsed.year ?: item.year,
            providers = context.metadata.availableProviders(context.config.scraper),
            providerIds = item.providerIds,
            lockedProvider = item.lockedProvider
        )
    }

    suspend fun identifySearch(
        id: String,
        provider: MetadataProvider?,
        query: String?,
        year: Int?
    ): List<ScrapeCandidateDto> = io {
        val item = identifiable(id)
        if (provider == null || provider == MetadataProvider.NONE) invalid("未知的刮削源")
        val effectiveQuery = query?.takeIf { it.isNotBlank() } ?: context.metadata.folderTitle(item).title
        val kind = if (item.kind == ItemKind.MOVIE) ItemKind.MOVIE else ItemKind.SERIES
        context.metadata.searchProvider(provider, effectiveQuery, year, kind, context.scraperConfigFor(item)).map {
            ScrapeCandidateDto(
                provider = provider,
                providerId = it.providerId,
                title = it.title,
                originalTitle = it.originalTitle,
                year = it.year,
                overview = it.overview?.take(400),
                posterUrl = it.posterUrl
            )
        }
    }

    suspend fun identify(id: String, request: IdentifyRequest, links: AssetLinks): MediaItemDto = io {
        val item = identifiable(id)
        if (request.provider == MetadataProvider.NONE || request.providerId.isBlank()) {
            invalid("需要刮削源与条目 id")
        }
        val updated = context.metadata.identify(
            item = item,
            provider = request.provider,
            providerId = request.providerId,
            order = context.providerOrderFor(item),
            config = context.scraperConfigFor(item)
        ) ?: throw FacadeException(
            Failure.UPSTREAM,
            "刮削失败",
            "${request.provider.displayName} 上没有 id ${request.providerId.trim()}，或该源未配置密钥"
        )
        updated.withAssetUrls(links)
    }

    // ------------------------------------------------------------ playback

    /**
     * Turns "play this" into a session plus an address the player can fetch.
     *
     * Pressing play on a series or a season has to land on an episode: their
     * own path is a directory, and asking the storage to stream a directory is
     * how this used to fail — a 502 with nothing in the player to say why.
     */
    suspend fun startPlayback(request: PlaybackStartRequest, links: AssetLinks): PlaybackInfoDto = io {
        val requested = context.repository.item(request.itemId)
        val stored = when {
            requested == null -> null
            requested.isPlayable -> requested
            else -> context.repository.nextEpisodeUnder(requested.id)
        }
        val storedPath = stored?.path
        if (stored == null || storedPath == null) {
            notFound("没有可播放的内容", requested?.let { "${it.name} 下没有分集" })
        }

        val item = runCatching { context.streams.probeItem(stored) }.getOrDefault(stored)
        val mediaPath = item.path ?: storedPath
        val userData = item.userData
        val audio = userData.audioStreamIndex ?: item.mediaStreams
            .firstOrNull { it.type == StreamType.AUDIO && it.isDefault }?.index
            ?: item.mediaStreams.firstOrNull { it.type == StreamType.AUDIO }?.index
        val subtitle = userData.subtitleStreamIndex ?: pickDefaultSubtitle(item)

        val session = context.playback.start(
            item = item,
            player = request.player,
            deviceName = request.deviceName,
            startPositionMs = request.startPositionMs ?: userData.positionMs,
            audioStreamIndex = audio,
            subtitleStreamIndex = subtitle
        )

        // The storage link is handed over for anything that can use it, but the
        // in-app player is not one of them: ExoPlayer following the storage's
        // 302 gets a 502 from the CDN, while the very same link fetched from
        // here returns 206 a second later. So it reads through the pipe, which
        // can also re-resolve the link when it expires mid-film.
        val direct = if (request.player == PlayerKind.INTERNAL) {
            context.streams.directUrl(mediaPath)
        } else null
        val proxy = request.trackThroughProxy && context.config.trackExternalPlayers

        // Only episodes have a successor; a film has nothing to follow it.
        val next = if (item.kind == ItemKind.EPISODE) {
            context.repository.episodeAfter(item.id)
        } else null

        PlaybackInfoDto(
            sessionId = session.id,
            item = item.withAssetUrls(links),
            streamUrl = links.stream(item.id, mediaPath.substringAfterLast('/'), session.id, proxy),
            directUrl = direct,
            startPositionMs = userData.positionMs,
            audioStreamIndex = audio,
            subtitleStreamIndex = subtitle,
            // A subtitle is a few kilobytes the player fetches once, and the
            // storage hands out links for it as readily as for the video, so it
            // goes straight there. Falling back only when it will not.
            subtitleUrls = item.mediaStreams
                .filter { it.isExternal && it.externalPath != null }
                .associate { stream ->
                    val subtitleDirect = context.streams.directUrl(stream.externalPath!!)
                    stream.index to (subtitleDirect ?: links.subtitle(item.id, stream.index, session.id))
                },
            container = mediaPath.substringAfterLast('.'),
            runtimeMs = item.runtimeMs,
            // Carried with the session so a player that reaches the end can go
            // straight on. Only for episodes: a film has nothing to follow it.
            nextItemId = next?.id,
            nextItemName = next?.let { it.episodeLabel?.plus(" ")?.plus(it.name) ?: it.name }
        )
    }

    suspend fun reportProgress(request: PlaybackProgressRequest): Boolean = io {
        context.playback.report(
            request.sessionId, request.positionMs, request.paused,
            request.audioStreamIndex, request.subtitleStreamIndex
        ) != null
    }

    suspend fun stopPlayback(request: PlaybackStopRequest): Unit = io {
        context.playback.stop(request.sessionId, request.positionMs.takeIf { it >= 0 })
        // Stopping already tells the pipe, and so does an idle timeout. This is
        // here for the session the tracker no longer holds — it has been retired
        // under us — and is harmless for one that never used a pipe.
        context.pipe.release(request.sessionId)
    }

    /**
     * Pushes the watch state up as soon as something stops playing.
     *
     * The upload was otherwise left entirely to a timer inside this process:
     * every sixty seconds, and only if ten minutes had passed since the last
     * one. Swiping the app away after an episode — which is what phones invite
     * you to do — meant the other device saw a position up to ten minutes
     * stale, on the one feature the whole design exists for.
     */
    suspend fun syncAfterPlayback() = io {
        if (context.config.sync.enabled) runCatching { context.sync.upload(automatic = true) }
        Unit
    }

    suspend fun sessions(): List<SessionStateDto> = io { context.playback.activeSessions() }

    /** Keeps a session from being retired while its player is merely paused. */
    suspend fun keepSessionAlive(sessionId: String) = io { context.playback.keepAlive(sessionId) }

    /** Whether anything is playing, so a window knows not to close silently. */
    suspend fun hasActiveSessions(): Boolean = io { context.playback.activeSessions().isNotEmpty() }

    // ------------------------------------------------------------ sync

    suspend fun syncSettings(result: SyncResultDto? = null) = io {
        SyncSettingsDto(
            enabled = context.config.sync.enabled,
            remotePath = context.config.sync.remotePath,
            minIntervalMinutes = context.config.sync.minIntervalMinutes,
            lastUploadAt = context.config.sync.lastUploadAt,
            lastPullAt = context.config.sync.lastPullAt,
            lastError = result?.takeIf { !it.ok }?.message ?: context.config.sync.lastError,
            writable = context.sync.storageWritable
        )
    }

    /**
     * Turning it on has to prove the storage takes writes, so it runs a real
     * upload; turning it off is unconditional.
     */
    suspend fun updateSyncSettings(incoming: SyncSettingsDto): SyncSettingsDto = io {
        context.updateConfig { current ->
            current.copy(
                sync = current.sync.copy(
                    remotePath = incoming.remotePath.ifBlank { current.sync.remotePath },
                    minIntervalMinutes = incoming.minIntervalMinutes.coerceIn(1, 24 * 60)
                )
            )
        }
        val result = when {
            incoming.enabled && !context.config.sync.enabled -> context.sync.enable()
            !incoming.enabled -> {
                context.sync.disable()
                null
            }

            else -> null
        }
        syncSettings(result)
    }

    suspend fun syncUpload(): SyncResultDto = io { context.sync.upload() }

    suspend fun syncPull(): SyncResultDto = io { context.sync.pull() }

    // ------------------------------------------------------------ backup

    /**
     * The one method that does not move itself off the caller's thread: the
     * sequence is lazy, so the reads happen wherever it is consumed. Consume it
     * on [Dispatchers.IO].
     */
    fun backupChunks(options: BackupOptions): Sequence<String> = backupChunks(context, options)

    suspend fun importBackup(backup: BackupFileDto): BackupSummaryDto =
        io { runCatching { applyBackup(context, backup) }.getOrElse { invalid("导入失败", it.message) } }

    /** Where the artwork for an item is cached, downloading it the first time. */
    suspend fun imageFile(itemId: String, type: String): java.nio.file.Path? = io {
        val item = context.repository.item(itemId) ?: return@io null
        val remote = when (type) {
            "primary" -> item.posterUrl
            "backdrop" -> item.backdropUrl
            "logo" -> item.logoUrl
            else -> null
        } ?: return@io null
        context.images.get(remote)?.file
    }

    /** How much disk the artwork cache is holding. */
    suspend fun imageCacheBytes(): Long = io { context.images.sizeBytes() }

    /**
     * Empties the artwork cache. Nothing is lost — every image is re-fetched on
     * demand — and until now the directory only ever grew, with no way to see
     * its size or clear it; on Android it sits under `filesDir`, out of reach
     * of the system's own "clear cache".
     */
    suspend fun clearImageCache() = io { context.images.clear() }

    // ------------------------------------------------------------ artwork

    /** Rewrites the provider's artwork URL to an address the caller can fetch. */
    private fun MediaItemDto.withAssetUrls(links: AssetLinks): MediaItemDto = copy(
        posterUrl = posterUrl?.let { links.image(id, "primary", it) },
        backdropUrl = backdropUrl?.let { links.image(id, "backdrop", it) },
        logoUrl = logoUrl?.let { links.image(id, "logo", it) }
    )
}

/** Which subtitle starts on when the user has not chosen one for this item. */
internal fun pickDefaultSubtitle(item: MediaItemDto): Int? {
    val subtitles = item.mediaStreams.filter { it.type == StreamType.SUBTITLE }
    if (subtitles.isEmpty()) return null
    return subtitles.firstOrNull { it.isDefault }?.index
        ?: subtitles.firstOrNull { it.language?.startsWith("zh") == true }?.index
        ?: subtitles.first().index
}

/** Scraper credentials, with the language of the library the item belongs to. */
internal fun ServerContext.scraperConfigFor(item: MediaItemDto) =
    config.scraper.copy(
        language = repository.library(item.libraryId)?.language?.ifBlank { null }
            ?: config.scraper.language
    )

internal fun ServerContext.providerOrderFor(item: MediaItemDto): List<MetadataProvider> {
    val library = repository.library(item.libraryId) ?: return emptyList()
    return library.providerOrder.ifEmpty { metadata.defaultOrder(library.kind) }
}

internal fun DavEntry.toDto() = WebDavEntryDto(
    name = name,
    path = path,
    isDirectory = isDirectory,
    sizeBytes = size,
    lastModified = lastModified
)

internal fun com.daview.server.config.AppConfig.toSettingsDto() = ServerSettingsDto(
    storage = StorageSettingsDto(
        url = storage.url,
        username = storage.username,
        password = "",
        passwordSet = storage.password.isNotBlank()
    ),
    scraper = ScraperSettingsDto(
        tmdbApiKey = "",
        tmdbApiKeySet = scraper.tmdbApiKey.isNotBlank(),
        tvdbApiKey = "",
        tvdbApiKeySet = scraper.tvdbApiKey.isNotBlank(),
        bangumiToken = "",
        bangumiTokenSet = scraper.bangumiToken.isNotBlank(),
        language = scraper.language,
        tmdbImageBase = scraper.tmdbImageBase
    ),
    serverName = serverName,
    version = DAVIEW_VERSION
)
