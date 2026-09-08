package com.daview.server

import com.daview.server.api.apiRoutes
import com.daview.shared.model.ApiError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
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

/**
 * Builds the HTTP server. The desktop and Android apps call this directly so
 * they run a server in-process rather than depending on a separate one.
 *
 * CIO rather than Netty: it is plain coroutines, which is what makes the same
 * server usable on Android.
 */
fun startServer(context: ServerContext): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    val config = context.config
    return embeddedServer(CIO, port = config.port, host = config.host) {
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
        // Byte-range requests would drown everything else; failures still show,
        // because the WebDAV handler above logs them.
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
        // The facade reports failures in its own terms; only the mapping onto
        // status codes is about HTTP, so it lives here rather than in every
        // handler.
        exception<com.daview.server.api.MediaFacade.FacadeException> { call, cause ->
            val status = when (cause.failure) {
                com.daview.server.api.MediaFacade.Failure.NOT_FOUND -> HttpStatusCode.NotFound
                com.daview.server.api.MediaFacade.Failure.INVALID -> HttpStatusCode.BadRequest
                com.daview.server.api.MediaFacade.Failure.UPSTREAM -> HttpStatusCode.BadGateway
            }
            call.respond(status, ApiError(cause.message, cause.detail))
        }
        exception<com.daview.server.storage.WebDavException> { call, cause ->
            // Logged, not just answered: a bare 502 at the player end says
            // nothing about which request to the storage failed or why.
            LoggerFactory.getLogger("DAView")
                .warn("WebDAV 错误 {} {}: {}", call.request.local.method.value, call.request.local.uri, cause.message, cause)
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
    }
}

