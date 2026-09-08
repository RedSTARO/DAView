package com.daview.app

import android.content.Context
import com.daview.server.ServerContext
import com.daview.server.db.AndroidSqlDatabase
import com.daview.server.startServer
import java.io.File
import kotlin.concurrent.thread

/**
 * The core, running inside the app.
 *
 * The phone scans the WebDAV share, scrapes, keeps the database and serves the
 * bytes itself; there is no separate backend to point at. It is the same code
 * the desktop build runs in-process, with Android's SQLite underneath instead
 * of JDBC.
 *
 * It still speaks HTTP to itself over the loopback interface. That keeps one
 * client implementation for all three platforms, and it is what lets the phone
 * hand a URL to an external player or an image loader — neither of which can be
 * given a Kotlin object.
 */
object EmbeddedServer {

    @Volatile
    private var started = false

    @Volatile
    var url: String? = null
        private set

    @Volatile
    var token: String? = null
        private set

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true

        val dataDir = File(context.filesDir, "daview").apply { mkdirs() }
        val serverContext = ServerContext(
            dataDir = dataDir.toPath(),
            sql = AndroidSqlDatabase(context, dataDir)
        )
        val config = serverContext.config
        url = "http://127.0.0.1:${config.port}"
        token = config.accessToken

        thread(isDaemon = true, name = "daview-embedded-server") {
            runCatching { startServer(serverContext).start(wait = true) }
                .onFailure { android.util.Log.e("DAView", "内嵌服务端启动失败", it) }
        }
    }
}
