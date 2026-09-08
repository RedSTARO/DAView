package com.daview.server

import com.daview.server.config.AppConfig
import com.daview.server.config.ConfigStore
import com.daview.server.db.Database
import com.daview.server.db.Repository
import com.daview.server.db.SqlDatabase
import com.daview.server.media.ImageCache
import com.daview.server.media.PlaybackService
import com.daview.server.media.ScanService
import com.daview.server.media.StreamService
import com.daview.server.scraper.MetadataService
import com.daview.server.storage.WebDavClient
import java.nio.file.Path

const val DAVIEW_VERSION = "1.0.0"

/**
 * Wires the server's singletons together and keeps them in sync with the config.
 *
 * The SQL driver is handed in rather than built here: it is the one piece that
 * differs between the desktop, where it is JDBC, and Android, where it is the
 * platform's own SQLite.
 */
class ServerContext(dataDir: Path, sql: SqlDatabase) : AutoCloseable {

    val configStore = ConfigStore(dataDir)
    val database = Database(sql)
    val repository = Repository(database)
    val images = ImageCache(dataDir)
    val metadata = MetadataService(repository)

    @Volatile
    private var dav: WebDavClient? = buildDav(configStore.current)

    val streams = StreamService({ dav }, repository)
    val playback = PlaybackService(repository, streams) { config.externalSessionIdleTimeoutSec }
    val pipe = com.daview.server.media.PlaybackPipe(repository, streams, playback)
    val scans = ScanService(repository, metadata, streams, { dav }, { config })
    val sync = com.daview.server.sync.SyncService(this) { dav }

    /** The one entry point into everything above; see [com.daview.server.api.MediaFacade]. */
    val media = com.daview.server.api.MediaFacade(this)

    val config: AppConfig get() = configStore.current

    fun webdav(): WebDavClient? = dav

    fun updateConfig(transform: (AppConfig) -> AppConfig): AppConfig {
        val previous = configStore.current.storage
        val updated = configStore.update(transform)
        if (updated.storage != previous) dav = buildDav(updated)
        return updated
    }

    private fun buildDav(config: AppConfig): WebDavClient? =
        if (config.storage.configured) WebDavClient(config.storage) else null

    override fun close() {
        pipe.close()
        sync.close()
        database.close()
    }
}
