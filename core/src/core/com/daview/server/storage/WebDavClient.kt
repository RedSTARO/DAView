package com.daview.server.storage

import com.daview.server.config.StorageConfig
import com.daview.server.io.readUpTo
import org.w3c.dom.Element
import org.w3c.dom.Node
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
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
 * The one thing the scanner asks of the storage: what a directory holds.
 *
 * Split out so a walk can be exercised against a tree held in memory; the
 * real client is the only implementation that talks to a share.
 */
fun interface DirectoryLister {
    fun list(relativePath: String): List<DavEntry>
}

/**
 * Minimal WebDAV client built on OkHttp.
 *
 * Two behaviours of the 123pan endpoint shape this class:
 *  - `GET` on a file answers `302` with a signed, time-limited CDN link that
 *    needs no credentials and honours `Range` (see [resolveDirectUrl]).
 *  - Whether it accepts writes cannot be read off `OPTIONS`: the gateway keeps
 *    `PUT` out of the `Allow` header even on a share that accepts it, so [put]
 *    is the only way to find out.
 */
class WebDavClient(private val config: StorageConfig) : DirectoryLister {

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

    // Whole-file reads follow redirects using OkHttp's origin-aware credential
    // handling. A Range request here could return only a prefix of a sync file.
    private val fileHttp = http.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
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

    override fun list(relativePath: String): List<DavEntry> {
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
            return parseMultiStatus(readBounded(it.body.byteStream(), LIST_LIMIT).toString(Charsets.UTF_8), relativePath)
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
            .apply { setEntityResolver { _, _ -> throw WebDavException("WebDAV 列表不允许外部 XML 实体") } }
            .parse(xml.byteInputStream(StandardCharsets.UTF_8))
        if (doc.documentElement.localName != "multistatus" || doc.documentElement.namespaceURI != "DAV:") {
            throw WebDavException("WebDAV 未返回有效的目录列表")
        }

        val requested = normalise(requestedPath)
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val out = ArrayList<DavEntry>(responses.length)
        for (i in 0 until responses.length) {
            val element = responses.item(i) as? Element ?: continue
            val href = element.childText("href")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw WebDavException("WebDAV 目录项缺少路径")
            val relative = hrefToRelative(href)
            element.childText("status")?.let { status ->
                if (!status.contains(" 200")) throw WebDavException("WebDAV 目录项读取失败: $relative")
            }

            val propstats = element.getElementsByTagNameNS("DAV:", "propstat")
            var isDir = false
            var size: Long? = null
            var modified: String? = null
            var etag: String? = null
            var displayName: String? = null
            var hasResourceType = false
            for (p in 0 until propstats.length) {
                val propstat = propstats.item(p) as? Element ?: continue
                val status = propstat.childText("status").orEmpty()
                if (!status.contains(" 200")) continue
                val prop = propstat.firstChild("prop") ?: continue
                prop.firstChild("resourcetype")?.let { type ->
                    hasResourceType = true
                    if (type.firstChild("collection") != null) isDir = true
                }
                prop.childText("getcontentlength")?.trim()?.toLongOrNull()?.let { size = it }
                prop.childText("getlastmodified")?.let { modified = it }
                prop.childText("getetag")?.let { etag = it.trim('"') }
                prop.childText("displayname")?.takeIf { it.isNotBlank() }?.let { displayName = it }
            }
            // Failed child properties do not establish whether it is a file
            // or directory. Treating this as a listing would let scans prune it.
            if (!hasResourceType) throw WebDavException("WebDAV 未能读取目录项类型: $relative")
            if (normalise(relative) == requested) continue
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
        val decoded = decodePath(path)
        val decodedRoot = decodePath(rootPath)
        return if (decodedRoot.isNotEmpty() && (decoded == decodedRoot || decoded.startsWith("$decodedRoot/"))) {
            decoded.removePrefix(decodedRoot)
        } else {
            decoded
        }
    }

    // URLDecoder uses form semantics, where '+' means space; a DAV href is a
    // URI path, so protect literal plus signs before decoding percent escapes.
    private fun decodePath(path: String): String =
        URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun normalise(path: String) = "/" + path.trim('/')

    /** File size via `HEAD`, or null when the server does not answer with one. */
    fun size(relativePath: String): Long? {
        val request = request(absoluteUrl(relativePath)).head().build()
        val response = runCatching { fileHttp.newCall(request).execute() }.getOrNull() ?: return null
        return response.use {
            if (it.isSuccessful) it.header("Content-Length")?.toLongOrNull()?.takeIf { size -> size >= 0 } else null
        }
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
        return response.use {
            if (it.code in REDIRECT_CODES) it.header("Location")?.let(it.request.url::resolve)?.toString() else null
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
        require(start >= 0 && (end == null || end >= start)) { "字节区间无效" }
        val request = request(url, useAuth)
            .get()
            .header("Range", if (end == null) "bytes=$start-" else "bytes=$start-$end")
            .build()

        val response = fileHttp.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            runCatching { response.close() }
            throw WebDavException("读取字节区间失败: HTTP $code", code)
        }
        val range = response.header("Content-Range")?.let(CONTENT_RANGE::matchEntire)
        val rangeStart = range?.groupValues?.get(1)?.toLongOrNull()
        val rangeEnd = range?.groupValues?.get(2)?.toLongOrNull()
        val rangeTotal = range?.groupValues?.get(3)?.toLongOrNull()
        val invalidPartial = response.code == 206 &&
            (rangeStart != start || rangeEnd == null || rangeEnd < start ||
                (rangeTotal != null && rangeEnd >= rangeTotal))
        if ((response.code != 200 && response.code != 206) ||
            (response.code == 200 && start > 0) || invalidPartial) {
            response.close()
            throw WebDavException("服务器未返回请求的字节区间", response.code)
        }
        val totalSize = if (response.code == 206) rangeTotal else response.body.contentLength().takeIf { it >= 0 }
        return RangeStream(response.body.byteStream(), totalSize, response.code == 206, response)
    }

    fun readFully(relativePath: String, limit: Long = 4L * 1024 * 1024): ByteArray {
        val result = read(relativePath, limit)
        return result.bytes ?: throw WebDavException(result.error ?: "文件不存在: $relativePath")
    }

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
                val detail = runCatching { it.body.byteStream().readUpTo(800).toString(Charsets.UTF_8).take(200) }.getOrDefault("")
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
        require(limit in 1 until Int.MAX_VALUE.toLong()) { "读取上限无效" }
        return runCatching {
            val request = request(absoluteUrl(relativePath)).get().build()
            fileHttp.newCall(request).execute().use {
                // A CDN 404 can mean an expired signed URL, not a missing DAV
                // file. Only the original endpoint may establish absence.
                if ((it.code == 404 || it.code == 410) && it.priorResponse == null) {
                    return@use ReadResult(missing = true)
                }
                if (it.code != 200) return@use ReadResult(error = "HTTP ${it.code}")
                if (it.body.contentLength() > limit) throw WebDavException("文件超过读取上限 ($limit 字节)")
                ReadResult(bytes = readBounded(it.body.byteStream(), limit))
            }
        }.getOrElse { ReadResult(error = it.message ?: it::class.simpleName) }
    }

    /** Reads at most limit + 1 bytes; never hands a truncated file to a caller. */
    private fun readBounded(stream: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), limit - total + 1).toInt())
            if (count < 0) return output.toByteArray()
            total += count
            if (total > limit) throw WebDavException("文件超过读取上限 ($limit 字节)")
            output.write(buffer, 0, count)
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
        java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8.name())
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
        const val LIST_LIMIT = 32L * 1024 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
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
