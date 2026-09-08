package com.daview.server.scraper

import com.daview.server.config.ScraperConfig
import com.daview.server.db.Repository
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.PersonDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class ScrapeCandidate(
    val providerId: String,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterUrl: String?,
    val score: Double = 0.0,
    /** Position in the provider's own relevance ranking, 0 = best. */
    val rank: Int = 0
)

data class ScrapedMetadata(
    val provider: MetadataProvider,
    val providerId: String,
    val name: String,
    val originalName: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val premiereDate: String? = null,
    val runtimeMs: Long? = null,
    val communityRating: Double? = null,
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val people: List<PersonDto> = emptyList(),
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val extraProviderIds: Map<String, String> = emptyMap()
)

data class ScrapedEpisode(
    val season: Int,
    val episode: Int,
    val name: String?,
    val overview: String? = null,
    val airDate: String? = null,
    val stillUrl: String? = null,
    val rating: Double? = null,
    val runtimeMs: Long? = null
)

interface MetadataScraper {
    val provider: MetadataProvider
    fun isConfigured(config: ScraperConfig): Boolean
    fun search(title: String, year: Int?, kind: ItemKind, config: ScraperConfig): List<ScrapeCandidate>

    /**
     * Lookup for the manual identify dialog. Defaults to [search]; a provider
     * overrides it when automatic matching needs a narrower query than a person
     * who has already chosen that provider on purpose.
     */
    fun searchManual(title: String, year: Int?, kind: ItemKind, config: ScraperConfig): List<ScrapeCandidate> =
        search(title, year, kind, config)

    fun details(providerId: String, kind: ItemKind, config: ScraperConfig): ScrapedMetadata?
    fun episodes(providerId: String, config: ScraperConfig): List<ScrapedEpisode> = emptyList()
}

/** Shared JSON-over-HTTP plumbing with an on-disk response cache. */
abstract class HttpScraper(protected val repository: Repository?) {
    protected val log = LoggerFactory.getLogger(javaClass)

    protected val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    protected val json = Json { ignoreUnknownKeys = true; isLenient = true }

    protected fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cacheKey: String? = null,
        cacheMaxAgeMs: Long = 7L * 24 * 3600 * 1000
    ): JsonElementOrNull {
        cacheKey?.let { key ->
            repository?.cacheGet(key, cacheMaxAgeMs)?.let {
                return JsonElementOrNull(runCatching { json.parseToJsonElement(it) }.getOrNull())
            }
        }
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = runCatching { http.newCall(builder.build()).execute() }
            .getOrElse {
                log.warn("请求 {} 失败: {}", url, it.message)
                return JsonElementOrNull(null)
            }
        val body = response.use {
            if (!it.isSuccessful) {
                log.warn("请求 {} 返回 HTTP {}", url, it.code)
                return JsonElementOrNull(null)
            }
            it.body.string()
        }
        cacheKey?.let { repository?.cachePut(it, body) }
        return JsonElementOrNull(runCatching { json.parseToJsonElement(body) }.getOrNull())
    }

    protected fun postJson(
        url: String,
        body: JsonObject,
        headers: Map<String, String> = emptyMap()
    ): JsonElementOrNull {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = runCatching { http.newCall(builder.build()).execute() }
            .getOrElse {
                log.warn("请求 {} 失败: {}", url, it.message)
                return JsonElementOrNull(null)
            }
        return response.use {
            if (!it.isSuccessful) {
                log.warn("请求 {} 返回 HTTP {}", url, it.code)
                JsonElementOrNull(null)
            } else {
                JsonElementOrNull(runCatching { json.parseToJsonElement(it.body.string()) }.getOrNull())
            }
        }
    }

    protected fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    class JsonElementOrNull(val element: kotlinx.serialization.json.JsonElement?) {
        val obj: JsonObject? get() = element as? JsonObject
        val array: JsonArray? get() = element as? JsonArray
    }

    companion object {
        const val USER_AGENT = "DAView/1.0 (+https://github.com/daview)"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// ---------------------------------------------------------------- TMDB

class TmdbScraper(repository: Repository?) : HttpScraper(repository), MetadataScraper {
    override val provider = MetadataProvider.TMDB
    override fun isConfigured(config: ScraperConfig) = config.tmdbApiKey.isNotBlank()

    private fun base(config: ScraperConfig) = "https://api.themoviedb.org/3"

    private fun image(config: ScraperConfig, path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "${config.tmdbImageBase}/$size$it" }

    override fun search(title: String, year: Int?, kind: ItemKind, config: ScraperConfig): List<ScrapeCandidate> {
        val endpoint = if (kind == ItemKind.MOVIE) "movie" else "tv"
        val yearParam = year?.let {
            if (kind == ItemKind.MOVIE) "&year=$it" else "&first_air_date_year=$it"
        }.orEmpty()
        val url = "${base(config)}/search/$endpoint?api_key=${config.tmdbApiKey}" +
            "&query=${encode(title)}&language=${config.language}&include_adult=false$yearParam"
        val results = getJson(url, cacheKey = "tmdb:search:$endpoint:$title:$year:${config.language}")
            .obj?.get("results")?.jsonArray ?: return emptyList()
        return results.mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull null
            val date = item.str(if (kind == ItemKind.MOVIE) "release_date" else "first_air_date")
            ScrapeCandidate(
                rank = index,
                providerId = item.int("id")?.toString() ?: return@mapIndexedNotNull null,
                title = item.str(if (kind == ItemKind.MOVIE) "title" else "name") ?: return@mapIndexedNotNull null,
                originalTitle = item.str(if (kind == ItemKind.MOVIE) "original_title" else "original_name"),
                year = date?.take(4)?.toIntOrNull(),
                overview = item.str("overview"),
                posterUrl = image(config, item.str("poster_path")),
                score = item.dbl("popularity") ?: 0.0
            )
        }
    }

    override fun details(providerId: String, kind: ItemKind, config: ScraperConfig): ScrapedMetadata? {
        val endpoint = if (kind == ItemKind.MOVIE) "movie" else "tv"
        val url = "${base(config)}/$endpoint/$providerId?api_key=${config.tmdbApiKey}" +
            "&language=${config.language}&append_to_response=credits,external_ids,images" +
            "&include_image_language=${config.language.take(2)},en,null"
        val item = getJson(url, cacheKey = "tmdb:details:$endpoint:$providerId:${config.language}").obj ?: return null

        val date = item.str(if (kind == ItemKind.MOVIE) "release_date" else "first_air_date")
        val runtime = if (kind == ItemKind.MOVIE) {
            item.int("runtime")?.let { it * 60_000L }
        } else {
            (item["episode_run_time"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull?.let { it * 60_000L }
        }
        val credits = item["credits"]?.jsonObject
        val cast = credits?.get("cast")?.jsonArray.orEmpty().take(20).mapNotNull { element ->
            val person = element as? JsonObject ?: return@mapNotNull null
            PersonDto(
                name = person.str("name") ?: return@mapNotNull null,
                role = person.str("character"),
                type = "Actor",
                imageUrl = image(config, person.str("profile_path"), "w185")
            )
        }
        val crew = credits?.get("crew")?.jsonArray.orEmpty().mapNotNull { element ->
            val person = element as? JsonObject ?: return@mapNotNull null
            val job = person.str("job") ?: return@mapNotNull null
            if (job !in setOf("Director", "Writer", "Screenplay", "Original Music Composer")) return@mapNotNull null
            PersonDto(
                name = person.str("name") ?: return@mapNotNull null,
                role = job,
                type = if (job == "Director") "Director" else "Writer",
                imageUrl = image(config, person.str("profile_path"), "w185")
            )
        }
        val logo = item["images"]?.jsonObject?.get("logos")?.jsonArray?.firstOrNull()
            ?.jsonObject?.str("file_path")

        return ScrapedMetadata(
            provider = MetadataProvider.TMDB,
            providerId = providerId,
            name = item.str(if (kind == ItemKind.MOVIE) "title" else "name") ?: return null,
            originalName = item.str(if (kind == ItemKind.MOVIE) "original_title" else "original_name"),
            overview = item.str("overview"),
            year = date?.take(4)?.toIntOrNull(),
            premiereDate = date,
            runtimeMs = runtime,
            communityRating = item.dbl("vote_average"),
            genres = item["genres"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.str("name") },
            studios = (item["production_companies"] ?: item["networks"])?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonObject)?.str("name") },
            people = cast + crew,
            posterUrl = image(config, item.str("poster_path")),
            backdropUrl = image(config, item.str("backdrop_path"), "w1280"),
            logoUrl = image(config, logo, "w500"),
            extraProviderIds = buildMap {
                item["external_ids"]?.jsonObject?.str("imdb_id")?.let { put("imdb", it) }
                item["external_ids"]?.jsonObject?.int("tvdb_id")?.let { put("tvdb", it.toString()) }
            }
        )
    }

    override fun episodes(providerId: String, config: ScraperConfig): List<ScrapedEpisode> {
        val series = getJson(
            "${base(config)}/tv/$providerId?api_key=${config.tmdbApiKey}&language=${config.language}",
            cacheKey = "tmdb:details:tv:$providerId:${config.language}"
        ).obj ?: return emptyList()
        val seasons = series["seasons"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.int("season_number") }
        return seasons.flatMap { seasonNumber ->
            val payload = getJson(
                "${base(config)}/tv/$providerId/season/$seasonNumber?api_key=${config.tmdbApiKey}&language=${config.language}",
                cacheKey = "tmdb:season:$providerId:$seasonNumber:${config.language}"
            ).obj ?: return@flatMap emptyList()
            payload["episodes"]?.jsonArray.orEmpty().mapNotNull { element ->
                val episode = element as? JsonObject ?: return@mapNotNull null
                ScrapedEpisode(
                    season = seasonNumber,
                    episode = episode.int("episode_number") ?: return@mapNotNull null,
                    name = episode.str("name"),
                    overview = episode.str("overview"),
                    airDate = episode.str("air_date"),
                    stillUrl = image(config, episode.str("still_path"), "w300"),
                    rating = episode.dbl("vote_average"),
                    runtimeMs = episode.int("runtime")?.let { it * 60_000L }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- TheTVDB v4

class TvdbScraper(repository: Repository?) : HttpScraper(repository), MetadataScraper {
    override val provider = MetadataProvider.TVDB
    override fun isConfigured(config: ScraperConfig) = config.tvdbApiKey.isNotBlank()

    private var token: String? = null
    private var tokenIssuedAt = 0L

    private fun auth(config: ScraperConfig): Map<String, String>? {
        val age = System.currentTimeMillis() - tokenIssuedAt
        if (token == null || age > 20L * 24 * 3600 * 1000) {
            val response = postJson(
                "$BASE/login",
                buildJsonObject { put("apikey", config.tvdbApiKey) }
            ).obj ?: return null
            token = response["data"]?.jsonObject?.str("token") ?: return null
            tokenIssuedAt = System.currentTimeMillis()
        }
        return mapOf("Authorization" to "Bearer ${token!!}")
    }

    override fun search(title: String, year: Int?, kind: ItemKind, config: ScraperConfig): List<ScrapeCandidate> {
        val headers = auth(config) ?: return emptyList()
        val type = if (kind == ItemKind.MOVIE) "movie" else "series"
        val url = "$BASE/search?query=${encode(title)}&type=$type" +
            (year?.let { "&year=$it" } ?: "") + "&limit=10"
        val results = getJson(url, headers, cacheKey = "tvdb:search:$type:$title:$year").obj
            ?.get("data")?.jsonArray ?: return emptyList()
        return results.mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull null
            ScrapeCandidate(
                rank = index,
                providerId = item.str("tvdb_id") ?: item.str("id")?.substringAfterLast('-')
                    ?: return@mapIndexedNotNull null,
                title = item.str("name") ?: return@mapIndexedNotNull null,
                originalTitle = item.str("name"),
                year = item.str("year")?.toIntOrNull(),
                overview = item.str("overview"),
                posterUrl = item.str("image_url") ?: item.str("thumbnail")
            )
        }
    }

    override fun details(providerId: String, kind: ItemKind, config: ScraperConfig): ScrapedMetadata? {
        val headers = auth(config) ?: return null
        val endpoint = if (kind == ItemKind.MOVIE) "movies" else "series"
        val url = "$BASE/$endpoint/$providerId/extended?meta=translations"
        val item = getJson(url, headers, cacheKey = "tvdb:details:$endpoint:$providerId").obj
            ?.get("data")?.jsonObject ?: return null

        val language = config.language.substringBefore('-').let { if (it == "zh") "zho" else it }
        val translations = item["translations"]?.jsonObject
        val nameTranslations = translations?.get("nameTranslations")?.jsonArray.orEmpty()
        val overviewTranslations = translations?.get("overviewTranslations")?.jsonArray.orEmpty()
        val localisedName = nameTranslations.firstOrNull { (it as? JsonObject)?.str("language") == language }
            ?.jsonObject?.str("name")
        val localisedOverview = overviewTranslations.firstOrNull { (it as? JsonObject)?.str("language") == language }
            ?.jsonObject?.str("overview")

        val artworks = item["artworks"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        fun artwork(type: Int) = artworks.firstOrNull { it.int("type") == type }?.str("image")

        return ScrapedMetadata(
            provider = MetadataProvider.TVDB,
            providerId = providerId,
            name = localisedName ?: item.str("name") ?: return null,
            originalName = item.str("name"),
            overview = localisedOverview ?: item.str("overview"),
            year = item.str("firstAired")?.take(4)?.toIntOrNull() ?: item.str("year")?.toIntOrNull(),
            premiereDate = item.str("firstAired"),
            runtimeMs = item.int("averageRuntime")?.let { it * 60_000L },
            communityRating = item.dbl("score")?.let { (it / 1000.0).coerceAtMost(10.0) },
            genres = item["genres"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.str("name") },
            studios = item["companies"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }.take(5),
            people = item["characters"]?.jsonArray.orEmpty().take(20).mapNotNull { element ->
                val person = element as? JsonObject ?: return@mapNotNull null
                PersonDto(
                    name = person.str("personName") ?: return@mapNotNull null,
                    role = person.str("name"),
                    type = "Actor",
                    imageUrl = person.str("image")
                )
            },
            posterUrl = item.str("image") ?: artwork(2),
            backdropUrl = artwork(3),
            logoUrl = artwork(23)
        )
    }

    override fun episodes(providerId: String, config: ScraperConfig): List<ScrapedEpisode> {
        val headers = auth(config) ?: return emptyList()
        val payload = getJson(
            "$BASE/series/$providerId/episodes/official?page=0",
            headers,
            cacheKey = "tvdb:episodes:$providerId"
        ).obj?.get("data")?.jsonObject ?: return emptyList()
        return payload["episodes"]?.jsonArray.orEmpty().mapNotNull { element ->
            val episode = element as? JsonObject ?: return@mapNotNull null
            ScrapedEpisode(
                season = episode.int("seasonNumber") ?: return@mapNotNull null,
                episode = episode.int("number") ?: return@mapNotNull null,
                name = episode.str("name"),
                overview = episode.str("overview"),
                airDate = episode.str("aired"),
                stillUrl = episode.str("image"),
                runtimeMs = episode.int("runtime")?.let { it * 60_000L }
            )
        }
    }

    private companion object {
        const val BASE = "https://api4.thetvdb.com/v4"
    }
}

// ---------------------------------------------------------------- bangumi.tv

class BangumiScraper(repository: Repository?) : HttpScraper(repository), MetadataScraper {
    override val provider = MetadataProvider.BANGUMI

    /** The public read API needs no credentials; a token only raises rate limits. */
    override fun isConfigured(config: ScraperConfig) = true

    private fun headers(config: ScraperConfig): Map<String, String> =
        if (config.bangumiToken.isBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer ${config.bangumiToken}")

    override fun search(title: String, year: Int?, kind: ItemKind, config: ScraperConfig): List<ScrapeCandidate> =
        query(title, config, animeOnly = true)

    /**
     * Manual lookups drop the type filter. Automatic matching needs it — without
     * it a same-titled game or live-action show wins, which is what dragged the
     * match rate down to 34% — but once the user has picked bangumi.tv by hand,
     * hiding everything that is not anime just makes the dialog look broken.
     */
    override fun searchManual(
        title: String,
        year: Int?,
        kind: ItemKind,
        config: ScraperConfig
    ): List<ScrapeCandidate> = query(title, config, animeOnly = false)

    private fun query(title: String, config: ScraperConfig, animeOnly: Boolean): List<ScrapeCandidate> {
        val body = buildJsonObject {
            put("keyword", title)
            put("sort", "match")
            if (animeOnly) {
                put("filter", buildJsonObject {
                    put("type", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(SUBJECT_TYPE_ANIME)) })
                })
            }
        }
        val response = postJson("$BASE/v0/search/subjects?limit=10", body, headers(config)).obj
        val results = response?.get("data")?.jsonArray ?: return emptyList()
        return results.mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull null
            val date = item.str("date")
            ScrapeCandidate(
                rank = index,
                providerId = item.int("id")?.toString() ?: return@mapIndexedNotNull null,
                title = item.str("name_cn")?.takeIf { it.isNotBlank() } ?: item.str("name")
                    ?: return@mapIndexedNotNull null,
                originalTitle = item.str("name"),
                year = date?.take(4)?.toIntOrNull(),
                overview = item.str("summary"),
                posterUrl = item["images"]?.jsonObject?.str("large"),
                score = item["rating"]?.jsonObject?.dbl("score") ?: 0.0
            )
        }
    }

    override fun details(providerId: String, kind: ItemKind, config: ScraperConfig): ScrapedMetadata? {
        val item = getJson(
            "$BASE/v0/subjects/$providerId",
            headers(config),
            cacheKey = "bgm:subject:$providerId"
        ).obj ?: return null
        val date = item.str("date")
        val infobox = item["infobox"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        fun infoValue(key: String): String? = infobox.firstOrNull { it.str("key") == key }
            ?.get("value")?.let { value ->
                (value as? JsonPrimitive)?.contentOrNull
                    ?: (value as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("v") }?.joinToString(" / ")
            }

        val people = listOfNotNull(
            infoValue("导演")?.let { PersonDto(it, "导演", "Director") },
            infoValue("原作")?.let { PersonDto(it, "原作", "Writer") },
            infoValue("脚本")?.let { PersonDto(it, "系列构成", "Writer") },
            infoValue("音乐")?.let { PersonDto(it, "音乐", "Composer") }
        )

        return ScrapedMetadata(
            provider = MetadataProvider.BANGUMI,
            providerId = providerId,
            name = item.str("name_cn")?.takeIf { it.isNotBlank() } ?: item.str("name") ?: return null,
            originalName = item.str("name"),
            overview = item.str("summary"),
            year = date?.take(4)?.toIntOrNull(),
            premiereDate = date,
            runtimeMs = infoValue("话数")?.let { null },
            communityRating = item["rating"]?.jsonObject?.dbl("score"),
            genres = item["tags"]?.jsonArray.orEmpty().take(8).mapNotNull { (it as? JsonObject)?.str("name") },
            studios = listOfNotNull(infoValue("动画制作"), infoValue("製作")).take(3),
            people = people,
            posterUrl = item["images"]?.jsonObject?.str("large"),
            backdropUrl = null
        )
    }

    override fun episodes(providerId: String, config: ScraperConfig): List<ScrapedEpisode> {
        val payload = getJson(
            "$BASE/v0/episodes?subject_id=$providerId&type=0&limit=100",
            headers(config),
            cacheKey = "bgm:episodes:$providerId"
        ).obj ?: return emptyList()
        return payload["data"]?.jsonArray.orEmpty().mapNotNull { element ->
            val episode = element as? JsonObject ?: return@mapNotNull null
            val number = episode.dbl("sort")?.toInt() ?: episode.int("ep") ?: return@mapNotNull null
            ScrapedEpisode(
                season = 1,
                episode = number,
                name = episode.str("name_cn")?.takeIf { it.isNotBlank() } ?: episode.str("name"),
                overview = episode.str("desc"),
                airDate = episode.str("airdate"),
                runtimeMs = null
            )
        }
    }

    private companion object {
        const val BASE = "https://api.bgm.tv"

        /** bangumi.tv subject types: 1 书籍, 2 动画, 3 音乐, 4 游戏, 6 三次元. */
        const val SUBJECT_TYPE_ANIME = 2
    }
}

// ---------------------------------------------------------------- helpers

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
