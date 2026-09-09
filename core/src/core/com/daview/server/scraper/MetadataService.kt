package com.daview.server.scraper

import com.daview.server.config.ScraperConfig
import com.daview.server.db.ItemRecord
import com.daview.server.db.Repository
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.ScrapeStatus
import com.daview.server.library.NameParser
import org.slf4j.LoggerFactory
import kotlin.math.max

/**
 * Applies scraped metadata on top of what the scanner derived from file names.
 *
 * Provider order is per-library; the first provider that returns a confident
 * match wins, and later providers only fill in fields the winner left empty.
 */
class MetadataService(
    private val repository: Repository,
    /** Overridden in tests; production always uses the real three. */
    private val scrapers: Map<MetadataProvider, MetadataScraper> = mapOf(
        MetadataProvider.TMDB to TmdbScraper(repository),
        MetadataProvider.TVDB to TvdbScraper(repository),
        MetadataProvider.BANGUMI to BangumiScraper(repository)
    )
) {

    private val log = LoggerFactory.getLogger(MetadataService::class.java)

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
            runCatching {
                enrichItem(
                    item,
                    order,
                    config.copy(language = library.language.ifBlank { config.language })
                )
            }
                .onFailure { log.warn("刮削 {} 失败: {}", item.name, it.message) }
        }
        return done
    }

    fun enrichItem(item: MediaItemDto, order: List<MetadataProvider>, config: ScraperConfig): Boolean {
        val kind = if (item.kind == ItemKind.MOVIE) ItemKind.MOVIE else ItemKind.SERIES
        var merged: ScrapedMetadata? = null
        val providerIds = item.providerIds.toMutableMap()

        // The provider the user pinned by hand goes first. The others still run,
        // but only with an id they already have (TMDB hands over a TVDB id, for
        // instance) — never with a search, since a search is exactly what
        // produced the wrong match the user is correcting.
        //
        // The pin table is consulted whenever the row itself carries none: sync
        // brings corrections over from other devices, and one can easily land
        // before this device has ever scanned the item it belongs to.
        val storedPin = if (item.lockedProvider == null) repository.pin(item.id) else null
        val pinnedProvider = storedPin?.let { row ->
            MetadataProvider.entries.firstOrNull { it.name == row.provider }
        }
        if (pinnedProvider != null) {
            providerIds[pinnedProvider.name.lowercase()] = storedPin.providerId
        }
        val locked = item.lockedProvider ?: pinnedProvider
        val effectiveOrder =
            if (locked == null) order else listOf(locked) + order.filter { it != locked }

        var status = if (locked != null) ScrapeStatus.MANUAL else ScrapeStatus.MATCHED
        var attempted = false

        for (provider in effectiveOrder) {
            val scraper = scrapers[provider] ?: continue
            if (!scraper.isConfigured(config)) continue
            attempted = true

            val pinnedId = providerIds[provider.name.lowercase()]
            val candidateId = when {
                pinnedId != null -> pinnedId
                locked != null -> null
                // An id from another database beats searching by title:
                // it is an answer somebody already arrived at, and this
                // is what makes a library moved over from Emby keep the
                // matches it came with instead of being re-guessed.
                else -> scraper.fromExternalIds(providerIds, kind, config)
                    ?: bestMatch(item, scraper.search(item.name, item.year, kind, config))?.providerId
            } ?: continue

            val details = scraper.details(candidateId, kind, config) ?: continue
            providerIds[provider.name.lowercase()] = candidateId
            details.extraProviderIds.forEach { (key, value) -> providerIds.putIfAbsent(key, value) }
            merged = merged?.let { mergeInto(it, details) } ?: details

            if (merged.overview != null && merged.posterUrl != null && merged.name.isNotBlank()) break
        }

        // Nothing matched confidently. Rather than leave the item bare -- no
        // title, no artwork, indistinguishable from one that was never scraped
        // -- take the best any source will offer and say so, so the detail page
        // can flag it and the manual identify dialog can correct it.
        if (merged == null && locked == null) {
            for (provider in effectiveOrder) {
                val scraper = scrapers[provider] ?: continue
                if (!scraper.isConfigured(config)) continue
                val candidate = fallbackMatch(item, scraper.search(item.name, item.year, kind, config))
                    ?: continue
                val details = scraper.details(candidate.providerId, kind, config) ?: continue
                providerIds[provider.name.lowercase()] = candidate.providerId
                details.extraProviderIds.forEach { (key, value) -> providerIds.putIfAbsent(key, value) }
                merged = details
                status = ScrapeStatus.FALLBACK
                log.info("{} 没有可靠匹配，退而使用 {} 的《{}》", item.name, provider.name, details.name)
                break
            }
        }

        if (merged == null) {
            // Record that every source was asked and came back empty, so the UI
            // can distinguish this from an item nobody has scraped yet. The
            // scraped_at stamp stays null on purpose: a new API key or a
            // corrected folder name should get another chance on the next scan.
            if (attempted) markStatus(item, ScrapeStatus.UNMATCHED)
            return false
        }
        val metadata = merged
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
                    providerIds = providerIds,
                    // A pin that arrived from another device has to land on the
                    // row too, or the next scrape would search again and undo it.
                    lockedProvider = locked,
                    scrapeStatus = status
                ),
                scrapedAt = now
            )
        )

        if (item.kind == ItemKind.SERIES) applyEpisodeMetadata(item, providerIds, order, config)
        return true
    }

    /**
     * Least-bad candidate when [bestMatch] rejected everything: the provider's
     * own top hit, as long as the year does not contradict the folder. Wrong
     * often enough to be labelled, useful more often than an empty entry.
     */
    private fun fallbackMatch(item: MediaItemDto, candidates: List<ScrapeCandidate>): ScrapeCandidate? {
        val targetYear = item.year
        return candidates
            .filter { candidate ->
                targetYear == null || candidate.year == null ||
                    kotlin.math.abs(targetYear - candidate.year) <= 1
            }
            .minByOrNull { it.rank }
    }

    private fun markStatus(item: MediaItemDto, status: ScrapeStatus) {
        val record = repository.itemRecord(item.id) ?: return
        if (record.dto.scrapeStatus == status) return
        repository.upsertItem(record.copy(dto = record.dto.copy(scrapeStatus = status)))
    }

    // ------------------------------------------------------------ manual identify

    /**
     * Raw hits from one provider, for the dialog where the user picks the right
     * entry themselves. Deliberately unfiltered: [bestMatch] and its year rule
     * are what rejected the correct answer in the first place.
     */
    fun searchProvider(
        provider: MetadataProvider,
        query: String,
        year: Int?,
        kind: ItemKind,
        config: ScraperConfig
    ): List<ScrapeCandidate> {
        val scraper = scrapers[provider] ?: return emptyList()
        if (!scraper.isConfigured(config)) return emptyList()
        if (query.isBlank()) return emptyList()
        return scraper.searchManual(query.trim(), year, kind, config)
    }

    /**
     * Pins [providerId] on [item] and rebuilds its metadata around that source.
     * Returns the stored item, or null when the id does not resolve — a typo
     * must not wipe metadata that is already correct.
     */
    fun identify(
        item: MediaItemDto,
        provider: MetadataProvider,
        providerId: String,
        order: List<MetadataProvider>,
        config: ScraperConfig
    ): MediaItemDto? {
        val scraper = scrapers[provider] ?: return null
        if (!scraper.isConfigured(config)) return null
        val id = providerId.trim()
        if (id.isBlank()) return null
        val kind = if (item.kind == ItemKind.MOVIE) ItemKind.MOVIE else ItemKind.SERIES
        scraper.details(id, kind, config) ?: return null

        val base = stripScrapedFields(item).copy(
            providerIds = mapOf(provider.name.lowercase() to id),
            lockedProvider = provider,
            scrapeStatus = ScrapeStatus.MANUAL
        )
        if (item.kind == ItemKind.SERIES) resetEpisodes(item.id)
        val applied = enrichItem(base, listOf(provider) + order.filter { it != provider }, config)
        if (!applied) return null
        // Recorded outside the item as well, so it survives into the sync file
        // and the other devices stop re-matching this folder on their own.
        repository.savePin(item.id, provider.name, id)
        return repository.item(item.id)
    }

    /**
     * The title as the scanner read it off the folder. That is what the user
     * should be searching with, because the stored name may well be the title
     * of the wrong match.
     */
    fun folderTitle(item: MediaItemDto): NameParser.TitleInfo {
        val path = item.path?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: return NameParser.parseTitle(item.name)
        val raw = when {
            item.kind == ItemKind.SERIES -> path.substringAfterLast('/')
            // A film nested inside a series folder was named after its file;
            // a top-level one after its own folder. Mirrors Scanner.
            item.parentId != null -> path.substringAfterLast('/').substringBeforeLast('.')
            else -> path.substringBeforeLast('/').substringAfterLast('/')
        }
        return NameParser.parseTitle(raw.ifBlank { item.name })
    }

    /** Everything a scraper wrote. What the files told us stays. */
    private fun stripScrapedFields(item: MediaItemDto) = item.copy(
        originalName = null,
        overview = null,
        premiereDate = null,
        communityRating = null,
        officialRating = null,
        genres = emptyList(),
        studios = emptyList(),
        people = emptyList(),
        posterUrl = null,
        backdropUrl = null,
        logoUrl = null
    )

    /**
     * Episodes the new series does not cover would otherwise keep the titles and
     * stills of the wrong one, so they go back to their file names first.
     */
    private fun resetEpisodes(seriesId: String) {
        val episodes = repository.episodesOfSeries(seriesId)
        if (episodes.isEmpty()) return
        val updates = episodes.mapNotNull { episode ->
            val record = repository.itemRecord(episode.id) ?: return@mapNotNull null
            val parsed = episode.path?.substringAfterLast('/')
                ?.let { NameParser.parseEpisode(it, episode.parentIndexNumber) }
            record.copy(
                dto = episode.copy(
                    name = parsed?.title?.takeIf { it.isNotBlank() }
                        ?: "第 ${episode.indexNumber ?: 1} 集",
                    overview = null,
                    premiereDate = null,
                    communityRating = null,
                    posterUrl = null
                ),
                scrapedAt = null
            )
        }
        repository.upsertItems(updates)
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
                // Providers that number a show as one season let us fall back on
                // the episode number alone — but not for specials, or every
                // special ends up wearing episode 1's title.
                ?: (if (singleSeason && season != 0) bySeasonEpisode[scraped.first().season to number] else null)
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
            .mapNotNull { candidate ->
                val titleScore = max(
                    similarity(target, normalise(candidate.title)),
                    candidate.originalTitle?.let { similarity(target, normalise(it)) } ?: 0.0
                )
                val exactYear = targetYear != null && candidate.year == targetYear

                // Folder names here are English while bangumi.tv answers in
                // Japanese and Chinese, so string similarity alone would reject
                // almost every correct match. The provider's own search already
                // did that work, so a top-ranked hit whose year agrees is
                // trusted; anything further down has to look like the title.
                val acceptable = titleScore >= STRONG_TITLE_MATCH ||
                    (exactYear && candidate.rank < TRUSTED_RANK) ||
                    (titleScore >= WEAK_TITLE_MATCH && targetYear != null && candidate.year != null)
                if (!acceptable) return@mapNotNull null

                val score = titleScore + when {
                    exactYear -> 0.35
                    targetYear != null && candidate.year != null -> 0.10
                    else -> 0.0
                } - candidate.rank * 0.02
                candidate to score
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private companion object {
        /** Enough on its own, even without a matching year. */
        const val STRONG_TITLE_MATCH = 0.55

        /** Only accepted when the release year matches exactly. */
        const val WEAK_TITLE_MATCH = 0.40

        /** How far down a provider's ranking an exact-year match is still believed. */
        const val TRUSTED_RANK = 3
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
