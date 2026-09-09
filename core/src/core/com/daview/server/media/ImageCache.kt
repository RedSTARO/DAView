package com.daview.server.media

import org.slf4j.LoggerFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Artwork is fetched once and kept on disk, so clients never talk to TMDB/TVDB
 * directly. That keeps API keys on the server and works on networks where the
 * image CDNs are unreachable from the device but not from the server.
 */
class ImageCache(
    dataDir: Path,
    /**
     * Reads a path on the share. Artwork found next to the video is addressed
     * as `dav:<path>` and cannot be fetched with a plain GET — the share wants
     * credentials, and those live with the WebDAV client.
     */
    private val readFromStorage: ((String) -> ByteArray?)? = null
) {
    private val log = LoggerFactory.getLogger(ImageCache::class.java)
    private val dir: Path = dataDir.resolve("cache").resolve("images").also { it.createDirectories() }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        // Five seconds, not fifteen: a poster that has not started arriving by
        // then is not going to save the grid, and every scroll back into view
        // used to pay the full timeout again.
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * URLs that failed recently, so a grid full of unreachable posters does not
     * re-attempt every one of them each time it scrolls back into view. Cleared
     * by time, so a network that comes back is picked up.
     */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()

    data class Entry(val file: Path, val contentType: String)

    /** Everything the cache is holding, for the setting that offers to empty it. */
    fun sizeBytes(): Long = runCatching {
        Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    }.getOrDefault(0L)

    /**
     * Drops every cached image. They are re-fetched on demand, so nothing is
     * lost — the directory only ever grew, and on Android it sits under
     * `filesDir`, where the system's own "clear cache" cannot reach it.
     */
    fun clear() {
        failures.clear()
        runCatching {
            Files.list(dir).use { paths -> paths.forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }

    fun get(remoteUrl: String): Entry? {
        val key = sha1(remoteUrl)
        val extension = remoteUrl.substringAfterLast('.', "jpg")
            .substringBefore('?')
            .lowercase()
            .takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "jpg"
        val file = dir.resolve("$key.$extension")
        if (file.exists() && Files.size(file) > 0) return Entry(file, contentType(extension))

        val failedAt = failures[remoteUrl]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_TTL_MS) return null

        if (remoteUrl.startsWith(STORAGE_SCHEME)) {
            val bytes = readFromStorage?.invoke(remoteUrl.removePrefix(STORAGE_SCHEME))
            if (bytes == null || bytes.isEmpty()) {
                failures[remoteUrl] = System.currentTimeMillis()
                return null
            }
            return write(file, bytes, extension)
        }

        val request = Request.Builder()
            .url(remoteUrl)
            .get()
            .header("User-Agent", "DAView/1.0")
            .build()
        val response = runCatching { http.newCall(request).execute() }
            .getOrElse {
                // The key is in the query string for TMDB, so the whole URL
                // cannot go in a log line someone might paste into an issue.
                log.warn("下载图片失败 {}: {}", redact(remoteUrl), it.message)
                failures[remoteUrl] = System.currentTimeMillis()
                return null
            }
        val bytes = response.use { if (it.isSuccessful) it.body.bytes() else ByteArray(0) }
        if (bytes.isEmpty()) {
            failures[remoteUrl] = System.currentTimeMillis()
            return null
        }
        return write(file, bytes, extension)
    }

    private fun write(file: Path, bytes: ByteArray, extension: String): Entry {
        val tmp = Path.of("$file.tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return Entry(file, contentType(extension))
    }

    private fun redact(url: String) = url.replace(Regex("(api_key|token)=[^&]*"), "$1=***")

    private fun contentType(extension: String) = when (extension) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        else -> "image/jpeg"
    }

    companion object {
        /** How long a failed fetch is remembered before it is worth another try. */
        const val FAILURE_TTL_MS = 5 * 60 * 1000L

        /** Marks an image that lives on the share rather than at an http URL. */
        const val STORAGE_SCHEME = "dav:"
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
