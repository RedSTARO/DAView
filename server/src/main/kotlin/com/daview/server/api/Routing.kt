package com.daview.server.api

import com.daview.server.DAVIEW_VERSION
import com.daview.server.ServerContext
import com.daview.server.config.StorageConfig
import com.daview.server.db.Repository
import com.daview.server.storage.WebDavException
import com.daview.shared.model.*
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val STREAM_BUFFER = 256 * 1024

/** What `source=datadir` looks for inside the data directory. */
const val IMPORT_FILE_NAME = "import.json"

fun Route.apiRoutes(context: ServerContext) {

    // ------------------------------------------------------------ server info

    get("/api/info") {
        call.requireAuth(context) ?: return@get
        call.respond(
            ServerInfoDto(
                name = context.config.serverName,
                version = DAVIEW_VERSION,
                storageConfigured = context.config.storage.configured,
                libraryCount = context.repository.libraries().size,
                itemCount = context.repository.totalItemCount()
            )
        )
    }

    // ------------------------------------------------------------ settings

    get("/api/settings") {
        call.requireAuth(context) ?: return@get
        call.respond(context.config.toDto())
    }

    put("/api/settings") {
        call.requireAuth(context) ?: return@put
        val incoming = call.receive<ServerSettingsDto>()
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
        call.respond(updated.toDto())
    }

    // ------------------------------------------------------------ storage

    post("/api/storage/test") {
        call.requireAuth(context) ?: return@post
        val incoming = call.receive<StorageSettingsDto>()
        val effective = StorageConfig(
            url = incoming.url.ifBlank { context.config.storage.url },
            username = incoming.username.ifBlank { context.config.storage.username },
            password = incoming.password.ifBlank { context.config.storage.password }
        )
        if (!effective.configured) {
            call.respond(HttpStatusCode.BadRequest, ApiError("缺少 WebDAV 地址"))
            return@post
        }
        val entries = withContext(Dispatchers.IO) {
            com.daview.server.storage.WebDavClient(effective).probe()
        }
        call.respond(entries.map { it.toDto() })
    }

    get("/api/storage/browse") {
        call.requireAuth(context) ?: return@get
        val dav = context.webdav()
        if (dav == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("WebDAV 未配置"))
            return@get
        }
        val path = call.request.queryParameters["path"] ?: "/"
        val entries = withContext(Dispatchers.IO) { dav.list(path) }
        call.respond(entries.map { it.toDto() })
    }

    // ------------------------------------------------------------ libraries

    get("/api/libraries") {
        call.requireAuth(context) ?: return@get
        call.respond(context.repository.libraries())
    }

    post("/api/libraries") {
        call.requireAuth(context) ?: return@post
        val incoming = call.receive<LibraryDto>()
        val library = incoming.copy(
            id = incoming.id.ifBlank { UUID.randomUUID().toString().replace("-", "").take(12) },
            name = incoming.name.ifBlank { incoming.path.trim('/').substringAfterLast('/') },
            providerOrder = incoming.providerOrder.ifEmpty { context.metadata.defaultOrder(incoming.kind) }
        )
        context.repository.upsertLibrary(library)
        call.respond(context.repository.library(library.id) ?: library)
    }

    put("/api/libraries/{id}") {
        call.requireAuth(context) ?: return@put
        val id = call.parameters["id"].orEmpty()
        val existing = context.repository.library(id)
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("媒体库不存在"))
            return@put
        }
        val incoming = call.receive<LibraryDto>()
        context.repository.upsertLibrary(incoming.copy(id = id))
        call.respond(context.repository.library(id)!!)
    }

    delete("/api/libraries/{id}") {
        call.requireAuth(context) ?: return@delete
        context.repository.deleteLibrary(call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }

    post("/api/libraries/{id}/scan") {
        call.requireAuth(context) ?: return@post
        val library = context.repository.library(call.parameters["id"].orEmpty())
        if (library == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("媒体库不存在"))
            return@post
        }
        val refresh = call.request.queryParameters["refresh"]?.toBoolean() ?: false
        call.respond(context.scans.submit(library, refresh))
    }

    get("/api/scan/status") {
        call.requireAuth(context) ?: return@get
        call.respond(context.scans.status())
    }

    // ------------------------------------------------------------ backup

    /**
     * Migration in two calls: download the file here, POST it to the new
     * server. Credentials stay out unless `secrets=true` is asked for, because
     * the result is a plain file the user is about to move between machines.
     */
    get("/api/backup/export") {
        call.requireAuth(context) ?: return@get
        val params = call.request.queryParameters
        fun flag(name: String, default: Boolean) = params[name]?.toBooleanStrictOrNull() ?: default
        call.respondBackup(
            context,
            BackupOptions(
                settings = flag("settings", true),
                libraries = flag("libraries", true),
                items = flag("items", true),
                userData = flag("userdata", true),
                secrets = flag("secrets", false)
            )
        )
    }

    post("/api/backup/import") {
        call.requireAuth(context) ?: return@post
        // `source=datadir` reads <data>/import.json, so the clients need no file
        // picker: on the new machine the file is copied in next to the database.
        val backup = if (call.request.queryParameters["source"] == "datadir") {
            val file = context.configStore.dataDir.resolve(IMPORT_FILE_NAME)
            if (!java.nio.file.Files.exists(file)) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("没有找到备份文件", "把备份文件放到数据目录并命名为 $IMPORT_FILE_NAME：$file")
                )
                return@post
            }
            val text = withContext(Dispatchers.IO) { java.nio.file.Files.readString(file) }
            runCatching { com.daview.shared.api.DaViewJson.decodeFromString(BackupFileDto.serializer(), text) }
                .getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("备份文件解析失败", it.message))
                    return@post
                }
        } else {
            runCatching { call.receive<BackupFileDto>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("备份内容解析失败", it.message))
                return@post
            }
        }

        val summary = runCatching { withContext(Dispatchers.IO) { applyBackup(context, backup) } }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("导入失败", it.message))
                return@post
            }
        call.respond(summary)
    }

    // ------------------------------------------------------------ sync

    get("/api/sync") {
        call.requireAuth(context) ?: return@get
        call.respond(context.syncSettings())
    }

    put("/api/sync") {
        call.requireAuth(context) ?: return@put
        val incoming = call.receive<SyncSettingsDto>()
        context.updateConfig { current ->
            current.copy(
                sync = current.sync.copy(
                    remotePath = incoming.remotePath.ifBlank { current.sync.remotePath },
                    minIntervalMinutes = incoming.minIntervalMinutes.coerceIn(1, 24 * 60)
                )
            )
        }
        // Turning it on has to prove the storage takes writes, so it runs an
        // upload; turning it off is unconditional.
        val result = withContext(Dispatchers.IO) {
            when {
                incoming.enabled && !context.config.sync.enabled -> context.sync.enable()
                !incoming.enabled -> { context.sync.disable(); null }
                else -> null
            }
        }
        call.respond(context.syncSettings(result))
    }

    post("/api/sync/upload") {
        call.requireAuth(context) ?: return@post
        call.respond(withContext(Dispatchers.IO) { context.sync.upload() })
    }

    post("/api/sync/pull") {
        call.requireAuth(context) ?: return@post
        call.respond(withContext(Dispatchers.IO) { context.sync.pull() })
    }

    // ------------------------------------------------------------ items

    get("/api/items") {
        call.requireAuth(context) ?: return@get
        val params = call.request.queryParameters
        val (items, total) = context.repository.query(
            Repository.Query(
                libraryId = params["libraryId"],
                parentId = params["parentId"],
                kind = params["kind"]?.let { value -> ItemKind.entries.firstOrNull { it.name.equals(value, true) } },
                search = params["search"],
                favorite = params["favorite"]?.toBooleanStrictOrNull(),
                sort = params["sort"] ?: "sortName",
                limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100,
                offset = params["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            )
        )
        call.respond(ItemPage(items.map { it.withAssetUrls(call) }, total, params["offset"]?.toIntOrNull() ?: 0))
    }

    get("/api/items/{id}") {
        call.requireAuth(context) ?: return@get
        val item = context.repository.item(call.parameters["id"].orEmpty())
        if (item == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("条目不存在"))
            return@get
        }
        val enriched = if (item.isPlayable && item.mediaStreams.none { !it.isExternal }) {
            withContext(Dispatchers.IO) { runCatching { context.streams.probeItem(item) }.getOrDefault(item) }
        } else item
        call.respond(enriched.withAssetUrls(call))
    }

    get("/api/items/{id}/children") {
        call.requireAuth(context) ?: return@get
        call.respond(context.repository.children(call.parameters["id"].orEmpty()).map { it.withAssetUrls(call) })
    }

    post("/api/items/{id}/favorite") {
        call.requireAuth(context) ?: return@post
        val value = call.request.queryParameters["value"]?.toBoolean() ?: true
        call.respond(context.repository.setFavorite(call.parameters["id"].orEmpty(), value))
    }

    post("/api/items/{id}/played") {
        call.requireAuth(context) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val value = call.request.queryParameters["value"]?.toBoolean() ?: true
        // A series or a season has no bytes of its own, so marking one watched
        // means marking the episodes under it; its own row would just be a
        // second answer to the same question, free to drift from the episodes.
        val episodes = context.repository.episodeIdsUnder(id)
        if (episodes.isEmpty()) {
            call.respond(context.repository.setPlayed(id, value))
        } else {
            withContext(Dispatchers.IO) {
                episodes.forEach { context.repository.setPlayed(it, value) }
            }
            call.respond(context.repository.userData(id))
        }
    }

    // ------------------------------------------------------------ manual identify

    /**
     * Scraping picks the wrong entry now and then. These three endpoints let the
     * user say which entry is right: look up candidates, or paste the provider's
     * own id straight from its site.
     */
    get("/api/items/{id}/identify") {
        call.requireAuth(context) ?: return@get
        val item = context.identifiable(call) ?: return@get
        val parsed = context.metadata.folderTitle(item)
        call.respond(
            IdentifyContextDto(
                itemId = item.id,
                kind = item.kind,
                defaultQuery = parsed.title,
                defaultYear = parsed.year ?: item.year,
                providers = context.metadata.availableProviders(context.config.scraper),
                providerIds = item.providerIds,
                lockedProvider = item.lockedProvider
            )
        )
    }

    get("/api/items/{id}/identify/search") {
        call.requireAuth(context) ?: return@get
        val item = context.identifiable(call) ?: return@get
        val provider = call.request.queryParameters["provider"]
        val parsed = provider?.let { value -> MetadataProvider.entries.firstOrNull { it.name.equals(value, true) } }
        if (parsed == null || parsed == MetadataProvider.NONE) {
            call.respond(HttpStatusCode.BadRequest, ApiError("未知的刮削源"))
            return@get
        }
        val query = call.request.queryParameters["query"]?.takeIf { it.isNotBlank() }
            ?: context.metadata.folderTitle(item).title
        val year = call.request.queryParameters["year"]?.toIntOrNull()
        val kind = if (item.kind == ItemKind.MOVIE) ItemKind.MOVIE else ItemKind.SERIES
        val candidates = withContext(Dispatchers.IO) {
            context.metadata.searchProvider(parsed, query, year, kind, context.scraperConfigFor(item))
        }
        call.respond(
            candidates.map {
                ScrapeCandidateDto(
                    provider = parsed,
                    providerId = it.providerId,
                    title = it.title,
                    originalTitle = it.originalTitle,
                    year = it.year,
                    overview = it.overview?.take(400),
                    posterUrl = it.posterUrl
                )
            }
        )
    }

    post("/api/items/{id}/identify") {
        call.requireAuth(context) ?: return@post
        val item = context.identifiable(call) ?: return@post
        val request = call.receive<IdentifyRequest>()
        if (request.provider == MetadataProvider.NONE || request.providerId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("需要刮削源与条目 id"))
            return@post
        }
        val updated = withContext(Dispatchers.IO) {
            context.metadata.identify(
                item = item,
                provider = request.provider,
                providerId = request.providerId,
                order = context.providerOrderFor(item),
                config = context.scraperConfigFor(item)
            )
        }
        if (updated == null) {
            call.respond(
                HttpStatusCode.BadGateway,
                ApiError("刮削失败", "${request.provider.displayName} 上没有 id ${request.providerId.trim()}，或该源未配置密钥")
            )
            return@post
        }
        call.respond(updated.withAssetUrls(call))
    }

    // ------------------------------------------------------------ home rows

    get("/api/home/resume") {
        call.requireAuth(context) ?: return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        call.respond(context.repository.resume(limit).map { it.withAssetUrls(call) })
    }

    get("/api/home/nextup") {
        call.requireAuth(context) ?: return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        call.respond(context.repository.nextUp(limit).map { it.withAssetUrls(call) })
    }

    get("/api/home/latest") {
        call.requireAuth(context) ?: return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        call.respond(
            context.repository.latest(call.request.queryParameters["libraryId"], limit).map { it.withAssetUrls(call) }
        )
    }

    get("/api/home/unwatched") {
        call.requireAuth(context) ?: return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        call.respond(
            context.repository.unwatched(call.request.queryParameters["libraryId"], limit)
                .map { it.withAssetUrls(call) }
        )
    }

    // ------------------------------------------------------------ playback

    post("/api/playback/start") {
        call.requireAuth(context) ?: return@post
        val request = call.receive<PlaybackStartRequest>()
        val stored = context.repository.item(request.itemId)
        val storedPath = stored?.path
        if (stored == null || storedPath == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("条目不存在或不可播放"))
            return@post
        }
        val item = withContext(Dispatchers.IO) {
            runCatching { context.streams.probeItem(stored) }.getOrDefault(stored)
        }

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
            startPositionMs = userData.positionMs,
            audioStreamIndex = audio,
            subtitleStreamIndex = subtitle
        )

        val token = context.config.accessToken
        val base = call.externalBase()
        val proxy = request.trackThroughProxy && context.config.trackExternalPlayers
        // The file name is carried in the path so external players show a sane
        // title and pick the right demuxer; it has to be percent-encoded or
        // spaces alone will break the hand-off.
        val fileName = encodePathSegment(mediaPath.substringAfterLast('/'))
        val streamUrl = "$base/api/stream/${item.id}/$fileName" +
            "?session=${session.id}&mode=${if (proxy) "proxy" else "redirect"}&token=$token"

        call.respond(
            PlaybackInfoDto(
                sessionId = session.id,
                item = item.withAssetUrls(call),
                streamUrl = streamUrl,
                directUrl = if (request.player == PlayerKind.INTERNAL) {
                    withContext(Dispatchers.IO) { context.streams.directUrl(mediaPath) }
                } else null,
                startPositionMs = userData.positionMs,
                audioStreamIndex = audio,
                subtitleStreamIndex = subtitle,
                subtitleUrls = item.mediaStreams
                    .filter { it.isExternal && it.externalPath != null }
                    .associate { it.index to "$base/api/subtitle/${item.id}/${it.index}?token=$token" },
                container = mediaPath.substringAfterLast('.'),
                runtimeMs = item.runtimeMs
            )
        )
    }

    post("/api/playback/progress") {
        call.requireAuth(context) ?: return@post
        val request = call.receive<PlaybackProgressRequest>()
        val session = context.playback.report(
            request.sessionId, request.positionMs, request.paused,
            request.audioStreamIndex, request.subtitleStreamIndex
        )
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("会话不存在"))
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post("/api/playback/stop") {
        call.requireAuth(context) ?: return@post
        val request = call.receive<PlaybackStopRequest>()
        context.playback.stop(request.sessionId, request.positionMs.takeIf { it >= 0 })
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/playback/sessions") {
        call.requireAuth(context) ?: return@get
        call.respond(context.playback.activeSessions())
    }

    // ------------------------------------------------------------ media bytes

    get("/api/stream/{id}/{name...}") {
        call.requireAuth(context) ?: return@get
        val item = context.repository.item(call.parameters["id"].orEmpty())
        val path = item?.path
        if (path == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("条目不存在"))
            return@get
        }
        val sessionId = call.request.queryParameters["session"]
        val rangeHeader = call.request.header(HttpHeaders.Range)
        val rangeStart = rangeHeader?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
        sessionId?.let { context.playback.onRangeRequest(it, rangeStart) }

        val mode = call.request.queryParameters["mode"] ?: "redirect"
        if (mode != "proxy") {
            val direct = withContext(Dispatchers.IO) { context.streams.directUrl(path) }
            if (direct != null) {
                call.respondRedirect(direct, permanent = false)
                return@get
            }
        }

        val size = item.sizeBytes ?: withContext(Dispatchers.IO) { context.streams.fileSize(path) }
        val rangeEnd = rangeHeader?.substringAfter('-')?.toLongOrNull()
            ?: size?.let { it - 1 }

        val stream = withContext(Dispatchers.IO) { context.streams.openRange(path, rangeStart, rangeEnd) }
        val effectiveSize = size ?: stream.totalSize
        val length = if (effectiveSize != null && rangeEnd != null) rangeEnd - rangeStart + 1 else null

        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.CacheControl, CacheControl.NoCache(null).toString())
        if (effectiveSize != null && rangeEnd != null) {
            call.response.header(HttpHeaders.ContentRange, "bytes $rangeStart-$rangeEnd/$effectiveSize")
        }

        val status = if (rangeHeader != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
        call.respondBytesWriter(
            contentType = ContentType.Application.OctetStream,
            status = status,
            contentLength = length
        ) {
            var delivered = 0L
            val buffer = ByteArray(STREAM_BUFFER)
            try {
                while (true) {
                    val read = withContext(Dispatchers.IO) { stream.stream.read(buffer) }
                    if (read <= 0) break
                    writeFully(buffer, 0, read)
                    delivered += read
                    sessionId?.let { context.playback.onBytesRead(it, rangeStart + delivered) }
                }
                flush()
            } finally {
                stream.close()
            }
        }
    }

    get("/api/subtitle/{id}/{index}") {
        call.requireAuth(context) ?: return@get
        val item = context.repository.item(call.parameters["id"].orEmpty())
        val index = call.parameters["index"]?.toIntOrNull()
        val stream = item?.mediaStreams?.firstOrNull { it.index == index && it.isExternal }
        val path = stream?.externalPath
        if (path == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("字幕不存在"))
            return@get
        }
        val bytes = withContext(Dispatchers.IO) {
            context.streams.openRange(path, 0, null).use { it.stream.readBytes() }
        }
        val contentType = when (stream.codec) {
            "vtt" -> ContentType.parse("text/vtt")
            "srt" -> ContentType.parse("application/x-subrip")
            else -> ContentType.parse("text/plain; charset=utf-8")
        }
        call.respondBytesWriter(contentType = contentType, status = HttpStatusCode.OK, contentLength = bytes.size.toLong()) {
            writeFully(bytes, 0, bytes.size)
            flush()
        }
    }

    /**
     * Compose for Web renders into a canvas and has no access to the system
     * fonts, so CJK text comes out as tofu unless a font is supplied. The
     * server hands over one it finds on its own machine rather than bundling a
     * ~10 MB font in the repository.
     */
    get("/api/font/cjk") {
        call.requireAuth(context) ?: return@get
        val font = com.daview.server.media.SystemFonts.cjkFont()
        if (font == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("服务器上没有找到中日韩字体"))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        call.respondFile(font.toFile())
    }

    get("/api/images/{id}/{type}") {
        call.requireAuth(context) ?: return@get
        val item = context.repository.item(call.parameters["id"].orEmpty())
        val remote = when (call.parameters["type"]) {
            "backdrop" -> item?.backdropUrl
            "logo" -> item?.logoUrl
            else -> item?.posterUrl
        }
        if (remote.isNullOrBlank()) {
            call.respond(HttpStatusCode.NotFound, ApiError("没有图片"))
            return@get
        }
        val entry = withContext(Dispatchers.IO) { context.images.get(remote) }
        if (entry == null) {
            call.respond(HttpStatusCode.BadGateway, ApiError("图片下载失败"))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=2592000")
        call.respondFile(entry.file.toFile())
    }
}

// ---------------------------------------------------------------- helpers

/** The item named by the route, once it is one that can carry scraped metadata. */
private suspend fun ServerContext.identifiable(call: ApplicationCall): MediaItemDto? {
    val item = repository.item(call.parameters["id"].orEmpty())
    if (item == null) {
        call.respond(HttpStatusCode.NotFound, ApiError("条目不存在"))
        return null
    }
    if (item.kind != ItemKind.MOVIE && item.kind != ItemKind.SERIES) {
        call.respond(HttpStatusCode.BadRequest, ApiError("只有电影和剧集可以手动指定刮削条目"))
        return null
    }
    return item
}

private fun ServerContext.syncSettings(result: com.daview.shared.model.SyncResultDto? = null) =
    SyncSettingsDto(
        enabled = config.sync.enabled,
        remotePath = config.sync.remotePath,
        minIntervalMinutes = config.sync.minIntervalMinutes,
        lastUploadAt = config.sync.lastUploadAt,
        lastPullAt = config.sync.lastPullAt,
        lastError = result?.takeIf { !it.ok }?.message ?: config.sync.lastError,
        writable = sync.storageWritable
    )

/** Scraper credentials, with the language of the library the item belongs to. */
private fun ServerContext.scraperConfigFor(item: MediaItemDto) =
    config.scraper.copy(language = repository.library(item.libraryId)?.language ?: config.scraper.language)

private fun ServerContext.providerOrderFor(item: MediaItemDto): List<MetadataProvider> {
    val library = repository.library(item.libraryId) ?: return emptyList()
    return library.providerOrder.ifEmpty { metadata.defaultOrder(library.kind) }
}

private fun encodePathSegment(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8)
        .replace("+", "%20")
        .replace("%2F", "/")

private fun pickDefaultSubtitle(item: MediaItemDto): Int? {
    val subtitles = item.mediaStreams.filter { it.type == StreamType.SUBTITLE }
    if (subtitles.isEmpty()) return null
    return subtitles.firstOrNull { it.isDefault }?.index
        ?: subtitles.firstOrNull { it.language?.startsWith("zh") == true }?.index
        ?: subtitles.first().index
}

/**
 * Rewrites remote artwork URLs to the server's own cached endpoints.
 *
 * The token goes in the query string because image URLs are consumed by
 * `<img>` tags and image loaders that cannot attach an Authorization header.
 */
private fun MediaItemDto.withAssetUrls(call: ApplicationCall): MediaItemDto {
    val base = call.externalBase()
    val token = call.attributes.getOrNull(AccessTokenKey).orEmpty()
    // The endpoint URL is stable per item, so re-identifying an item would leave
    // every client showing the old poster from its own cache. The version is
    // derived from the remote URL, which changes exactly when the artwork does.
    fun endpoint(type: String, remote: String): String {
        val tokenPart = if (token.isBlank()) "" else "&token=$token"
        return "$base/api/images/$id/$type?v=${imageVersion(remote)}$tokenPart"
    }
    return copy(
        posterUrl = posterUrl?.let { endpoint("primary", it) },
        backdropUrl = backdropUrl?.let { endpoint("backdrop", it) },
        logoUrl = logoUrl?.let { endpoint("logo", it) }
    )
}

private fun imageVersion(remoteUrl: String): String =
    (remoteUrl.hashCode().toLong() and 0xffffffffL).toString(16)

private fun ApplicationCall.externalBase(): String {
    val forwardedProto = request.header("X-Forwarded-Proto")
    val forwardedHost = request.header("X-Forwarded-Host")
    if (forwardedHost != null) return "${forwardedProto ?: "http"}://$forwardedHost"
    val origin = request.origin
    val port = origin.serverPort
    val defaultPort = (origin.scheme == "http" && port == 80) || (origin.scheme == "https" && port == 443)
    return if (defaultPort) "${origin.scheme}://${origin.serverHost}"
    else "${origin.scheme}://${origin.serverHost}:$port"
}

/**
 * Bearer token or `?token=` query parameter. Returns null (after answering with
 * 401) when the caller is not authorised.
 */
private suspend fun ApplicationCall.requireAuth(context: ServerContext): Unit? {
    val expected = context.config.accessToken
    if (expected.isBlank()) return Unit
    val header = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
    val query = request.queryParameters["token"]
    if (header == expected || query == expected) {
        attributes.put(AccessTokenKey, expected)
        return Unit
    }
    respond(HttpStatusCode.Unauthorized, ApiError("令牌无效", "请在设置中填写服务器令牌"))
    return null
}

private val AccessTokenKey = io.ktor.util.AttributeKey<String>("daview-access-token")

private fun com.daview.server.config.AppConfig.toDto() = ServerSettingsDto(
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

private fun com.daview.server.storage.DavEntry.toDto() = WebDavEntryDto(
    name = name,
    path = path,
    isDirectory = isDirectory,
    sizeBytes = size,
    lastModified = lastModified
)
