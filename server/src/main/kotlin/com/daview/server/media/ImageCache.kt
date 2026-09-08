package com.daview.server.media

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
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

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
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

        val request = HttpRequest.newBuilder(URI.create(remoteUrl))
            .GET()
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "DAView/1.0")
            .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofByteArray()) }
            .getOrElse {
                log.warn("下载图片失败 {}: {}", remoteUrl, it.message)
                return null
            }
        if (response.statusCode() !in 200..299 || response.body().isEmpty()) return null

        val tmp = dir.resolve("$key.$extension.tmp")
        Files.write(tmp, response.body())
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
