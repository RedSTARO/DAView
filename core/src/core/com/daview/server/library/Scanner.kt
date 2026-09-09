package com.daview.server.library

import com.daview.server.db.ItemRecord
import com.daview.server.db.Repository
import com.daview.server.media.ImageCache
import com.daview.server.storage.DavEntry
import com.daview.server.storage.WebDavClient
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Walks a WebDAV subtree and turns it into library items.
 *
 * The traversal is deliberately conservative: names are parsed, nothing is
 * written back to the share (the reference share is read-only anyway), and
 * anything that cannot be understood is skipped rather than guessed at.
 */
class Scanner(
    private val dav: WebDavClient,
    private val repository: Repository
) {
    private val log = LoggerFactory.getLogger(Scanner::class.java)

    /** Season zero is the specials folder, in this layout and in Emby's. */
    private val SPECIALS_SEASON = 0

    fun interface ProgressSink {
        fun report(phase: String, current: Int, total: Int, message: String)
    }

    data class Result(val itemCount: Int, val removed: Int, val warnings: List<String>)

    fun scan(library: LibraryDto, progress: ProgressSink): Result {
        val warnings = mutableListOf<String>()
        val records = mutableListOf<ItemRecord>()
        val now = System.currentTimeMillis()

        progress.report("listing", 0, 1, "读取 ${library.path}")
        val roots = dav.list(library.path)
        val folders = roots.filter {
            it.isDirectory &&
                !NameParser.isExtrasFolder(it.name) &&
                // A NAS puts @eaDir beside every folder and a #recycle at the
                // share root; each was being read as a title and scraped.
                !NameParser.isSystemFolder(it.name)
        }
        val looseVideos = roots.filter { !it.isDirectory && NameParser.isVideoFile(it.name) && !NameParser.isJunkFile(it.name) }
        val looseSubtitles = roots.filter { !it.isDirectory && NameParser.isSubtitleFile(it.name) }

        val total = folders.size + looseVideos.size
        var index = 0

        folders.forEach { folder ->
            index++
            progress.report("scanning", index, total, folder.name)
            runCatching {
                if (library.kind.isSeriesLike) {
                    records += scanSeriesFolder(library, folder, now)
                } else {
                    records += scanMovieFolder(library, folder, now)
                }
            }.onFailure {
                log.warn("扫描 {} 失败", folder.path, it)
                warnings += "${folder.name}: ${it.message}"
            }
        }

        looseVideos.forEach { video ->
            index++
            progress.report("scanning", index, total, video.name)
            records += movieFromFile(library, video, looseSubtitles, parentId = null, now = now)
        }

        progress.report("saving", total, total, "写入数据库")
        repository.upsertScannedItems(records)

        // Parentage was just rebuilt from the folder tree, so any merge the user
        // made has to be laid back on top of it.
        repository.reapplyMerges()

        val seen = records.map { it.dto.id }.toSet()
        val stale = repository.idsInLibrary(library.id) - seen
        if (stale.isNotEmpty()) repository.deleteItems(stale)
        repository.markScanned(library.id, now)

        // The catalogue just changed size by orders of magnitude. Leaving the
        // planner on the statistics it had before the scan is what makes a
        // freshly filled library feel slower than one that has been reopened.
        repository.refreshStatistics()

        return Result(records.size, stale.size, warnings)
    }

    // ------------------------------------------------------------ movies

    private fun scanMovieFolder(library: LibraryDto, folder: DavEntry, now: Long): List<ItemRecord> {
        val children = dav.list(folder.path)
        val videos = children.filter {
            !it.isDirectory && NameParser.isVideoFile(it.name) && !NameParser.isJunkFile(it.name)
        }
        val subtitles = children.filter { !it.isDirectory && NameParser.isSubtitleFile(it.name) }
        val nestedSubs = nestedSubtitles(children)
        val artwork = localArtwork(children)

        if (videos.isEmpty()) {
            // A folder of folders, e.g. a collection directory.
            return children
                .filter {
                    it.isDirectory &&
                        !NameParser.isExtrasFolder(it.name) &&
                        !NameParser.isSystemFolder(it.name)
                }
                .flatMap { scanMovieFolder(library, it, now) }
        }

        // Every film in the folder, not only the largest file. Keeping one
        // meant a category directory of loose films — `/电影/漫威系列/*.mkv` —
        // contributed exactly one entry and the rest did not exist anywhere in
        // the app, and a two-part film lost its second half.
        val info = NameParser.parseTitle(folder.name)
        val main = videos.maxByOrNull { it.size ?: 0 } ?: videos.first()
        return videos.map { video ->
            movieFromFile(
                library,
                video,
                subtitlesFor(video, subtitles, nestedSubs, soleVideo = videos.size == 1),
                parentId = null,
                now = now,
                // Only the main feature takes the folder's title; the others
                // keep their own file names so two entries are tellable apart.
                override = info.takeIf { video.path == main.path },
                artwork = artwork
            )
        }
    }

    /** Poster and backdrop sitting next to the video, if whoever built the folder put them there. */
    private fun localArtwork(children: List<DavEntry>): LocalArtwork {
        val images = children.filter { !it.isDirectory }
        return LocalArtwork(
            poster = images.firstOrNull { NameParser.isPosterImage(it.name) }?.path,
            backdrop = images.firstOrNull { NameParser.isBackdropImage(it.name) }?.path
        )
    }

    /**
     * Artwork found on the share.
     *
     * Worth having because the scrapers need an API key the user has to go and
     * apply for, and without one every tile is a grey placeholder — while the
     * folder very often already holds the picture, put there by whatever built
     * the library before this one.
     */
    data class LocalArtwork(val poster: String? = null, val backdrop: String? = null)

    private fun movieFromFile(
        library: LibraryDto,
        video: DavEntry,
        subtitles: List<DavEntry>,
        parentId: String?,
        now: Long,
        override: NameParser.TitleInfo? = null,
        artwork: LocalArtwork = LocalArtwork()
    ): ItemRecord {
        val info = override ?: NameParser.parseTitle(video.name.substringBeforeLast('.'))
        val id = itemId(library.id, video.path)
        return ItemRecord(
            dto = MediaItemDto(
                id = id,
                libraryId = library.id,
                kind = ItemKind.MOVIE,
                parentId = parentId,
                name = info.title,
                sortName = NameParser.sortName(info.title),
                year = info.year,
                providerIds = info.providerIds,
                path = video.path,
                sizeBytes = video.size,
                // A poster the folder already carries. It is only ever a
                // starting point: a scrape overwrites it, and the upsert leaves
                // scraped rows alone.
                posterUrl = artwork.poster?.let { "${ImageCache.STORAGE_SCHEME}$it" },
                backdropUrl = artwork.backdrop?.let { "${ImageCache.STORAGE_SCHEME}$it" },
                mediaStreams = externalSubtitleStreams(video, subtitles)
            ),
            dateCreated = now,
            dateModified = now,
            etag = video.etag
        )
    }

    // ------------------------------------------------------------ series

    private fun scanSeriesFolder(library: LibraryDto, folder: DavEntry, now: Long): List<ItemRecord> {
        val info = NameParser.parseTitle(folder.name)
        val seriesId = itemId(library.id, folder.path)
        val out = mutableListOf<ItemRecord>()
        val artwork = localArtwork(dav.list(folder.path))

        out += ItemRecord(
            dto = MediaItemDto(
                id = seriesId,
                libraryId = library.id,
                kind = ItemKind.SERIES,
                name = info.title,
                sortName = NameParser.sortName(info.title),
                year = info.year,
                providerIds = info.providerIds,
                path = folder.path,
                posterUrl = artwork.poster?.let { "${ImageCache.STORAGE_SCHEME}$it" },
                backdropUrl = artwork.backdrop?.let { "${ImageCache.STORAGE_SCHEME}$it" }
            ),
            dateCreated = now,
            dateModified = now,
            etag = folder.etag
        )

        val children = dav.list(folder.path)
        val seasonFolders = children.filter { it.isDirectory && NameParser.parseSeasonFolder(it.name) != null }
        val otherFolders = children.filter {
            it.isDirectory && NameParser.parseSeasonFolder(it.name) == null && !NameParser.isExtrasFolder(it.name)
        }
        val looseVideos = children.filter {
            !it.isDirectory && NameParser.isVideoFile(it.name) && !NameParser.isJunkFile(it.name)
        }
        val looseSubtitles = children.filter { !it.isDirectory && NameParser.isSubtitleFile(it.name) }

        // Anything that is not the season's own episodes: a `SPs`, `Extras` or
        // `CDs` folder sitting inside it. Collected here rather than scanned in
        // place because they all belong to one specials season, not to the
        // season they happen to live under.
        val extraFolders = mutableListOf<DavEntry>()
        var specialsSeasonId: String? = null
        var specialsEpisodes = 0

        seasonFolders.forEach { seasonFolder ->
            val number = NameParser.parseSeasonFolder(seasonFolder.name) ?: 1
            val scanned = seasonWithEpisodes(library, seriesId, info.title, seasonFolder, number, now)
            out += scanned.records
            extraFolders += scanned.subFolders
            if (number == SPECIALS_SEASON) {
                // A real `Season 00` already is the specials season; the extras
                // join it instead of a second one appearing beside it.
                specialsSeasonId = itemId(library.id, seasonFolder.path)
                specialsEpisodes = scanned.records.count { it.dto.kind == ItemKind.EPISODE }
            }
        }

        if (looseVideos.isNotEmpty()) {
            out += episodesInPlace(
                library = library,
                seriesId = seriesId,
                seriesName = info.title,
                seasonNumberHint = if (seasonFolders.isEmpty()) 1 else 0,
                container = folder,
                videos = looseVideos,
                subtitles = looseSubtitles,
                now = now
            )
        }

        // At the series level these were filtered out of `otherFolders` as noise,
        // which is right for building seasons and wrong for finding specials.
        extraFolders += children.filter { it.isDirectory && NameParser.isExtrasFolder(it.name) }
        out += specialsFrom(
            library = library,
            seriesId = seriesId,
            seriesName = info.title,
            seriesFolder = folder,
            folders = extraFolders,
            existingSeasonId = specialsSeasonId,
            indexOffset = specialsEpisodes,
            now = now
        )

        // A nested `Title (Year)` folder inside a series usually holds a film
        // spin-off (`iPartment (2009)/iPartment The Movie (2018)`). Treat it as a
        // movie attached to the series rather than mangling it into a season.
        otherFolders.forEach { nested ->
            runCatching {
                val nestedChildren = dav.list(nested.path)
                val nestedVideos = nestedChildren.filter {
                    !it.isDirectory && NameParser.isVideoFile(it.name) && !NameParser.isJunkFile(it.name)
                }
                val nestedSubs = nestedChildren.filter { !it.isDirectory && NameParser.isSubtitleFile(it.name) }
                if (nestedVideos.isEmpty()) return@runCatching

                val looksEpisodic = nestedVideos.any { NameParser.parseEpisode(it.name, null)?.season != null }
                if (looksEpisodic) {
                    out += seasonWithEpisodes(library, seriesId, info.title, nested, 1, now).records
                } else {
                    val main = nestedVideos.maxByOrNull { it.size ?: 0 }!!
                    out += movieFromFile(
                        library, main, nestedSubs, parentId = seriesId, now = now,
                        override = NameParser.parseTitle(nested.name)
                    )
                }
            }.onFailure { log.warn("嵌套目录 {} 扫描失败", nested.path, it) }
        }

        return out
    }

    private fun seasonWithEpisodes(
        library: LibraryDto,
        seriesId: String,
        seriesName: String,
        folder: DavEntry,
        seasonNumber: Int,
        now: Long
    ): ScannedSeason {
        val seasonId = itemId(library.id, folder.path)
        val out = mutableListOf<ItemRecord>()
        out += ItemRecord(
            dto = MediaItemDto(
                id = seasonId,
                libraryId = library.id,
                kind = ItemKind.SEASON,
                parentId = seriesId,
                seriesId = seriesId,
                seriesName = seriesName,
                name = if (seasonNumber == 0) "特别篇" else "第 $seasonNumber 季",
                sortName = seasonNumber.toString().padStart(4, '0'),
                indexNumber = seasonNumber,
                path = folder.path
            ),
            dateCreated = now,
            dateModified = now,
            etag = folder.etag
        )

        val children = dav.list(folder.path)
        val videos = children.filter { !it.isDirectory && NameParser.isVideoFile(it.name) && !NameParser.isJunkFile(it.name) }
        // Beside the episodes, and inside any Subs / 字幕 folder next to them:
        // a release that files its subtitles that way read as having none.
        val subtitles = children.filter { !it.isDirectory && NameParser.isSubtitleFile(it.name) } +
            nestedSubtitles(children)

        out += videos.mapIndexed { position, video ->
            episodeRecord(library, seriesId, seriesName, seasonId, seasonNumber, video, subtitles, position, now)
        }
        return ScannedSeason(out, children.filter { it.isDirectory })
    }

    /**
     * What one season folder produced, plus the directories inside it.
     *
     * The caller needs those directories because what is in them — menus, PVs,
     * creditless openings, a soundtrack — is not part of the season's run, and
     * listing the folder a second time to find them would double the traversal
     * of a share where every listing is a network round trip.
     */
    private data class ScannedSeason(
        val records: List<ItemRecord>,
        val subFolders: List<DavEntry>
    )

    /**
     * Everything under a series that is not an episode of a season, gathered
     * into that series' specials season.
     *
     * They go to season zero rather than inline, so the run someone is actually
     * watching stays a clean list and the extras sit in their own tab. Nothing
     * filters by folder name: what is worth having is decided by the file, and
     * [NameParser.isVideoFile] already rejects the FLAC albums that make up most
     * of a `CDs` directory.
     */
    private fun specialsFrom(
        library: LibraryDto,
        seriesId: String,
        seriesName: String,
        seriesFolder: DavEntry,
        folders: List<DavEntry>,
        existingSeasonId: String?,
        indexOffset: Int,
        now: Long
    ): List<ItemRecord> {
        if (folders.isEmpty()) return emptyList()

        val found = mutableListOf<Pair<DavEntry, List<DavEntry>>>()
        folders.distinctBy { it.path }.forEach { folder ->
            runCatching {
                val entries = dav.list(folder.path)
                val subtitles = entries.filter { !it.isDirectory && NameParser.isSubtitleFile(it.name) }
                entries.filter {
                    !it.isDirectory && NameParser.isVideoFile(it.name) && !NameParser.isJunkFile(it.name)
                }.forEach { found += it to subtitles }
            }.onFailure { log.warn("特典目录 {} 扫描失败", folder.path, it) }
        }
        if (found.isEmpty()) return emptyList()

        val out = mutableListOf<ItemRecord>()
        val seasonId = existingSeasonId ?: itemId(library.id, seriesFolder.path + "#specials")
        if (existingSeasonId == null) {
            out += ItemRecord(
                dto = MediaItemDto(
                    id = seasonId,
                    libraryId = library.id,
                    kind = ItemKind.SEASON,
                    parentId = seriesId,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    name = "特别篇",
                    sortName = SPECIALS_SEASON.toString().padStart(4, '0'),
                    indexNumber = SPECIALS_SEASON,
                    path = seriesFolder.path
                ),
                dateCreated = now,
                dateModified = now
            )
        }
        out += found.mapIndexed { position, (video, subtitles) ->
            episodeRecord(
                library, seriesId, seriesName, seasonId, SPECIALS_SEASON,
                video, subtitles, indexOffset + position, now
            )
        }
        return out
    }

    /** Episodes that live directly in the series folder, with no season directory. */
    private fun episodesInPlace(
        library: LibraryDto,
        seriesId: String,
        seriesName: String,
        seasonNumberHint: Int,
        container: DavEntry,
        videos: List<DavEntry>,
        subtitles: List<DavEntry>,
        now: Long
    ): List<ItemRecord> {
        val seasonNumber = seasonNumberHint.takeIf { it > 0 } ?: 1
        val seasonId = itemId(library.id, container.path + "#season$seasonNumber")
        val out = mutableListOf<ItemRecord>()
        out += ItemRecord(
            dto = MediaItemDto(
                id = seasonId,
                libraryId = library.id,
                kind = ItemKind.SEASON,
                parentId = seriesId,
                seriesId = seriesId,
                seriesName = seriesName,
                name = "第 $seasonNumber 季",
                sortName = seasonNumber.toString().padStart(4, '0'),
                indexNumber = seasonNumber,
                path = container.path
            ),
            dateCreated = now,
            dateModified = now
        )
        out += videos.mapIndexed { position, video ->
            episodeRecord(library, seriesId, seriesName, seasonId, seasonNumber, video, subtitles, position, now)
        }
        return out
    }

    private fun episodeRecord(
        library: LibraryDto,
        seriesId: String,
        seriesName: String,
        seasonId: String,
        seasonNumber: Int,
        video: DavEntry,
        subtitles: List<DavEntry>,
        fallbackIndex: Int,
        now: Long
    ): ItemRecord {
        val parsed = NameParser.parseEpisode(video.name, seasonNumber)
        val episodeNumber = parsed?.episode ?: (fallbackIndex + 1)
        // `S01E01-E02` is one file holding two episodes. The parser has always
        // read the second number; nothing read it back, so the list showed
        // 1, 3, 4 and looked as though a file had been missed.
        val endEpisode = parsed?.endEpisode?.takeIf { it > episodeNumber }
        val name = parsed?.title?.takeIf { it.isNotBlank() }
            ?: endEpisode?.let { "第 $episodeNumber-$it 集" }
            ?: "第 $episodeNumber 集"
        return ItemRecord(
            dto = MediaItemDto(
                id = itemId(library.id, video.path),
                libraryId = library.id,
                kind = ItemKind.EPISODE,
                parentId = seasonId,
                seriesId = seriesId,
                seriesName = seriesName,
                name = name,
                sortName = "%04d%04d".format(parsed?.season ?: seasonNumber, episodeNumber),
                indexNumber = episodeNumber,
                parentIndexNumber = parsed?.season ?: seasonNumber,
                path = video.path,
                sizeBytes = video.size,
                mediaStreams = externalSubtitleStreams(
                    video,
                    subtitlesFor(video, subtitles, emptyList(), soleVideo = false)
                )
            ),
            dateCreated = now,
            dateModified = now,
            etag = video.etag
        )
    }

    // ------------------------------------------------------------ subtitles

    /**
     * External subtitle files are matched by base name. Indices start at
     * [EXTERNAL_STREAM_BASE] so they can never clash with the track numbers that
     * [MkvProbe] reports for embedded streams.
     */
    /**
     * The subtitle files that belong to a video: the ones beside it, plus the
     * contents of any `Subs` / `字幕` folder in the same directory.
     *
     * Only files whose name matches the video are taken, except where the
     * directory holds exactly one video — then everything is fair game, which
     * is what rescues the very common case of a folder named after the release
     * group with subtitles whose names carry a language tag the parser has
     * never seen ("简日双语", "CHS&JPN").
     */
    private fun subtitlesFor(
        video: DavEntry,
        siblings: List<DavEntry>,
        nested: List<DavEntry>,
        soleVideo: Boolean
    ): List<DavEntry> {
        val base = video.name.substringBeforeLast('.')
        val all = siblings + nested
        val matched = all.filter { candidate ->
            NameParser.parseSubtitle(candidate.name)
                ?.videoBaseName?.equals(base, ignoreCase = true) == true
        }
        if (matched.isNotEmpty() || !soleVideo) return matched
        return all
    }

    /** Subtitle files inside a `Subs` / `字幕` folder next to the video. */
    private fun nestedSubtitles(children: List<DavEntry>): List<DavEntry> =
        children.filter { it.isDirectory && NameParser.isSubtitleFolder(it.name) }
            .flatMap { folder ->
                runCatching { dav.list(folder.path) }.getOrDefault(emptyList())
                    .filter { !it.isDirectory && NameParser.isSubtitleFile(it.name) }
            }

    private fun externalSubtitleStreams(video: DavEntry, candidates: List<DavEntry>): List<MediaStreamDto> {
        var next = EXTERNAL_STREAM_BASE
        return candidates.mapNotNull { candidate ->
            val parsed = NameParser.parseSubtitle(candidate.name) ?: return@mapNotNull null
            MediaStreamDto(
                index = next++,
                type = StreamType.SUBTITLE,
                codec = parsed.extension,
                language = parsed.language,
                title = parsed.title,
                isDefault = parsed.isDefault,
                isForced = parsed.isForced,
                isExternal = true,
                externalPath = candidate.path
            )
        }
    }

    companion object {
        const val EXTERNAL_STREAM_BASE = 1000

        /** Stable, collision-resistant id derived from the library and the path. */
        fun itemId(libraryId: String, path: String): String = digest("$libraryId|$path")

        /**
         * Derived from the path rather than drawn at random, because two devices
         * that scan the same share have to arrive at the same id on their own.
         * Item ids hang off the library id, so a random one would give every
         * device its own set of item ids and the watch state would never line up
         * — and the sync file would carry the same folder twice.
         */
        fun libraryId(path: String): String = digest("library|" + normalisePath(path))

        /** Trailing slashes and case are not part of what a folder *is*. */
        private fun normalisePath(path: String): String =
            "/" + path.trim().trim('/').lowercase()

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(value.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }
    }
}
