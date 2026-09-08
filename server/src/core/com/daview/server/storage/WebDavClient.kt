package com.daview.server.storage

import com.daview.server.config.StorageConfig
import org.w3c.dom.Element
import org.w3c.dom.Node
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
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

    // OkHttp rather than java.net.http: the same blocking API exists on Android,
    // where java.net.http does not exist at all.
    //
    // Redirects are not followed because the 302 to the signed CDN link is
    // information this class hands out (see resolveDirectUrl), and there is no
    // call timeout because the same client streams multi-gigabyte ranges.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val rootUri: URI = URI.create(config.url.trimEnd('/'))
    private val rootPath: String = rootUri.rawPath.trimEnd('/')

    private val authHeader: String? = if (config.username.isBlank()) null else
        "Basic " + Base64.getEncoder()
            .encodeToString("${config.username}:${config.password}".toByteArray(StandardCharsets.UTF_8))

    private fun Request.Builder.auth(): Request.Builder =
        also { b -> authHeader?.let { b.header("Authorization", it) } }

    private fun request(url: String, useAuth: Boolean = true): Request.Builder =
        Request.Builder().url(url).also { if (useAuth) it.auth() }

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

        val request = request(url)
            .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw WebDavException("无法连接 WebDAV: ${e.message}", cause = e)
        }
        response.use {
            if (it.code == 401 || it.code == 403) {
                throw WebDavException("WebDAV 认证失败 (${it.code})", it.code)
            }
            if (!it.isSuccessful) {
                throw WebDavException("WebDAV PROPFIND 失败: HTTP ${it.code}", it.code)
            }
            return parseMultiStatus(it.body.string(), relativePath)
        }
    }

    private fun parseMultiStatus(xml: String, requestedPath: String): List<DavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Android's parser is Expat-based and rejects features the JDK's
            // Xerces accepts, so each one is applied where it exists rather than
            // assumed. Expat does not resolve external entities in the first
            // place, which is what these guard against.
            HARDENING.forEach { feature ->
                runCatching { setFeature(feature, false) }
            }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
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
        val request = request(absoluteUrl(relativePath)).head().build()
        val response = runCatching { http.newCall(request).execute() }.getOrNull() ?: return null
        return response.use { it.header("Content-Length")?.toLongOrNull() }
    }

    /**
     * Resolves the short-lived direct download URL the storage backend hands out.
     * Returns null when the backend streams the bytes itself instead of redirecting.
     */
    fun resolveDirectUrl(relativePath: String): String? {
        val request = request(absoluteUrl(relativePath))
            .get()
            .header("Range", "bytes=0-0")
            .build()
        val response = runCatching { http.newCall(request).execute() }
            .getOrElse { throw WebDavException("解析直链失败: ${it.message}", cause = it) }
        return response.use { if (it.code in 300..399) it.header("Location") else null }
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
        val request = request(url, useAuth)
            .get()
            .header("Range", if (end == null) "bytes=$start-" else "bytes=$start-$end")
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            runCatching { response.close() }
            throw WebDavException("读取字节区间失败: HTTP $code", code)
        }
        val totalSize = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            ?: response.header("Content-Length")?.toLongOrNull()
        return RangeStream(response.body.byteStream(), totalSize, response.code == 206, response)
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
        val request = request(absoluteUrl(relativePath))
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        val response = runCatching { http.newCall(request).execute() }
            .getOrElse { return WriteResult(false, null, it.message ?: it::class.simpleName) }
        return response.use {
            if (it.isSuccessful) {
                WriteResult(true, it.code, null)
            } else {
                val detail = runCatching { it.body.string().take(200) }.getOrDefault("")
                WriteResult(false, it.code, "HTTP ${it.code}" + if (detail.isBlank()) "" else ": $detail")
            }
        }
    }

    /** Deletes a single path. Used only to clean up files this app wrote. */
    fun delete(relativePath: String): WriteResult {
        val request = request(absoluteUrl(relativePath)).delete().build()
        val response = runCatching { http.newCall(request).execute() }
            .getOrElse { return WriteResult(false, null, it.message) }
        return response.use { WriteResult(it.isSuccessful || it.code == 404, it.code, null) }
    }

    /** Bytes of a file the app wrote, or null when it is not there yet. */
    fun readIfPresent(relativePath: String, limit: Long = 32L * 1024 * 1024): ByteArray? =
        read(relativePath, limit).bytes

    /**
     * Same read, but says which kind of nothing came back.
     *
     * A caller that is about to replace the file has to tell "it is not there"
     * from "I could not find out": `PUT` writes the whole file, so treating a
     * timeout as an empty share would overwrite what every other device wrote.
     */
    fun read(relativePath: String, limit: Long = 32L * 1024 * 1024): ReadResult {
        val request = request(absoluteUrl(relativePath)).get().build()
        val response = runCatching { http.newCall(request).execute() }
            .getOrElse { return ReadResult(error = it.message ?: it::class.simpleName) }
        response.use {
            // A signed CDN redirect is how this gateway serves file bodies.
            if (it.code in 300..399) {
                val location = it.header("Location")
                    ?: return ReadResult(error = "${it.code} 跳转但没有 Location")
                return runCatching {
                    ReadResult(
                        bytes = openRangeAt(location, 0, limit - 1, useAuth = false)
                            .use { range -> range.stream.readBytes() }
                    )
                }.getOrElse { cause -> ReadResult(error = cause.message ?: cause::class.simpleName) }
            }
            if (it.code == 404 || it.code == 410) return ReadResult(missing = true)
            if (!it.isSuccessful) return ReadResult(error = "HTTP ${it.code}")
            return ReadResult(bytes = it.body.bytes())
        }
    }

    class ReadResult(
        val bytes: ByteArray? = null,
        /** The share answered, and the file is not there. */
        val missing: Boolean = false,
        /** Set when the share could not be asked at all. */
        val error: String? = null
    )

    private fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%2F", "/")
            .replace("*", "%2A")
            .replace("%7E", "~")

    class RangeStream(
        val stream: InputStream,
        val totalSize: Long?,
        val partial: Boolean,
        /** Held so the connection goes back to the pool even on a partial read. */
        private val response: Response? = null
    ) : AutoCloseable {
        override fun close() {
            runCatching { stream.close() }
            runCatching { response?.close() }
        }
    }

    private companion object {
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        val HARDENING = listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd"
        )

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
