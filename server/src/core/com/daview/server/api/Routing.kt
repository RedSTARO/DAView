package com.daview.server.api

import com.daview.server.ServerContext
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
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STREAM_BUFFER = 256 * 1024

/** What `source=datadir` looks for inside the data directory. */
const val IMPORT_FILE_NAME = "import.json"

/**
 * The HTTP face of [MediaFacade].
 *
 * Nothing here decides anything: every handler reads the request, calls the
 * facade and writes the answer. What the routes still own is the part that is
 * genuinely about the protocol — the access token, byte ranges, cache headers,
 * and turning artwork into URLs an `<img>` tag can fetch.
 */
fun Route.apiRoutes(context: ServerContext) {
    val media = context.media

    // ------------------------------------------------------------ server info

    get("/api/info") {
        call.requireAuth(context) ?: return@get
        call.respond(media.info())
    }

    // ------------------------------------------------------------ settings

    get("/api/settings") {
        call.requireAuth(context) ?: return@get
        call.respond(media.settings())
    }

    put("/api/settings") {
        call.requireAuth(context) ?: return@put
        call.respond(media.updateSettings(call.receive()))
    }

    // ------------------------------------------------------------ storage

    post("/api/storage/test") {
        call.requireAuth(context) ?: return@post
        call.respond(media.testStorage(call.receive()))
    }

    get("/api/storage/browse") {
        call.requireAuth(context) ?: return@get
        call.respond(media.browseStorage(call.request.queryParameters["path"] ?: "/"))
    }

    // ------------------------------------------------------------ libraries

    get("/api/libraries") {
        call.requireAuth(context) ?: return@get
        call.respond(media.libraries())
    }

    post("/api/libraries") {
        call.requireAuth(context) ?: return@post
        call.respond(media.createLibrary(call.receive()))
    }

    put("/api/libraries/{id}") {
        call.requireAuth(context) ?: return@put
        call.respond(media.updateLibrary(call.parameters["id"].orEmpty(), call.receive()))
    }

    delete("/api/libraries/{id}") {
        call.requireAuth(context) ?: return@delete
        media.deleteLibrary(call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }

    post("/api/libraries/{id}/scan") {
        call.requireAuth(context) ?: return@post
        val params = call.request.queryParameters
        val mode = params["mode"]?.let { value -> ScanMode.entries.firstOrNull { it.name.equals(value, true) } }
            ?: if (params["refresh"]?.toBoolean() == true) ScanMode.REFRESH else ScanMode.FULL
        call.respond(media.scan(call.parameters["id"].orEmpty(), mode))
    }

    get("/api/scan/status") {
        call.requireAuth(context) ?: return@get
        call.respond(media.scanStatus())
    }

    // ------------------------------------------------------------ backup

    /**
     * Migration in two calls: download the file here, POST it to the new
     * server. Credentials stay out unless `secrets=true` is asked for, because
     * the result is a plain file the user is about to move between machines.
     */
    get("/api/backup/export") {
        call.requireAuth(context) ?: return@get
        fun flag(name: String, default: Boolean) =
            call.request.queryParameters[name]?.toBooleanStrictOrNull() ?: default
        call.respondBackup(
            context,
            BackupOptions(
                settings = flag("settings", true),
                libraries = flag("libraries", true),
                items = flag("items", true),
                userData = flag("userdata", true),
                // Not tied to the catalogue switch: a few dozen bytes each, and
                // the one piece of scrape data a rescan cannot reproduce.
                pins = flag("pins", true),
                secrets = flag("secrets", false)
            )
        )
    }

    post("/api/backup/import") {
        call.requireAuth(context) ?: return@post
        // `source=datadir` reads <data>/import.json, so a headless restore needs
        // no file picker: the file is copied in next to the database.
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
        call.respond(media.importBackup(backup))
    }

    // ------------------------------------------------------------ sync

    get("/api/sync") {
        call.requireAuth(context) ?: return@get
        call.respond(media.syncSettings())
    }

    put("/api/sync") {
        call.requireAuth(context) ?: return@put
        call.respond(media.updateSyncSettings(call.receive()))
    }

    post("/api/sync/upload") {
        call.requireAuth(context) ?: return@post
        call.respond(media.syncUpload())
    }

    post("/api/sync/pull") {
        call.requireAuth(context) ?: return@post
        call.respond(media.syncPull())
    }

    // ------------------------------------------------------------ items

    get("/api/items") {
        call.requireAuth(context) ?: return@get
        val params = call.request.queryParameters
        call.respond(
            media.items(
                links = call.links(context),
                libraryId = params["libraryId"],
                parentId = params["parentId"],
                kind = params["kind"]?.let { value -> ItemKind.entries.firstOrNull { it.name.equals(value, true) } },
                search = params["search"],
                favorite = params["favorite"]?.toBooleanStrictOrNull(),
                sort = params["sort"] ?: "sortName",
                limit = params["limit"]?.toIntOrNull() ?: 100,
                offset = params["offset"]?.toIntOrNull() ?: 0
            )
        )
    }

    get("/api/items/{id}") {
        call.requireAuth(context) ?: return@get
        call.respond(media.item(call.parameters["id"].orEmpty(), call.links(context)))
    }

    get("/api/items/{id}/children") {
        call.requireAuth(context) ?: return@get
        call.respond(media.children(call.parameters["id"].orEmpty(), call.links(context)))
    }

    post("/api/items/{id}/favorite") {
        call.requireAuth(context) ?: return@post
        val value = call.request.queryParameters["value"]?.toBoolean() ?: true
        call.respond(media.setFavorite(call.parameters["id"].orEmpty(), value))
    }

    post("/api/items/{id}/played") {
        call.requireAuth(context) ?: return@post
        val value = call.request.queryParameters["value"]?.toBoolean() ?: true
        call.respond(media.setPlayed(call.parameters["id"].orEmpty(), value))
    }

    // ------------------------------------------------------------ merging duplicates

    /**
     * The same show can land in the library twice — two folders, or one copy in
     * the anime library and another in the TV one. Merging folds the duplicates'
     * seasons and episodes under one item and hides the spare entries.
     */
    get("/api/items/{id}/merged") {
        call.requireAuth(context) ?: return@get
        call.respond(media.mergedSources(call.parameters["id"].orEmpty(), call.links(context)))
    }

    post("/api/items/{id}/merge") {
        call.requireAuth(context) ?: return@post
        val request = call.receive<MergeRequest>()
        call.respond(media.merge(call.parameters["id"].orEmpty(), request.sourceIds, call.links(context)))
    }

    post("/api/items/{id}/unmerge") {
        call.requireAuth(context) ?: return@post
        call.respond(media.unmerge(call.parameters["id"].orEmpty(), call.links(context)))
    }

    // ------------------------------------------------------------ manual identify

    /**
     * Scraping picks the wrong entry now and then. These three endpoints let the
     * user say which entry is right: look up candidates, or paste the provider's
     * own id straight from its site.
     */
    get("/api/items/{id}/identify") {
        call.requireAuth(context) ?: return@get
        call.respond(media.identifyContext(call.parameters["id"].orEmpty()))
    }

    get("/api/items/{id}/identify/search") {
        call.requireAuth(context) ?: return@get
        val params = call.request.queryParameters
        val provider = params["provider"]
            ?.let { value -> MetadataProvider.entries.firstOrNull { it.name.equals(value, true) } }
        call.respond(
            media.identifySearch(
                id = call.parameters["id"].orEmpty(),
                provider = provider,
                query = params["query"],
                year = params["year"]?.toIntOrNull()
            )
        )
    }

    post("/api/items/{id}/identify") {
        call.requireAuth(context) ?: return@post
        call.respond(media.identify(call.parameters["id"].orEmpty(), call.receive(), call.links(context)))
    }

    // ------------------------------------------------------------ home rows

    get("/api/home/resume") {
        call.requireAuth(context) ?: return@get
        call.respond(media.resume(call.limit(), call.links(context)))
    }

    get("/api/home/nextup") {
        call.requireAuth(context) ?: return@get
        call.respond(media.nextUp(call.limit(), call.links(context)))
    }

    get("/api/home/latest") {
        call.requireAuth(context) ?: return@get
        call.respond(
            media.latest(call.request.queryParameters["libraryId"], call.limit(), call.links(context))
        )
    }

    get("/api/home/unwatched") {
        call.requireAuth(context) ?: return@get
        call.respond(
            media.unwatched(call.request.queryParameters["libraryId"], call.limit(), call.links(context))
        )
    }

    // ------------------------------------------------------------ playback

    post("/api/playback/start") {
        call.requireAuth(context) ?: return@post
        call.respond(media.startPlayback(call.receive(), call.links(context)))
    }

    post("/api/playback/progress") {
        call.requireAuth(context) ?: return@post
        if (media.reportProgress(call.receive())) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound, ApiError("会话不存在"))
    }

    post("/api/playback/stop") {
        call.requireAuth(context) ?: return@post
        media.stopPlayback(call.receive())
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/playback/sessions") {
        call.requireAuth(context) ?: return@get
        call.respond(media.sessions())
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


    get("/api/images/{id}/{type}") {
        call.requireAuth(context) ?: return@get
        val file = media.imageFile(call.parameters["id"].orEmpty(), call.parameters["type"].orEmpty())
        if (file == null) {
            // Either the item has no artwork or fetching it failed; the client
            // draws the same placeholder for both, so they answer the same.
            call.respond(HttpStatusCode.NotFound, ApiError("没有图片", "条目没有这张图，或者下载失败"))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=2592000")
        call.respondFile(file.toFile())
    }
}

// ---------------------------------------------------------------- helpers

private fun ApplicationCall.limit(): Int = request.queryParameters["limit"]?.toIntOrNull() ?: 20

/**
 * Artwork and media addressed as URLs back into this server.
 *
 * The token goes in the query string because these URLs are consumed by `<img>`
 * tags, image loaders and external players, none of which can attach an
 * Authorization header.
 */
private class HttpAssetLinks(private val base: String, private val token: String) : AssetLinks {

    private val tokenPart = if (token.isBlank()) "" else "&token=$token"

    // The endpoint is stable per item, so re-identifying one would leave every
    // client showing the old poster out of its own cache. The version comes
    // from the remote URL, which changes exactly when the artwork does.
    override fun image(itemId: String, type: String, remoteUrl: String): String =
        "$base/api/images/$itemId/$type?v=${imageVersion(remoteUrl)}$tokenPart"

    override fun stream(itemId: String, fileName: String, sessionId: String, proxy: Boolean): String =
        "$base/api/stream/$itemId/${encodePathSegment(fileName)}" +
            "?session=$sessionId&mode=${if (proxy) "proxy" else "redirect"}" +
            (if (token.isBlank()) "" else "&token=$token")

    override fun subtitle(itemId: String, index: Int): String =
        "$base/api/subtitle/$itemId/$index" + if (token.isBlank()) "" else "?token=$token"
}

private fun ApplicationCall.links(context: ServerContext): AssetLinks =
    HttpAssetLinks(externalBase(), attributes.getOrNull(AccessTokenKey).orEmpty())

private fun imageVersion(remoteUrl: String): String =
    (remoteUrl.hashCode().toLong() and 0xffffffffL).toString(16)

/**
 * The file name is carried in the stream path so external players show a sane
 * title and pick the right demuxer; it has to be percent-encoded or spaces
 * alone will break the hand-off.
 */
private fun encodePathSegment(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8)
        .replace("+", "%20")
        .replace("%2F", "/")

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
 * Bearer token or `?token=`. Returns null (after answering with 401) when the
 * caller is not authorised.
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
