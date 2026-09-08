package com.daview.app.platform

import com.daview.server.ServerContext
import com.daview.server.db.AndroidSqlDatabase
import java.io.File

/**
 * The library, running inside the app.
 *
 * Not a server: nothing listens, nothing is addressed, the UI calls into it the
 * way it calls anything else. The phone scans the share, scrapes, keeps its own
 * database and agrees with the other devices through the sync file — none of
 * which needs a port.
 */
fun createCoreContext(): ServerContext {
    val context = AndroidContextHolder.context
    val dataDir = File(context.filesDir, "daview").apply { mkdirs() }
    return ServerContext(dataDir.toPath(), AndroidSqlDatabase(context, dataDir))
}
