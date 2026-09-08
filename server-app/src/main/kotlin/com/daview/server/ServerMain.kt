package com.daview.server

import com.daview.server.config.ConfigStore
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The standalone server. All of its behaviour lives in `:server`; this module
 * exists only because the Gradle `application` plugin needs a plain JVM module
 * to hang `installDist` off, and `:server` is now multiplatform.
 */
fun main(args: Array<String>) {
    val dataDir = args.firstOrNull()?.let { Path.of(it) } ?: ConfigStore.defaultDataDir()
    val context = ServerContext(dataDir)
    val config = context.config

    val log = LoggerFactory.getLogger("DAView")
    log.info("数据目录: {}", dataDir.toAbsolutePath())
    log.info("访问令牌: {}", config.accessToken)
    log.info("Web 客户端: http://127.0.0.1:{}/?token={}", config.port, config.accessToken)

    Runtime.getRuntime().addShutdownHook(Thread { context.close() })

    startServer(context).start(wait = true)
}
