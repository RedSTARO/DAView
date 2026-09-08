package com.daview.server.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class StorageConfig(
    val url: String = "",
    val username: String = "",
    val password: String = ""
) {
    val configured: Boolean get() = url.isNotBlank()
}

@Serializable
data class ScraperConfig(
    val tmdbApiKey: String = "",
    val tvdbApiKey: String = "",
    val bangumiToken: String = "",
    val language: String = "zh-CN",
    val tmdbImageBase: String = "https://image.tmdb.org/t/p"
)

/**
 * Cross-device sync through the share itself: the watch state is written to one
 * file on the WebDAV target and read back by the other devices.
 */
@Serializable
data class SyncConfig(
    val enabled: Boolean = false,
    /** Path of the sync file relative to the WebDAV root. The app owns this file. */
    val remotePath: String = "/daview-sync.json",
    /** Floor on how often an automatic upload may run. */
    val minIntervalMinutes: Int = 10,
    val lastUploadAt: Long? = null,
    val lastPullAt: Long? = null,
    val lastError: String? = null
)

@Serializable
data class AppConfig(
    val serverName: String = "DAView",
    val port: Int = 8096,
    val host: String = "0.0.0.0",
    val accessToken: String = "",
    val storage: StorageConfig = StorageConfig(),
    val scraper: ScraperConfig = ScraperConfig(),
    /**
     * Stream external players through the server so playback position can be
     * derived from the byte offsets they request. Off means a plain redirect,
     * which is faster but reports no progress.
     */
    val trackExternalPlayers: Boolean = true,
    /**
     * Seconds without a byte request before an external session is considered
     * finished. Players buffer aggressively — PotPlayer can go a couple of
     * minutes between reads — so this has to be generous.
     */
    val externalSessionIdleTimeoutSec: Int = 300,
    val sync: SyncConfig = SyncConfig()
)

/**
 * Config lives in a single JSON file next to the database. Values may be
 * overridden by environment variables so the secrets never have to be written
 * to disk in a container.
 */
class ConfigStore(val dataDir: Path) {
    private val file: Path = dataDir.resolve("config.json")
    private val lock = ReentrantLock()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cached: AppConfig = load()

    val current: AppConfig get() = cached

    private fun load(): AppConfig {
        dataDir.createDirectories()
        val base = if (file.exists()) {
            runCatching { json.decodeFromString(AppConfig.serializer(), file.readText()) }
                .getOrElse { AppConfig() }
        } else {
            AppConfig()
        }
        val withEnv = base.copy(
            port = env("DAVIEW_PORT")?.toIntOrNull() ?: base.port,
            host = env("DAVIEW_HOST") ?: base.host,
            accessToken = env("DAVIEW_TOKEN") ?: base.accessToken.ifBlank { generateToken() },
            storage = base.storage.copy(
                url = env("DAVIEW_WEBDAV_URL") ?: base.storage.url,
                username = env("DAVIEW_WEBDAV_USER") ?: base.storage.username,
                password = env("DAVIEW_WEBDAV_PASS") ?: base.storage.password
            ),
            scraper = base.scraper.copy(
                tmdbApiKey = env("DAVIEW_TMDB_KEY") ?: base.scraper.tmdbApiKey,
                tvdbApiKey = env("DAVIEW_TVDB_KEY") ?: base.scraper.tvdbApiKey,
                bangumiToken = env("DAVIEW_BANGUMI_TOKEN") ?: base.scraper.bangumiToken
            )
        )
        if (!file.exists() || withEnv.accessToken != base.accessToken) persist(withEnv)
        return withEnv
    }

    fun update(transform: (AppConfig) -> AppConfig): AppConfig = lock.withLock {
        val next = transform(cached)
        persist(next)
        cached = next
        next
    }

    private fun persist(config: AppConfig) {
        dataDir.createDirectories()
        val tmp = dataDir.resolve("config.json.tmp")
        tmp.writeText(json.encodeToString(AppConfig.serializer(), config))
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    companion object {
        fun generateToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun defaultDataDir(): Path {
            System.getenv("DAVIEW_DATA")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
            val local = System.getenv("LOCALAPPDATA")
            return if (local != null) Path.of(local, "DAView") else Path.of(System.getProperty("user.home"), ".daview")
        }
    }
}
