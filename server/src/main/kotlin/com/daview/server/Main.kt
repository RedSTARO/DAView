package com.daview.server

import com.daview.server.api.apiRoutes
import com.daview.server.config.ConfigStore
import com.daview.shared.model.ApiError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import java.nio.file.Path

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

/**
 * Builds the HTTP server. The desktop app calls this directly so it can run a
 * server in-process without spawning a second JVM.
 */
fun startServer(context: ServerContext): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val config = context.config
    return embeddedServer(Netty, port = config.port, host = config.host) {
        module(context)
    }
}

fun Application.module(context: ServerContext) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        })
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> !call.request.local.uri.startsWith("/api/stream") }
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Range)
        exposeHeader(HttpHeaders.ContentRange)
        exposeHeader(HttpHeaders.AcceptRanges)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowCredentials = true
    }
    install(StatusPages) {
        exception<com.daview.server.storage.WebDavException> { call, cause ->
            call.respond(HttpStatusCode.BadGateway, ApiError("WebDAV 错误", cause.message))
        }
        exception<Throwable> { call, cause ->
            LoggerFactory.getLogger("DAView").error("未处理的异常", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("服务器错误", cause.message))
        }
    }

    routing {
        apiRoutes(context)

        get("/health") { call.respond(mapOf("status" to "ok")) }

        // The compiled web client, when it has been built.
        val webDir = System.getenv("DAVIEW_WEB_DIR")?.let { File(it) }
            ?: File("composeApp/build/dist/wasmJs/productionExecutable")
        if (webDir.isDirectory) {
            staticFiles("/", webDir) { default("index.html") }
            LoggerFactory.getLogger("DAView").info("Web 客户端目录: {}", webDir.absolutePath)
        }
    }
}
