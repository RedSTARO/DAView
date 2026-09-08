package com.daview.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.daview.server.ServerContext
import com.daview.server.config.ConfigStore
import com.daview.server.startServer
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Desktop entry point.
 *
 * By default it runs the DAView server in-process, so the desktop app is
 * self-contained: one process scans the WebDAV share, keeps the catalogue and
 * serves the same REST API that the Android and web clients talk to. Pass
 * `--remote <url>` (or set `DAVIEW_REMOTE`) to attach to a server elsewhere.
 */
fun main(args: Array<String>) {
    val remote = argValue(args, "--remote") ?: System.getenv("DAVIEW_REMOTE")
    if (remote != null) {
        System.setProperty("daview.serverUrl", remote.trimEnd('/'))
        (argValue(args, "--token") ?: System.getenv("DAVIEW_REMOTE_TOKEN"))
            ?.let { System.setProperty("daview.token", it) }
    } else {
        startEmbeddedServer(argValue(args, "--data")?.let { Path.of(it) })
    }

    application {
        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(0)
            },
            title = "DAView",
            state = rememberWindowState(size = DpSize(1360.dp, 900.dp))
        ) {
            App()
        }
    }
}

private fun argValue(args: Array<String>, name: String): String? {
    val index = args.indexOf(name)
    return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}

private fun startEmbeddedServer(dataDir: Path?) {
    val context = ServerContext(dataDir ?: ConfigStore.defaultDataDir())
    val config = context.config
    System.setProperty("daview.serverUrl", "http://127.0.0.1:${config.port}")
    System.setProperty("daview.token", config.accessToken)
    thread(isDaemon = true, name = "daview-embedded-server") {
        runCatching { startServer(context).start(wait = true) }
            .onFailure { println("内嵌服务器启动失败: ${it.message}") }
    }
    Runtime.getRuntime().addShutdownHook(Thread { context.close() })
}
