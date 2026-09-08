package com.daview.server.storage

import com.daview.server.config.StorageConfig
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

data class DavEntry(
    val name: String,
    /** Path relative to the configured WebDAV root, always starting with `/`. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
    val lastModified: String?,
    val etag: String?
)

class WebDavException(message: String, val status: Int? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Minimal WebDAV client built on the JDK HTTP client.
 *
 * Two behaviours of the 123pan endpoint shape this class:
 *  - `GET` on a file answers `302` with a signed, time-limited CDN link that
 *    needs no credentials and honours `Range` (see [resolveDirectUrl]).
 *  - Whether it accepts writes cannot be read off `OPTIONS`: the gateway keeps
 *    `PUT` out of the `Allow` header even on a share that accepts it, so [put]
 *    is the only way to find out.
 */
class WebDavClient(private val config: StorageConfig) {

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private val rootUri: URI = URI.create(config.url.trimEnd('/'))
    private val rootPath: String = rootUri.rawPath.trimEnd('/')

    private val authHeader: String? = if (config.username.isBlank()) null else
        "Basic " + Base64.getEncoder()
            .encodeToString("${config.username}:${config.password}".toByteArray(StandardCharsets.UTF_8))

    private fun HttpRequest.Builder.auth(): HttpRequest.Builder =
        also { b -> authHeader?.let { b.header("Authorization", it) } }

    /** Builds an absolute, percent-encoded URL for a path relative to the WebDAV root. */
    fun absoluteUrl(relativePath: String): String {
        val encoded = relativePath.trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }
        val base = rootUri.scheme + "://" + rootUri.authority + rootPath
        return if (encoded.isEmpty()) "$base/" else "$base/$encoded"
    }

    fun list(relativePath: String): List<DavEntry> {
        val url = absoluteUrl(relativePath).let { if (it.endsWith("/")) it else "$it/" }
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<d:propfind xmlns:d="DAV:"><d:prop>
            |<d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/>
            |</d:prop></d:propfind>""".trimMargin()

        val request = HttpRequest.newBuilder(URI.create(url))
            .method("PROPFIND", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .timeout(Duration.ofSeconds(90))
            .auth()
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw WebDavException("无法连接 WebDAV: ${e.message}", cause = e)
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw WebDavException("WebDAV 认证失败 (${response.statusCode()})", response.statusCode())
        }
        if (response.statusCode() !in 200..299) {
            throw WebDavException("WebDAV PROPFIND 失败: HTTP ${response.statusCode()}", response.statusCode())
        }
        return parseMultiStatus(response.body(), relativePath)
    }

    private fun parseMultiStatus(xml: String, requestedPath: String): List<DavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder()
            .parse(xml.byteInputStream(StandardCharsets.UTF_8))

        val requested = normalise(requestedPath)
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val out = ArrayList<DavEntry>(responses.length)
        for (i in 0 until responses.length) {
            val element = responses.item(i) as? Element ?: continue
            val href = element.childText("href")?.trim() ?: continue
            val relative = hrefToRelative(href)
            if (normalise(relative) == requested) continue

            val propstats = element.getElementsByTagNameNS("DAV:", "propstat")
            var isDir = false
            var size: Long? = null
            var modified: String? = null
            var etag: String? = null
            var displayName: String? = null
            for (p in 0 until propstats.length) {
                val propstat = propstats.item(p) as? Element ?: continue
                val status = propstat.childText("status").orEmpty()
                if (!status.contains(" 200")) continue
                val prop = propstat.firstChild("prop") ?: continue
                if (prop.firstChild("resourcetype")?.firstChild("collection") != null) isDir = true
                prop.childText("getcontentlength")?.trim()?.toLongOrNull()?.let { size = it }
                prop.childText("getlastmodified")?.let { modified = it }
                prop.childText("getetag")?.let { etag = it.trim('"') }
                prop.childText("displayname")?.takeIf { it.isNotBlank() }?.let { displayName = it }
            }
            val name = displayName ?: relative.trimEnd('/').substringAfterLast('/')
            out += DavEntry(
                name = name,
                path = "/" + relative.trim('/'),
                isDirectory = isDir,
                size = size,
                lastModified = modified,
                etag = etag
            )
        }
        return out.sortedWith(compareByDescending<DavEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    private fun hrefToRelative(href: String): String {
        val path = runCatching { URI.create(href).rawPath }.getOrNull() ?: href
        val decoded = URLDecoder.decode(path, StandardCharsets.UTF_8)
        val decodedRoot = URLDecoder.decode(rootPath, StandardCharsets.UTF_8)
        return if (decodedRoot.isNotEmpty() && decoded.startsWith(decodedRoot)) {
            decoded.removePrefix(decodedRoot)
        } else {
            decoded
        }
    }

    private fun normalise(path: String) = "/" + path.trim('/')

    /** File size via `HEAD`, or null when the server does not answer with one. */
    fun size(relativePath: String): Long? {
        val request = HttpRequest.newBuilder(URI.create(absoluteUrl(relativePath)))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(30))
            .auth()
            .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.discarding()) }.getOrNull()
            ?: return null
        return response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
    }

    /**
     * Resolves the short-lived direct download URL the storage backend hands out.
     * Returns null when the backend streams the bytes itself instead of redirecting.
     */
    fun resolveDirectUrl(relativePath: String): String? {
        val request = HttpRequest.newBuilder(URI.create(absoluteUrl(relativePath)))
            .GET()
            .header("Range", "bytes=0-0")
            .timeout(Duration.ofSeconds(45))
            .auth()
            .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.discarding()) }
            .getOrElse { throw WebDavException("解析直链失败: ${it.message}", cause = it) }
        return if (response.statusCode() in 300..399) {
            response.headers().firstValue("Location").orElse(null)
        } else {
            null
        }
    }

    /**
     * Opens a byte range. [end] is inclusive, as in the HTTP spec; null means
     * "to the end of the file".
     */
    fun openRange(relativePath: String, start: Long, end: Long?): RangeStream {
        val direct = runCatching { resolveDirectUrl(relativePath) }.getOrNull()
        return openRangeAt(direct ?: absoluteUrl(relativePath), start, end, useAuth = direct == null)
    }

    /**
     * Range read against an already-resolved URL. Callers that read a file more
     * than once (container probing, cue lookups) resolve the direct link once
     * and reuse it here, which removes a redirect round trip per read.
     */
    fun openRangeAt(url: String, start: Long, end: Long?, useAuth: Boolean): RangeStream {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .header("Range", if (end == null) "bytes=$start-" else "bytes=$start-$end")
            .timeout(Duration.ofSeconds(60))
        if (useAuth) builder.auth()

        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            runCatching { response.body().close() }
            throw WebDavException("读取字节区间失败: HTTP ${response.statusCode()}", response.statusCode())
        }
        val totalSize = response.headers().firstValue("Content-Range").orElse(null)
            ?.substringAfter('/')?.toLongOrNull()
            ?: response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
        return RangeStream(response.body(), totalSize, response.statusCode() == 206)
    }

    fun readFully(relativePath: String, limit: Long = 4L * 1024 * 1024): ByteArray =
        openRange(relativePath, 0, limit - 1).use { it.stream.readNBytes(limit.toInt()) }

    fun probe(): List<DavEntry> = list("/")

    /** Outcome of a write, kept separate from exceptions so callers can show why. */
    data class WriteResult(val ok: Boolean, val status: Int?, val message: String?) {
        /** The share answered, and answered "no". Distinct from a network failure. */
        val forbidden: Boolean get() = status == 403 || status == 401 || status == 405
    }

    /**
     * Uploads [bytes], replacing whatever is at that path.
     *
     * Only ever called with a path the app owns; nothing here walks the tree or
     * touches media files.
     */
    fun put(relativePath: String, bytes: ByteArray, contentType: String = "application/json"): WriteResult {
        val request = HttpRequest.newBuilder(URI.create(absoluteUrl(relativePath)))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .header("Content-Type", contentType)
            .timeout(Duration.ofSeconds(180))
            .auth()
            .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { return WriteResult(false, null, it.message ?: it::class.simpleName) }
        val status = response.statusCode()
        return if (status in 200..299) {
            WriteResult(true, status, null)
        } else {
            WriteResult(false, status, "HTTP $status" + response.body().take(200).let {
                if (it.isBlank()) "" else ": $it"
            })
        }
    }

    /** Deletes a single path. Used only to clean up files this app wrote. */
    fun delete(relativePath: String): WriteResult {
        val request = HttpRequest.newBuilder(URI.create(absoluteUrl(relativePath)))
            .DELETE()
            .timeout(Duration.ofSeconds(60))
            .auth()
            .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.discarding()) }
            .getOrElse { return WriteResult(false, null, it.message) }
        val status = response.statusCode()
        return WriteResult(status in 200..299 || status == 404, status, null)
    }

    /** Bytes of a file the app wrote, or null when it is not there yet. */
    fun readIfPresent(relativePath: String, limit: Long = 32L * 1024 * 1024): ByteArray? {
        val request = HttpRequest.newBuilder(URI.create(absoluteUrl(relativePath)))
            .GET()
            .timeout(Duration.ofSeconds(120))
            .auth()
            .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofByteArray()) }
            .getOrElse { return null }
        // A signed CDN redirect is how this gateway serves file bodies.
        if (response.statusCode() in 300..399) {
            val location = response.headers().firstValue("location").orElse(null) ?: return null
            return openRangeAt(location, 0, limit - 1, useAuth = false).use { it.stream.readBytes() }
        }
        if (response.statusCode() == 404) return null
        if (response.statusCode() !in 200..299) return null
        return response.body()
    }

    private fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%2F", "/")
            .replace("*", "%2A")
            .replace("%7E", "~")

    class RangeStream(
        val stream: InputStream,
        val totalSize: Long?,
        val partial: Boolean
    ) : AutoCloseable {
        override fun close() {
            runCatching { stream.close() }
        }
    }

    private companion object {
        fun Element.firstChild(localName: String): Element? {
            val nodes = childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node.nodeType == Node.ELEMENT_NODE && (node as Element).localName == localName) return node
            }
            return null
        }

        fun Element.childText(localName: String): String? = firstChild(localName)?.textContent
    }
}
