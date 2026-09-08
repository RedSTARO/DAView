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
class ImageCache(dataDir: Path) {
    private val log = LoggerFactory.getLogger(ImageCache::class.java)
    private val dir: Path = dataDir.resolve("cache").resolve("images").also { it.createDirectories() }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Entry(val file: Path, val contentType: String)

    fun get(remoteUrl: String): Entry? {
        val key = sha1(remoteUrl)
        val extension = remoteUrl.substringAfterLast('.', "jpg")
            .substringBefore('?')
            .lowercase()
            .takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "jpg"
        val file = dir.resolve("$key.$extension")
        if (file.exists() && Files.size(file) > 0) return Entry(file, contentType(extension))

        val request = Request.Builder()
            .url(remoteUrl)
            .get()
            .header("User-Agent", "DAView/1.0")
            .build()
        val response = runCatching { http.newCall(request).execute() }
            .getOrElse {
                log.warn("下载图片失败 {}: {}", remoteUrl, it.message)
                return null
            }
        val bytes = response.use { if (it.isSuccessful) it.body.bytes() else ByteArray(0) }
        if (bytes.isEmpty()) return null

        val tmp = dir.resolve("$key.$extension.tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return Entry(file, contentType(extension))
    }

    private fun contentType(extension: String) = when (extension) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        else -> "image/jpeg"
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
