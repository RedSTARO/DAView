package com.daview.server.scraper

import com.daview.server.config.ScraperConfig
import com.daview.server.db.ItemRecord
import com.daview.server.db.Repository
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataProvider
import com.daview.server.library.NameParser
import org.slf4j.LoggerFactory
import kotlin.math.max

/**
 * Applies scraped metadata on top of what the scanner derived from file names.
 *
 * Provider order is per-library; the first provider that returns a confident
 * match wins, and later providers only fill in fields the winner left empty.
 */
class MetadataService(private val repository: Repository) {

    private val log = LoggerFactory.getLogger(MetadataService::class.java)

    private val scrapers: Map<MetadataProvider, MetadataScraper> = mapOf(
        MetadataProvider.TMDB to TmdbScraper(repository),
        MetadataProvider.TVDB to TvdbScraper(repository),
        MetadataProvider.BANGUMI to BangumiScraper(repository)
    )

    fun availableProviders(config: ScraperConfig): List<MetadataProvider> =
        scrapers.filterValues { it.isConfigured(config) }.keys.toList()

    fun defaultOrder(kind: LibraryKind): List<MetadataProvider> = when (kind) {
        LibraryKind.ANIME -> listOf(MetadataProvider.BANGUMI, MetadataProvider.TMDB, MetadataProvider.TVDB)
        LibraryKind.MOVIE -> listOf(MetadataProvider.TMDB, MetadataProvider.TVDB)
        // bangumi.tv only indexes anime, so it is deliberately not a fallback
        // for live-action shows: it happily returns same-titled anime instead.
        LibraryKind.SERIES -> listOf(MetadataProvider.TMDB, MetadataProvider.TVDB)
        LibraryKind.OTHER -> listOf(MetadataProvider.TMDB)
    }

    fun interface ProgressSink {
        fun report(current: Int, total: Int, message: String)
    }

    fun enrichLibrary(
        library: LibraryDto,
        config: ScraperConfig,
        force: Boolean,
        progress: ProgressSink
    ): Int {
        val order = library.providerOrder.ifEmpty { defaultOrder(library.kind) }
            .filter { scrapers[it]?.isConfigured(config) == true }
        if (order.isEmpty()) {
            log.info("库 {} 没有可用的刮削源，跳过", library.name)
            return 0
        }

        val targets = repository.itemsNeedingScrape(library.id, force)
        var done = 0
        targets.forEach { item ->
            done++
            progress.report(done, targets.size, item.name)
            runCatching { enrichItem(item, order, config.copy(language = library.language)) }
                .onFailure { log.warn("刮削 {} 失败: {}", item.name, it.message) }
        }
        return done
    }

    fun enrichItem(item: MediaItemDto, order: List<MetadataProvider>, config: ScraperConfig): Boolean {
        val kind = if (item.kind == ItemKind.MOVIE) ItemKind.MOVIE else ItemKind.SERIES
        var merged: ScrapedMetadata? = null
        val providerIds = item.providerIds.toMutableMap()

        for (provider in order) {
            val scraper = scrapers[provider] ?: continue
            if (!scraper.isConfigured(config)) continue

            val pinnedId = providerIds[provider.name.lowercase()]
            val candidateId = pinnedId ?: run {
                val candidates = scraper.search(item.name, item.year, kind, config)
                bestMatch(item, candidates)?.providerId
            } ?: continue

            val details = scraper.details(candidateId, kind, config) ?: continue
            providerIds[provider.name.lowercase()] = candidateId
            details.extraProviderIds.forEach { (key, value) -> providerIds.putIfAbsent(key, value) }
            merged = merged?.let { mergeInto(it, details) } ?: details

            if (merged.overview != null && merged.posterUrl != null && merged.name.isNotBlank()) break
        }

        val metadata = merged ?: return false
        val now = System.currentTimeMillis()
        val existing = repository.itemRecord(item.id) ?: return false

        repository.upsertItem(
            existing.copy(
                dto = item.copy(
                    name = metadata.name.ifBlank { item.name },
                    originalName = metadata.originalName ?: item.originalName,
                    sortName = NameParser.sortName(metadata.name.ifBlank { item.name }),
                    overview = metadata.overview ?: item.overview,
                    year = metadata.year ?: item.year,
                    premiereDate = metadata.premiereDate ?: item.premiereDate,
                    runtimeMs = item.runtimeMs ?: metadata.runtimeMs,
                    communityRating = metadata.communityRating ?: item.communityRating,
                    officialRating = metadata.officialRating ?: item.officialRating,
                    genres = metadata.genres.ifEmpty { item.genres },
                    studios = metadata.studios.ifEmpty { item.studios },
                    people = metadata.people.ifEmpty { item.people },
                    posterUrl = metadata.posterUrl ?: item.posterUrl,
                    backdropUrl = metadata.backdropUrl ?: item.backdropUrl,
                    logoUrl = metadata.logoUrl ?: item.logoUrl,
                    providerIds = providerIds
                ),
                scrapedAt = now
            )
        )

        if (item.kind == ItemKind.SERIES) applyEpisodeMetadata(item, providerIds, order, config)
        return true
    }

    private fun applyEpisodeMetadata(
        series: MediaItemDto,
        providerIds: Map<String, String>,
        order: List<MetadataProvider>,
        config: ScraperConfig
    ) {
        val episodes = repository.episodesOfSeries(series.id)
        if (episodes.isEmpty()) return

        val scraped = order.asSequence()
            .mapNotNull { provider ->
                val id = providerIds[provider.name.lowercase()] ?: return@mapNotNull null
                scrapers[provider]?.episodes(id, config)?.takeIf { it.isNotEmpty() }
            }
            .firstOrNull() ?: return

        val bySeasonEpisode = scraped.associateBy { it.season to it.episode }
        val singleSeason = scraped.map { it.season }.distinct().size == 1
        val now = System.currentTimeMillis()

        val updates = episodes.mapNotNull { episode ->
            val season = episode.parentIndexNumber ?: 1
            val number = episode.indexNumber ?: return@mapNotNull null
            val match = bySeasonEpisode[season to number]
                ?: (if (singleSeason) bySeasonEpisode[scraped.first().season to number] else null)
                ?: return@mapNotNull null
            val record = repository.itemRecord(episode.id) ?: return@mapNotNull null
            record.copy(
                dto = episode.copy(
                    name = match.name?.takeIf { it.isNotBlank() } ?: episode.name,
                    overview = match.overview ?: episode.overview,
                    premiereDate = match.airDate ?: episode.premiereDate,
                    communityRating = match.rating ?: episode.communityRating,
                    runtimeMs = episode.runtimeMs ?: match.runtimeMs,
                    posterUrl = match.stillUrl ?: episode.posterUrl
                ),
                scrapedAt = now
            )
        }
        repository.upsertItems(updates)
    }

    private fun mergeInto(base: ScrapedMetadata, extra: ScrapedMetadata) = base.copy(
        overview = base.overview ?: extra.overview,
        year = base.year ?: extra.year,
        premiereDate = base.premiereDate ?: extra.premiereDate,
        runtimeMs = base.runtimeMs ?: extra.runtimeMs,
        communityRating = base.communityRating ?: extra.communityRating,
        genres = base.genres.ifEmpty { extra.genres },
        studios = base.studios.ifEmpty { extra.studios },
        people = base.people.ifEmpty { extra.people },
        posterUrl = base.posterUrl ?: extra.posterUrl,
        backdropUrl = base.backdropUrl ?: extra.backdropUrl,
        logoUrl = base.logoUrl ?: extra.logoUrl
    )

    /**
     * Picks the candidate that best matches the folder name. A matching year is
     * worth a lot; beyond that it is normalised edit-distance on the title.
     */
    fun bestMatch(item: MediaItemDto, candidates: List<ScrapeCandidate>): ScrapeCandidate? {
        if (candidates.isEmpty()) return null
        val target = normalise(item.name)
        val targetYear = item.year
        return candidates
            // A release year that is off by more than one is treated as a
            // different work: without this, "All Is Well (2019)" happily matches
            // an unrelated 2024 show whose title happens to be identical.
            .filter { candidate ->
                targetYear == null || candidate.year == null ||
                    kotlin.math.abs(targetYear - candidate.year) <= 1
            }
            .map { candidate ->
                var score = max(
                    similarity(target, normalise(candidate.title)),
                    candidate.originalTitle?.let { similarity(target, normalise(it)) } ?: 0.0
                )
                if (targetYear != null && candidate.year != null) {
                    score += if (targetYear == candidate.year) 0.35 else 0.10
                }
                candidate to score
            }
            .filter { it.second >= 0.42 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun normalise(value: String) = value.lowercase()
        .replace(Regex("""[\p{Punct}\s·・：:!?！？'"”“]"""), "")
        .trim()

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.85
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / max(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
