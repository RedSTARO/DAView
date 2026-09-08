package com.daview.app.platform

import com.daview.server.ServerContext
import com.daview.server.config.ConfigStore

/**
 * The library, running inside the app. See the Android counterpart: same idea,
 * with JDBC underneath and the data directory the app has always used.
 *
 * One per process, for the same reason — a window can be recreated.
 */
private var instance: ServerContext? = null

@Synchronized
fun createCoreContext(): ServerContext =
    instance ?: ServerContext(ConfigStore.defaultDataDir()).also { instance = it }
