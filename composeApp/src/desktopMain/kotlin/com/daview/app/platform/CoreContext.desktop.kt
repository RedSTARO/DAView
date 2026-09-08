package com.daview.app.platform

import com.daview.server.ServerContext
import com.daview.server.config.ConfigStore
import java.nio.file.Path

/**
 * The library, running inside the app. See the Android counterpart: same idea,
 * with JDBC underneath and the data directory the standalone build would use,
 * so a desktop install and a headless one can share a folder.
 */
fun createCoreContext(dataDir: Path? = null): ServerContext =
    ServerContext(dataDir ?: ConfigStore.defaultDataDir())
