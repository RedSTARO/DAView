package com.daview.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.daview.app.platform.createCoreContext
import com.daview.server.ServerContext
import com.daview.server.config.StorageConfig
import com.daview.server.db.DOWNLOAD_KIND_VIDEO
import com.daview.server.db.ItemRecord
import com.daview.server.db.Repository
import com.daview.server.media.ImageCache
import com.daview.shared.model.DownloadDto
import com.daview.shared.model.DownloadState
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.PersonDto
import com.daview.shared.model.UserDataDto
import java.io.File

/** Persistent synthetic media for the disposable side-by-side APK, never the release APK. */
internal object AdaptiveCatalogueFixture {
    const val PACKAGE = "com.daview.app.debug"
    const val FILMS = "Demo Films"
    const val SHOWS = "Demo Shows"
    const val FILM_TITLE = "Demo Film 001"
    const val PLAYABLE_FILM_TITLE = "Demo Film 002"
    const val SERIES_TITLE = "Demo Fold Series"
    const val FILM_COUNT = 96
    const val LAST_FILM_TITLE = "Demo Film 096"
    const val SEARCH_GENRE = "Demo Archive"

    private const val ROOT = "/daview-adaptive-demo"
    private const val PREFIX = "adaptive-ui-"
    private const val FILM_LIBRARY = "${PREFIX}films"
    private const val SHOW_LIBRARY = "${PREFIX}shows"
    private const val SERIES_ID = "${PREFIX}series"
    private const val SAMPLE_ASSET = "fold-sample.mp4"
    private const val SAMPLE_FILM_INDEX = 2
    private const val SAMPLE_RUNTIME_MS = 120_000L
    private const val STAMP = 1_735_689_600_000L

    fun seed(target: Context, testContext: Context) {
        check(target.packageName == PACKAGE && target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Install a disposable $PACKAGE build with -PdaviewSideBySide=true before running this test."
        }
        val core = createCoreContext()
        check(core.config.storage == StorageConfig() && core.webdav() == null && !core.config.sync.enabled) {
            "Refusing to seed a debug app with storage credentials or sync enabled. Use a fresh $PACKAGE profile."
        }
        val libraries = listOf(
            LibraryDto(FILM_LIBRARY, FILMS, LibraryKind.MOVIE, "$ROOT/films", listOf(MetadataProvider.NONE)),
            LibraryDto(SHOW_LIBRARY, SHOWS, LibraryKind.SERIES, "$ROOT/shows", listOf(MetadataProvider.NONE))
        )
        val items = films() + series()
        val beforeLibraries = core.repository.libraries()
        val knownLibraries = libraries.associate { it.id to it.path }
        val knownItems = items.associate { it.id to it.libraryId }
        val sample = items.single { it.id == filmId(SAMPLE_FILM_INDEX) }
        val samplePath = checkNotNull(sample.path)
        val sampleFile = File(core.offline.defaultDirectory().toFile(), "adaptive-ui/$SAMPLE_ASSET")
        check(beforeLibraries.all { knownLibraries[it.id] == it.path } &&
            core.repository.itemRecordsPage(items.size + 1, 0).all { knownItems[it.dto.id] == it.dto.libraryId } &&
            core.repository.allUserData().all { it.itemId in knownItems } &&
            core.repository.downloads().all {
                it.itemId == sample.id && it.state == DownloadState.DONE && it.file == sampleFile.absolutePath
            } && core.repository.downloadFiles(sample.id).all {
                it.mediaPath == samplePath && it.isVideo && it.state == DownloadState.DONE && it.file == sampleFile.absolutePath
            }
        ) {
            "Refusing to seed a debug app with an unrelated catalogue or downloads. Use a fresh $PACKAGE profile."
        }

        // All artwork and sample resolution are local. With no DAV URL, no startup scan is needed;
        // the debuggable application already disables automatic update checks. No settings are edited.
        val art = File(core.configStore.dataDir.toFile(), "adaptive-ui-art").apply { mkdirs() }
        val posters = listOf(0xFF314C77, 0xFF44644C, 0xFF793F57).mapIndexed { index, color ->
            artwork(art, "poster-$index.png", 160, 240, color.toInt())
        }
        val backdrop = artwork(art, "backdrop.png", 480, 270, 0xFF375777.toInt())
        installSample(testContext, sampleFile)
        libraries.forEach { core.repository.upsertLibrary(it) }
        core.repository.upsertItems(items.mapIndexed { index, item ->
            ItemRecord(
                item.copy(
                    posterUrl = posters[index % posters.size],
                    backdropUrl = backdrop,
                    sizeBytes = if (item.id == sample.id) sampleFile.length() else null
                ),
                dateCreated = STAMP + index,
                dateModified = STAMP + index
            )
        })
        registerSample(core, sample, samplePath, sampleFile)
        core.repository.restoreUserData(
            filmId(1), UserDataDto(positionMs = 615_000L, favorite = true, lastPlayedAt = STAMP + 10_000L)
        )
        core.repository.restoreUserData(SERIES_ID, UserDataDto(favorite = true))
        core.repository.restoreUserData(
            episodeId(1, 1), UserDataDto(played = true, playCount = 1, lastPlayedAt = STAMP)
        )
        check(core.repository.library(FILM_LIBRARY)?.itemCount == FILM_COUNT)
        check(core.repository.children(SERIES_ID).size == 2)
    }

    private fun installSample(testContext: Context, destination: File) {
        val directory = checkNotNull(destination.parentFile)
        check(directory.mkdirs() || directory.isDirectory)
        val temporary = File.createTempFile("fold-sample-", ".mp4", directory)
        try {
            testContext.assets.open(SAMPLE_ASSET).use { asset ->
                temporary.outputStream().use { asset.copyTo(it) }
            }
            check(temporary.length() > 0L)
            check(temporary.renameTo(destination)) { "Could not install the synthetic offline sample" }
        } finally {
            temporary.delete()
        }
    }

    private fun registerSample(core: ServerContext, film: MediaItemDto, mediaPath: String, file: File) {
        val size = file.length()
        core.repository.saveDownload(
            DownloadDto(film.id, film.name, DownloadState.DONE, totalBytes = size, downloadedBytes = size),
            file.absolutePath,
            mediaPath
        )
        core.repository.saveDownloadFile(
            Repository.DownloadFileRow(
                film.id, mediaPath, file.absolutePath, DOWNLOAD_KIND_VIDEO, DownloadState.DONE,
                totalBytes = size, downloadedBytes = size
            )
        )
        check(core.offline.isComplete(film.id) && core.offline.localFile(mediaPath) == file.toPath())
    }

    private fun number(value: Int) = value.toString().padStart(3, '0')
    private fun filmId(index: Int) = "${PREFIX}film-${number(index)}"
    private fun seasonId(season: Int) = "$SERIES_ID-season-$season"
    private fun episodeId(season: Int, episode: Int) = "${seasonId(season)}-episode-$episode"

    private fun films() = (1..FILM_COUNT).map { index ->
        val title = "Demo Film ${number(index)}"
        MediaItemDto(
            id = filmId(index), libraryId = FILM_LIBRARY, kind = ItemKind.MOVIE, name = title,
            // Make the last card reachable through the actual alphabet jump without a fragile
            // device-speed-dependent sequence of dozens of synthetic flings.
            sortName = if (index == FILM_COUNT) "E $title" else title, year = 2000 + index % 25,
            runtimeMs = if (index == SAMPLE_FILM_INDEX) SAMPLE_RUNTIME_MS else 5_400_000L,
            genres = listOf(when {
                index == 1 -> SEARCH_GENRE
                index % 2 == 0 -> "Mystery"
                else -> "Adventure"
            }),
            people = listOf(PersonDto("Avery Demo", "Archivist")),
            overview = "Offline fixture film ${number(index)}. An archivist follows a map of a city that changes shape.",
            path = "$ROOT/films/$title.${if (index == SAMPLE_FILM_INDEX) "mp4" else "mkv"}"
        )
    }

    private fun series() = buildList {
        add(MediaItemDto(
            id = SERIES_ID, libraryId = SHOW_LIBRARY, kind = ItemKind.SERIES,
            name = SERIES_TITLE, sortName = SERIES_TITLE, year = 2025, genres = listOf("Adventure"),
            overview = "An entirely synthetic series with two seasons for checking adaptive detail and episode navigation."
        ))
        for (season in 1..2) {
            add(MediaItemDto(
                id = seasonId(season), libraryId = SHOW_LIBRARY, kind = ItemKind.SEASON, parentId = SERIES_ID,
                name = "Demo Season $season", sortName = "Demo Season $season", indexNumber = season
            ))
            for (episode in 1..18) {
                val title = "Demo S${season}E${number(episode)}"
                add(MediaItemDto(
                    id = episodeId(season, episode), libraryId = SHOW_LIBRARY, kind = ItemKind.EPISODE,
                    parentId = seasonId(season), seriesId = SERIES_ID, name = title, sortName = title,
                    parentIndexNumber = season, indexNumber = episode, runtimeMs = 1_440_000L,
                    overview = "Fixture season $season, episode $episode. No video bytes are installed.",
                    path = "$ROOT/shows/Season $season/$title.mkv"
                ))
            }
        }
    }

    private fun artwork(directory: File, name: String, width: Int, height: Int, color: Int): String {
        val file = File(directory, name)
        if (!file.isFile || file.length() == 0L) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(color)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFF2CB80.toInt() }
                canvas.drawCircle(width * 0.66f, height * 0.32f, minOf(width, height) * 0.18f, paint)
                paint.color = 0xFF17243F.toInt()
                canvas.drawRect(width * 0.47f, height * 0.48f, width.toFloat(), height.toFloat(), paint)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
        }
        return "${ImageCache.FILE_SCHEME}${file.absolutePath}"
    }
}
