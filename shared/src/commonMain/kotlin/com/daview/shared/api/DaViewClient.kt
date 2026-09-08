package com.daview.shared.api

import com.daview.shared.model.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class DaViewApiException(
    val status: Int,
    val error: String,
    val detail: String? = null
) : RuntimeException(if (detail == null) error else "$error: $detail")

val DaViewJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

/**
 * Thin typed wrapper over the DAView REST API. Shared by every client target;
 * the Ktor engine is picked up from whichever engine artifact the platform ships.
 */
class DaViewClient(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    engineClient: HttpClient? = null
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    private val http: HttpClient = (engineClient ?: HttpClient()).config {
        expectSuccess = false
        install(ContentNegotiation) { json(DaViewJson) }
        defaultRequest {
            contentType(ContentType.Application.Json)
            tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }
        HttpResponseValidator {
            validateResponse { response ->
                if (response.status.value >= 400) {
                    val raw = runCatching { response.bodyAsText() }.getOrDefault("")
                    val parsed = runCatching { DaViewJson.decodeFromString(ApiError.serializer(), raw) }.getOrNull()
                    throw DaViewApiException(
                        status = response.status.value,
                        error = parsed?.error ?: response.status.description.ifBlank { "request failed" },
                        detail = parsed?.detail ?: raw.take(400).ifBlank { null }
                    )
                }
            }
        }
    }

    private fun url(path: String) = "$baseUrl$path"

    /** Absolute URL for an image/stream endpoint, with the auth token inline for `<img>`/players. */
    fun assetUrl(path: String): String {
        val token = tokenProvider()
        val sep = if (path.contains('?')) "&" else "?"
        return if (token.isNullOrBlank()) url(path) else url(path) + sep + "token=" + token
    }

    suspend fun info(): ServerInfoDto = http.get(url("/api/info")).body()

    suspend fun settings(): ServerSettingsDto = http.get(url("/api/settings")).body()

    suspend fun updateSettings(settings: ServerSettingsDto): ServerSettingsDto =
        http.put(url("/api/settings")) { setBody(settings) }.body()

    suspend fun testStorage(settings: StorageSettingsDto): List<WebDavEntryDto> =
        http.post(url("/api/storage/test")) { setBody(settings) }.body()

    suspend fun browseStorage(path: String): List<WebDavEntryDto> =
        http.get(url("/api/storage/browse")) { parameter("path", path) }.body()

    suspend fun libraries(): List<LibraryDto> = http.get(url("/api/libraries")).body()

    suspend fun createLibrary(library: LibraryDto): LibraryDto =
        http.post(url("/api/libraries")) { setBody(library) }.body()

    suspend fun updateLibrary(library: LibraryDto): LibraryDto =
        http.put(url("/api/libraries/${library.id}")) { setBody(library) }.body()

    suspend fun deleteLibrary(id: String) {
        http.delete(url("/api/libraries/$id"))
    }

    suspend fun scanLibrary(id: String, refreshMetadata: Boolean = false): ScanProgressDto =
        http.post(url("/api/libraries/$id/scan")) { parameter("refresh", refreshMetadata) }.body()

    suspend fun scanStatus(): List<ScanProgressDto> = http.get(url("/api/scan/status")).body()

    suspend fun items(
        libraryId: String? = null,
        parentId: String? = null,
        kind: ItemKind? = null,
        search: String? = null,
        sort: String = "sortName",
        limit: Int = 100,
        offset: Int = 0
    ): ItemPage = http.get(url("/api/items")) {
        libraryId?.let { parameter("libraryId", it) }
        parentId?.let { parameter("parentId", it) }
        kind?.let { parameter("kind", it.name.lowercase()) }
        search?.let { parameter("search", it) }
        parameter("sort", sort)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    suspend fun item(id: String): MediaItemDto = http.get(url("/api/items/$id")).body()

    suspend fun children(id: String): List<MediaItemDto> = http.get(url("/api/items/$id/children")).body()

    /** What the manual identify dialog needs: parsed folder title and usable sources. */
    suspend fun identifyContext(itemId: String): IdentifyContextDto =
        http.get(url("/api/items/$itemId/identify")).body()

    /** Unfiltered hits from one provider, for the user to pick from. */
    suspend fun identifySearch(
        itemId: String,
        provider: MetadataProvider,
        query: String,
        year: Int? = null
    ): List<ScrapeCandidateDto> = http.get(url("/api/items/$itemId/identify/search")) {
        parameter("provider", provider.name.lowercase())
        parameter("query", query)
        year?.let { parameter("year", it) }
    }.body()

    /** Pins a provider id on an item and re-scrapes it from that entry. */
    suspend fun identify(itemId: String, provider: MetadataProvider, providerId: String): MediaItemDto =
        http.post(url("/api/items/$itemId/identify")) {
            setBody(IdentifyRequest(provider = provider, providerId = providerId))
        }.body()

    suspend fun resume(limit: Int = 20): List<MediaItemDto> =
        http.get(url("/api/home/resume")) { parameter("limit", limit) }.body()

    suspend fun nextUp(limit: Int = 20): List<MediaItemDto> =
        http.get(url("/api/home/nextup")) { parameter("limit", limit) }.body()

    suspend fun latest(libraryId: String? = null, limit: Int = 20): List<MediaItemDto> =
        http.get(url("/api/home/latest")) {
            libraryId?.let { parameter("libraryId", it) }
            parameter("limit", limit)
        }.body()

    suspend fun setFavorite(itemId: String, favorite: Boolean): UserDataDto =
        http.post(url("/api/items/$itemId/favorite")) { parameter("value", favorite) }.body()

    suspend fun setPlayed(itemId: String, played: Boolean): UserDataDto =
        http.post(url("/api/items/$itemId/played")) { parameter("value", played) }.body()

    suspend fun startPlayback(request: PlaybackStartRequest): PlaybackInfoDto =
        http.post(url("/api/playback/start")) { setBody(request) }.body()

    suspend fun reportProgress(request: PlaybackProgressRequest) {
        http.post(url("/api/playback/progress")) { setBody(request) }
    }

    suspend fun stopPlayback(request: PlaybackStopRequest) {
        http.post(url("/api/playback/stop")) { setBody(request) }
    }

    suspend fun sessions(): List<SessionStateDto> = http.get(url("/api/playback/sessions")).body()

    /** Raw bytes from an endpoint, used for the web client's font download. */
    suspend fun fetchBytes(path: String): ByteArray? = runCatching {
        val response = http.get(url(path))
        if (response.status.value in 200..299) response.body<ByteArray>() else null
    }.getOrNull()

    /** Returns true when the base URL answers and the token is accepted. */
    suspend fun ping(): Boolean = runCatching {
        val response = http.get(url("/api/info"))
        response.status == HttpStatusCode.OK
    }.getOrDefault(false)

    fun close() = http.close()
}
