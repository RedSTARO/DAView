package com.daview.server.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/** One downloadable package in an [UpdateManifest]. */
@Serializable
data class UpdateAsset(
    val url: String,
    val name: String? = null,
    /** Hex SHA-256 of the file. Checked after the download when present. */
    val sha256: String? = null,
    val size: Long? = null
)

/**
 * What the release workflow publishes with every release: the version and a
 * package per platform. It is a small JSON file on GitHub's raw CDN, so finding
 * out whether there is a newer build costs one GET of a few hundred bytes.
 */
@Serializable
data class UpdateManifest(
    /** The human label — the tag, `v1.2.0`. Shown, never compared. */
    val version: String,
    /** MAJOR.MINOR.PATCH as the installers carry it. This is what is compared. */
    val packageVersion: String,
    val publishedAt: String? = null,
    /** Where a person can read about the release and fetch it by hand. */
    val pageUrl: String? = null,
    val notes: String? = null,
    /** Keyed by platform: `android`, `windows`, `linux`, `macos`. */
    val assets: Map<String, UpdateAsset> = emptyMap()
)

/** MAJOR.MINOR.PATCH, compared field by field, the way Windows Installer compares it. */
data class PackageVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<PackageVersion> {
    override fun compareTo(other: PackageVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(text: String): PackageVersion? {
            val parts = text.trim().removePrefix("v").split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            return PackageVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

/**
 * Finds and fetches a newer build of the app.
 *
 * Deliberately knows nothing about installing: that differs per platform and
 * belongs to the app. This reads the manifest, says whether it is newer than
 * the running build, and puts the package on disk with its digest checked.
 */
class UpdateService(dataDir: Path) {
    private val log = LoggerFactory.getLogger(UpdateService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val http: OkHttpClient = OkHttpClient.Builder()
        // GitHub answers a release download with a redirect to its object store.
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Where packages land. Under the data directory on purpose: on Android that
     * is inside the app's own files, which is what the manifest's FileProvider
     * is allowed to hand to the package installer.
     */
    val directory: Path = dataDir.resolve("updates")

    fun fetchManifest(url: String): UpdateManifest {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            // The raw CDN caches for a few minutes; a check a person asked for
            // should not be answered from a copy it took just before the release.
            .header("Cache-Control", "no-cache")
            .build()
        val body = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("读取更新清单失败：HTTP ${response.code}")
                response.body.string()
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("读取更新清单失败：${e.message}", e)
        }
        return runCatching { json.decodeFromString(UpdateManifest.serializer(), body) }
            .getOrElse { throw IOException("更新清单无法解析：${it.message}") }
    }

    /**
     * True when [manifest] carries a package version above [current]. A version
     * that cannot be read on either side is never an update: offering one on a
     * guess is how a build gets replaced by an older one.
     */
    fun isNewer(manifest: UpdateManifest, current: String): Boolean {
        val theirs = PackageVersion.parse(manifest.packageVersion) ?: return false
        val ours = PackageVersion.parse(current) ?: return false
        return theirs > ours
    }

    /**
     * Downloads [asset] into [directory] as [fileName], checking its SHA-256
     * when the manifest gives one.
     *
     * Written to a `.part` first and renamed at the end, so a file under the
     * final name is always a whole one: a download cut off half-way must not be
     * handed to an installer. A whole file already there with the right digest
     * is not fetched again.
     */
    fun download(
        asset: UpdateAsset,
        fileName: String,
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> }
    ): Path {
        directory.createDirectories()
        val target = directory.resolve(fileName)
        val part = directory.resolve("$fileName.part")

        if (target.exists() && asset.sha256 != null && sha256(target).equals(asset.sha256, ignoreCase = true)) {
            val size = Files.size(target)
            onProgress(size, size)
            return target
        }

        val request = Request.Builder().url(asset.url).build()
        val received: Long
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: asset.size
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                var lastReported = 0L
                body.byteStream().use { input ->
                    Files.newOutputStream(part).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            count += read
                            if (count - lastReported >= PROGRESS_STEP) {
                                lastReported = count
                                onProgress(count, total)
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (asset.sha256 != null && !actual.equals(asset.sha256, ignoreCase = true)) {
                    throw IOException("下载的文件校验失败（SHA-256 不符）")
                }
                if (asset.size != null && count != asset.size) {
                    throw IOException("下载不完整：收到 $count 字节，应为 ${asset.size}")
                }
                received = count
            }
        } catch (e: IOException) {
            part.deleteIfExists()
            throw e
        } catch (e: Exception) {
            part.deleteIfExists()
            throw IOException("下载失败：${e.message}", e)
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        onProgress(received, received)
        log.info("更新包已下载: {} ({} 字节)", target, received)
        return target
    }

    /** Drops every package except [keep]. They are large and useful exactly once. */
    fun clean(keep: Path? = null) {
        if (!directory.exists()) return
        runCatching {
            directory.listDirectoryEntries().forEach { entry ->
                if (keep == null || entry != keep) entry.deleteIfExists()
            }
        }.onFailure { log.warn("清理更新目录失败: {}", it.message) }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Progress is reported every this many bytes, not every read. */
        private const val PROGRESS_STEP = 512L * 1024

        /**
         * Puts a mirror in front of [url], for networks where GitHub itself is
         * out of reach: `https://mirror/` + `https://github.com/...`, which is
         * the shape the common proxies take.
         */
        fun mirrored(url: String, mirror: String): String {
            val prefix = mirror.trim()
            if (prefix.isEmpty()) return url
            return prefix.trimEnd('/') + "/" + url
        }
    }
}
