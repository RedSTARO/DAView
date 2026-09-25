package com.daview.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.app.data.AppState
import com.daview.app.data.Confirmation
import com.daview.app.data.ModalMarker
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.platform.openUrl
import com.daview.app.platform.pickImageFile
import com.daview.app.theme.favoriteColor
import com.daview.shared.model.DownloadDto
import com.daview.shared.model.DownloadState
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.PlayedState
import com.daview.shared.model.SUBTITLE_OFF
import com.daview.shared.model.ScrapeStatus
import com.daview.shared.model.StreamType
import kotlinx.coroutines.launch

/** The widest the text-bearing parts of the page run on a wide window. */
private val ReadingWidth = 1100.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailScreen(state: AppState, playback: PlaybackController, itemId: String) {
    val item = state.detailItem?.takeIf { it.id == itemId }
    if (item == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(title = null, onBack = if (state.canGoBack) ({ state.back() }) else null)
            val error = state.detailError
            when {
                error != null -> EmptyState(title = "读取失败", description = error) {
                    Button(onClick = { state.loadDetail(itemId) }) { Text("重试") }
                }
                else -> LoadingPane()
            }
        }
        return
    }
    val seasons = state.detailChildren.filter { it.kind == ItemKind.SEASON }
    val relatedMovies = state.detailChildren.filter { it.kind == ItemKind.MOVIE }
    val selectedSeason = seasons.firstOrNull { it.id == state.detailSeasonId }
    val listState = rememberLazyListState()

    // Opened from its own episode's row on a busy series, the page scrolls to
    // the episodes rather than the cast.
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { DetailHeader(state, playback, item) }

            // The episodes come first: on a series page they are what the page
            // is for, and they used to sit under the cast and the file details.
            if (seasons.isNotEmpty()) {
                item {
                    Column(Modifier.readingWidth()) {
                        SectionHeader("剧集")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons, key = { it.id }) { season ->
                                ToggleButton(
                                    checked = state.detailSeasonId == season.id,
                                    onCheckedChange = { state.selectSeason(season.id) }
                                ) {
                                    Text(season.name)
                                    val total = season.episodeCount ?: 0
                                    if (total > 0) {
                                        Text(
                                            "  ${season.playedEpisodeCount ?: 0}/$total",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                        selectedSeason?.let { season -> SeasonActions(state, season) }
                        selectedSeason?.path?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                "目录: $it",
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(state.detailEpisodes, key = { it.id }) { episode ->
                    Box(Modifier.readingWidth()) {
                        EpisodeRow(
                            episode,
                            basePath = selectedSeason?.path,
                            isNext = episode.id == state.detailNextUp?.id,
                            download = state.downloadOf(episode.id),
                            onPlay = { playback.play(episode) },
                            onOpen = { state.navigate(Screen.Detail(episode.id)) },
                            menu = { dismiss -> ItemMenuItems(state, playback, episode, dismiss) }
                        ) {
                            state.togglePlayed(episode)
                        }
                    }
                }
            }

            if (item.kind == ItemKind.EPISODE && state.detailEpisodes.isNotEmpty()) {
                item { SeasonEpisodesRow(state, playback, item, state.detailEpisodes) }
            }

            if (relatedMovies.isNotEmpty()) {
                item {
                    MediaRow(
                        "相关影片",
                        relatedMovies,
                        menu = cardMenu(state, playback),
                        onItemPlay = { playback.play(it) }
                    ) {
                        state.navigate(Screen.Detail(it.id))
                    }
                }
            }

            if (item.people.isNotEmpty()) {
                item { PeopleRow(state, item) }
            }

            item { Box(Modifier.readingWidth()) { FileInfoSection(item) } }
        }
        VerticalScrollbarFor(listState, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))

        // Over the art rather than in a band above it, so the picture runs to
        // the top of the window.
        if (state.canGoBack) {
            Tooltip("返回（Alt + ←）") {
                FilledIconButton(
                    onClick = { state.back() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 12.dp, top = 12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        }
    }
}

/** Left-aligned, capped to a reading width, on a window of any size. */
private fun Modifier.readingWidth() = fillMaxWidth().wrapContentWidth(Alignment.Start).widthIn(max = ReadingWidth)

/** 「整季标记」 for the season on screen, which asks before changing a whole season. */
@Composable
private fun SeasonActions(state: AppState, season: MediaItemDto) {
    var confirm by remember { mutableStateOf(false) }
    val played = season.playedState == PlayedState.PLAYED
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            watchedLabel(season),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        SeasonDownloadButton(state, season)
        TextButton(onClick = { confirm = true }) {
            Text(if (played) "整季标记未看" else "整季标记已看")
        }
    }
    if (confirm) {
        ConfirmMarkDialog(season, onConfirm = { state.togglePlayed(season) }, onDismiss = { confirm = false })
    }
}

/**
 * Asks before marking a whole season or series. Marking clears where each
 * episode had got to, and one tap used to do that to every episode at once.
 */
@Composable
fun ConfirmMarkDialog(item: MediaItemDto, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalMarker()
    val played = item.playedState == PlayedState.PLAYED
    val count = item.episodeCount ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (played) "把 $count 集都标记为未观看？" else "把 $count 集都标记为已观看？") },
        text = {
            Text(
                "「${item.name}」下的每一集都会改，已有的播放进度会被清除。" +
                    "改完后提示条上可以撤销。"
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(if (played) "全部标记未看" else "全部标记已看") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * Where the metadata came from, and how much it should be trusted. A fallback
 * match is the one worth acting on: it means no source matched confidently and
 * the least-bad candidate was taken.
 */
private fun scrapeLabel(item: MediaItemDto): String {
    val ids = item.providerIds.entries
        .filter { it.key != "imdb" }
        .joinToString(" · ") { "${it.key} ${it.value}" }
        .ifBlank { "无来源 id" }
    val base = when (item.scrapeStatus) {
        ScrapeStatus.MANUAL -> if (item.lockedProvider != null) "手动指定 · $ids" else "手动编辑 · $ids"
        ScrapeStatus.MATCHED -> "自动匹配 · $ids"
        ScrapeStatus.FALLBACK -> "次级来源顶替（未可靠匹配，建议核对） · $ids"
        ScrapeStatus.UNMATCHED -> "所有来源都没有匹配"
        ScrapeStatus.NONE -> if (item.scrapedAt != null) "已刮削 · $ids" else "尚未刮削"
    }
    return if (item.manualFields.isNotEmpty()) "$base · ${item.manualFields.size} 个字段为手动填写" else base
}

/**
 * One line of watched state for any kind of item: a film or episode says where
 * it stopped, a series or season how much of it is done.
 */
private fun watchedLabel(item: MediaItemDto): String = when {
    item.isPlayable -> when {
        item.userData.played -> "已看完"
        item.userData.positionMs > 0 -> "看到 ${formatDuration(item.userData.positionMs)}"
        else -> "未观看"
    }
    (item.episodeCount ?: 0) == 0 -> "未观看"
    item.playedEpisodeCount == item.episodeCount -> "已看完 ${item.episodeCount} 集"
    else -> "已看 ${item.playedEpisodeCount ?: 0} / ${item.episodeCount} 集"
}

/** Where a provider shows an id, so the ids on the page can be opened there. */
private fun providerUrl(key: String, id: String, item: MediaItemDto): String? = when (key.lowercase()) {
    "tmdb" -> "https://www.themoviedb.org/${if (item.kind == ItemKind.MOVIE) "movie" else "tv"}/$id"
    "tvdb" -> "https://thetvdb.com/dereferrer/${if (item.kind == ItemKind.MOVIE) "movie" else "series"}/$id"
    "bangumi" -> "https://bgm.tv/subject/$id"
    "imdb" -> "https://www.imdb.com/title/$id"
    else -> null
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetailHeader(state: AppState, playback: PlaybackController, item: MediaItemDto) {
    // A season or an episode names the show it belongs to; the show itself has
    // no parent to point at, so the label stays plain text there.
    val openSeries: (() -> Unit)? = item.seriesId?.let { id ->
        { state.navigate(Screen.Detail(id)) }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
        // The band is only as tall as the column beside the poster, and that
        // height is not known until the column has been measured.
        // `matchParentSize` runs in Box's second pass and so picks it up;
        // `fillMaxSize` cannot, because a LazyColumn item is measured with an
        // unbounded height and the art then falls back to its own pixel size.
        val art = item.backdropUrl ?: item.posterUrl
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
        // Title and overview sit over the whole band rather than just its foot,
        // so the scrim stays dense all the way up.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                    0.5f to MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                    1f to MaterialTheme.colorScheme.background
                )
            )
        )

        // A phone cannot carry the poster and everything beside it at once. At
        // 400dp wide the poster and the padding leave that column about 130dp
        // — narrower than the play button on its own — so the row of actions
        // ran off the screen with the external-player button squeezed into a
        // stack of single characters. Under the threshold the blurb and the
        // actions take the full width instead, below a smaller poster.
        val sideBySide = maxWidth >= 600.dp
        // Clear of the status bar and of the back button that floats over the art.
        val top = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 56.dp)

        if (sideBySide) {
            Row(top.padding(24.dp).widthIn(max = ReadingWidth), verticalAlignment = Alignment.Bottom) {
                HeroPoster(item, width = 160.dp, height = 240.dp, gap = 20.dp)
                Column(Modifier.weight(1f)) {
                    HeroTitle(state, item, openSeries)
                    HeroBlurb(state, item)
                    Spacer(Modifier.height(18.dp))
                    PlayActions(state, playback, item)
                }
            }
        } else {
            Column(top.padding(20.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    HeroPoster(item, width = 120.dp, height = 180.dp, gap = 16.dp)
                    Column(Modifier.weight(1f)) { HeroTitle(state, item, openSeries) }
                }
                HeroBlurb(state, item)
                Spacer(Modifier.height(16.dp))
                PlayActions(state, playback, item)
            }
        }
    }
}

@Composable
private fun HeroPoster(item: MediaItemDto, width: Dp, height: Dp, gap: Dp) {
    Surface(
        modifier = Modifier.width(width).height(height),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        // Holds its place when there is no artwork. Bailing out here took the
        // gap with it, so a title with a poster and one without laid the whole
        // header out 180dp apart.
        if (item.posterUrl == null) {
            ArtworkPlaceholder(item.kind, Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    Spacer(Modifier.width(gap))
}

/** What names the item: the show it belongs to, its own title, and its facts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.HeroTitle(state: AppState, item: MediaItemDto, openSeries: (() -> Unit)?) {
    item.seriesName?.let { name ->
        LinkText(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            onClick = openSeries
        )
    }
    Text(item.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    item.originalName?.takeIf { it != item.name }?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(10.dp))
    // A film carries up to five of these, which is more than sits beside a
    // poster on a phone, so they wrap instead of running off the edge.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item.episodeLabel?.let { Chip(it) }
        // The premiere date where there is one, the year otherwise.
        formatDate(item.premiereDate)?.let { Chip(it) } ?: item.year?.let { Chip("${it} 年") }
        item.officialRating?.takeIf { it.isNotBlank() }?.let { Chip(it) }
        formatRuntime(item.runtimeMs)?.let { Chip(it) }
        item.communityRating?.let {
            // Whose score it is: a TMDB 7 and a bangumi 7 are not the same thing.
            val source = item.communityRatingSource?.displayName ?: "评分"
            Chip("$source ${(it * 10).toInt() / 10.0}")
        }
        item.childCount?.takeIf { item.kind == ItemKind.SERIES }?.let { Chip("$it 季") }
        Chip(watchedLabel(item))
        // Where the copy on this device has got to. A series or a season says
        // how many of its episodes are here.
        val download = state.downloadOf(item.id)
        when {
            item.isPlayable && download?.state == DownloadState.DONE -> Chip(
                if (download.subtitleCount > 0) "已下载到本机 · 含 ${download.subtitleCount} 条字幕" else "已下载到本机",
                color = MaterialTheme.colorScheme.tertiary
            )
            item.isPlayable && download?.active == true -> Chip(
                if (download.state == DownloadState.RUNNING) "正在下载 ${(download.fraction * 100).toInt()}%"
                else download.note ?: "等待下载"
            )
            !item.isPlayable -> {
                val done = state.downloadsUnder(item).count { it.state == DownloadState.DONE }
                if (done > 0) Chip("已下载 $done/${item.episodeCount ?: done} 集", color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/** Genres and synopsis — the part that reads best across the full width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.HeroBlurb(state: AppState, item: MediaItemDto) {
    if (item.genres.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        // Each genre leads to the other titles that carry it.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item.genres.forEach { genre ->
                Chip(genre, onClick = { state.searchFor(genre) })
            }
        }
    }

    item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
        Spacer(Modifier.height(12.dp))
        var expanded by remember(item.id) { mutableStateOf(false) }
        var overflowing by remember(item.id) { mutableStateOf(false) }
        SelectionContainer {
            Text(
                overview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                modifier = Modifier.widthIn(max = 760.dp)
            )
        }
        // Said in words: tapping the text did expand it, but nothing on the
        // page told anyone that it could.
        if (overflowing || expanded) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (expanded) "收起" else "展开全文")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayActions(state: AppState, playback: PlaybackController, item: MediaItemDto) {
    // A series or season plays where the viewer is up to across every season —
    // the same episode the core would land on — not the first unwatched episode
    // of whichever season happens to be selected.
    val target = if (item.isPlayable) item else state.detailNextUp
    var playerMenu by remember { mutableStateOf(false) }
    var confirmMark by remember { mutableStateOf(false) }

    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            if (target != null) {
                Button(onClick = { playback.play(target) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    val label = target.episodeLabel?.takeIf { !item.isPlayable }
                    Text(
                        when {
                            target.userData.positionMs > 0 ->
                                listOfNotNull("继续", label, formatDuration(target.userData.positionMs)).joinToString(" ")
                            else -> listOfNotNull("播放", label).joinToString(" ")
                        }
                    )
                }
                // Starting again is one tap here too, not only in a card's menu.
                if (target.userData.positionMs > 0) {
                    OutlinedButton(onClick = { playback.play(target, startPositionMs = 0L) }) {
                        Icon(Icons.Filled.Replay, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("从头播放")
                    }
                }

                if (playback.externalPlayers.isNotEmpty()) {
                    Box {
                        FilledTonalButton(onClick = { playerMenu = true }) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("外部播放器")
                        }
                        AppMenu(expanded = playerMenu, onDismissRequest = { playerMenu = false }) {
                            playback.externalPlayers.forEach { player ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(player.label)
                                            player.executablePath?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        playerMenu = false
                                        playback.playExternal(target, player)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            TipIconButton(if (item.userData.favorite) "取消收藏" else "收藏", onClick = { state.toggleFavorite(item) }) {
                Icon(
                    if (item.userData.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (item.userData.favorite) "取消收藏" else "收藏",
                    tint = if (item.userData.favorite) favoriteColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Series and seasons get the toggle too: marking one watched marks
            // every episode under it, which is the only thing "watched" can mean
            // for something that has no bytes of its own — and so it asks first.
            if (item.kind != ItemKind.SEASON || (item.episodeCount ?: 0) > 0) {
                val playedTip = if (item.playedState == PlayedState.PLAYED) "标记为未观看" else "标记为已观看"
                TipIconButton(playedTip, onClick = {
                    if (item.isPlayable) state.togglePlayed(item) else confirmMark = true
                }) {
                    WatchedIcon(item.playedState, playedTip)
                }
            }
            DownloadAction(state, item)
            DetailMoreMenu(state, playback, item, target)
        }

        // On a series page what played is its episode, whose failure is filed
        // under the episode's id.
        (playback.errorFor(item.id) ?: target?.let { playback.errorFor(it.id) })?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        target?.takeIf { it.isPlayable }?.let { playable ->
            TrackPickers(state, playable)
        }
    }

    if (confirmMark) {
        ConfirmMarkDialog(item, onConfirm = { state.togglePlayed(item) }, onDismiss = { confirmMark = false })
    }
}

/** Filled tick for watched, a partly drawn ring for started, an empty ring for neither. */
@Composable
private fun WatchedIcon(played: PlayedState, description: String) {
    when (played) {
        PlayedState.PLAYED -> Icon(Icons.Filled.CheckCircle, contentDescription = description, tint = MaterialTheme.colorScheme.primary)
        PlayedState.PARTIAL -> Icon(Icons.Filled.Timelapse, contentDescription = description, tint = MaterialTheme.colorScheme.primary)
        PlayedState.NONE -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One word on an episode row for where its copy has got to, or nothing. */
private fun downloadLabel(download: DownloadDto?): String? = when (download?.state) {
    DownloadState.DONE -> "已下载"
    DownloadState.RUNNING -> "下载中 ${(download.fraction * 100).toInt()}%"
    DownloadState.QUEUED -> download.note ?: "等待下载"
    DownloadState.FAILED -> "下载失败"
    null -> null
}

/**
 * The download button. What it says and does follows where the copy has got
 * to: a film or an episode is one file, fetched, cancelled or deleted; a series
 * or a season is its episodes, which are queued, cancelled and deleted as one.
 */
@Composable
private fun DownloadAction(state: AppState, item: MediaItemDto) {
    if (item.isPlayable) {
        val download = state.downloadOf(item.id)
        val (tip, icon, tint) = when (download?.state) {
            DownloadState.DONE -> Triple("删除本地文件…", Icons.Filled.DownloadDone, MaterialTheme.colorScheme.tertiary)
            DownloadState.RUNNING -> Triple(
                "取消下载（${(download.fraction * 100).toInt()}%）", Icons.Filled.Downloading, MaterialTheme.colorScheme.primary
            )
            DownloadState.QUEUED -> Triple(
                "取消下载（${download.note ?: "排队中"}）", Icons.Filled.Downloading, MaterialTheme.colorScheme.primary
            )
            DownloadState.FAILED -> Triple(
                "重新下载（上次失败：${download.error ?: "未知原因"}）", Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error
            )
            null -> Triple("下载到本机（连同外挂字幕）", Icons.Filled.Download, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TipIconButton(tip, onClick = {
            when (download?.state) {
                // Deleting a finished copy asks first: the bytes took minutes to arrive.
                DownloadState.DONE -> state.confirm(
                    Confirmation(
                        title = "删除「${item.name}」的本地文件？",
                        text = ("删除后要再联网播放或重新下载。" + formatSize(download.totalBytes)).trim(),
                        confirmLabel = "删除",
                        destructive = true,
                        action = { state.removeDownload(item.id) }
                    )
                )
                DownloadState.RUNNING, DownloadState.QUEUED -> state.cancelDownload(item)
                else -> state.download(item)
            }
        }) {
            Icon(icon, contentDescription = tip, tint = tint)
        }
        return
    }

    val total = item.episodeCount ?: 0
    if (total == 0) return
    val under = state.downloadsUnder(item)
    val pending = under.count { it.active }
    val done = under.count { it.state == DownloadState.DONE }
    val scope = if (item.kind == ItemKind.SEASON) "本季" else "全部"
    when {
        pending > 0 -> TipIconButton("取消下载（$pending 集）", onClick = { state.cancelDownloadsUnder(item) }) {
            Icon(Icons.Filled.Downloading, contentDescription = "取消下载", tint = MaterialTheme.colorScheme.primary)
        }
        done >= total -> TipIconButton("删除本地文件（$done 集）…", onClick = {
            state.confirm(
                Confirmation(
                    title = "删除「${item.name}」的 $done 个本地文件？",
                    text = ("删除后要再联网播放或重新下载。" +
                        formatSize(under.filter { it.state == DownloadState.DONE }.sumOf { it.totalBytes })).trim(),
                    confirmLabel = "删除",
                    destructive = true,
                    action = { state.removeDownloadsUnder(item) }
                )
            )
        }) {
            Icon(Icons.Filled.DownloadDone, contentDescription = "删除本地文件", tint = MaterialTheme.colorScheme.tertiary)
        }
        else -> TipIconButton("下载$scope（$total 集）…", onClick = { state.download(item) }) {
            Icon(Icons.Filled.Download, contentDescription = "下载$scope", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The season's own download control, beside its watched toggle. */
@Composable
private fun SeasonDownloadButton(state: AppState, season: MediaItemDto) {
    val under = state.downloadsUnder(season)
    val pending = under.count { it.active }
    val done = under.count { it.state == DownloadState.DONE }
    val total = season.episodeCount ?: 0
    when {
        pending > 0 -> TextButton(onClick = { state.cancelDownloadsUnder(season) }) { Text("取消下载（$pending 集）") }
        total > 0 && done >= total -> TextButton(onClick = {
            state.confirm(
                Confirmation(
                    title = "删除「${season.name}」的 $done 个本地文件？",
                    text = ("删除后要再联网播放或重新下载。" +
                        formatSize(under.filter { it.state == DownloadState.DONE }.sumOf { it.totalBytes })).trim(),
                    confirmLabel = "删除",
                    destructive = true,
                    action = { state.removeDownloadsUnder(season) }
                )
            )
        }) { Text("删除本季文件…") }
        else -> TextButton(onClick = { state.download(season) }) {
            Text(if (done > 0) "下载本季（还差 ${total - done} 集）…" else "下载本季…")
        }
    }
}

/**
 * The rarely needed things — fixing the metadata, merging a duplicate, keeping
 * a copy — kept in one menu. They sat as a row of look-alike pencil icons at
 * the same weight as play and favourite.
 */
@Composable
private fun DetailMoreMenu(state: AppState, playback: PlaybackController, item: MediaItemDto, target: MediaItemDto?) {
    var open by remember { mutableStateOf(false) }
    var identifyOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var mergeOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val whole = item.kind == ItemKind.MOVIE || item.kind == ItemKind.SERIES

    if (identifyOpen) IdentifyDialog(state, item, onDismiss = { identifyOpen = false })
    if (editOpen) EditItemDialog(state, item, onDismiss = { editOpen = false })
    if (mergeOpen) MergeDialog(state, item, onDismiss = { mergeOpen = false })

    Box {
        TipIconButton("更多", onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            if (whole) {
                DropdownMenuItem(text = { Text("编辑条目信息…") }, onClick = { open = false; editOpen = true })
                DropdownMenuItem(
                    text = { Text("更换海报…") },
                    onClick = {
                        open = false
                        scope.launch {
                            pickImageFile()?.let { state.setArtwork(item, "poster", it.bytes, it.extension) }
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("更换背景图…") },
                    onClick = {
                        open = false
                        scope.launch {
                            pickImageFile()?.let { state.setArtwork(item, "backdrop", it.bytes, it.extension) }
                        }
                    }
                )
                if (item.manualFields.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("恢复为刮削结果") },
                        onClick = { open = false; state.revertManualEdits(item) }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            if (item.lockedProvider != null) "更换手动指定的条目…（当前 ${item.lockedProvider?.displayName}）"
                            else "手动指定刮削条目…"
                        )
                    },
                    onClick = { open = false; identifyOpen = true }
                )
                if (item.lockedProvider != null) {
                    DropdownMenuItem(
                        text = { Text("解除手动指定，重新自动匹配") },
                        onClick = { open = false; state.unpin(item) }
                    )
                }
                DropdownMenuItem(text = { Text("重新刮削") }, onClick = { open = false; state.refreshMetadata(item) })
                DropdownMenuItem(text = { Text("合并重复条目…") }, onClick = { open = false; mergeOpen = true })
            }
            if (item.providerIds.isNotEmpty()) {
                HorizontalDivider()
                item.providerIds.forEach { (key, id) ->
                    providerUrl(key, id, item)?.let { url ->
                        DropdownMenuItem(
                            text = { Text("在 ${key.uppercase()} 上查看") },
                            onClick = { open = false; openUrl(url) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * The audio and subtitle to start on, chosen before pressing play. The file
 * details listed every track and offered no way to pick one; the choice could
 * only be made once the film was running.
 */
@Composable
private fun TrackPickers(state: AppState, item: MediaItemDto) {
    val audio = item.mediaStreams.filter { it.type == StreamType.AUDIO }
    val subtitles = item.mediaStreams.filter { it.type == StreamType.SUBTITLE }
    if (audio.size < 2 && subtitles.isEmpty()) return
    val chosenAudio = item.userData.audioStreamIndex
    val chosenSubtitle = item.userData.subtitleStreamIndex
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (audio.size >= 2) {
            TrackPicker(
                label = "音轨",
                current = audio.firstOrNull { it.index == chosenAudio }?.displayTitle ?: "自动",
                options = listOf(AUTOMATIC to "自动（按首选语言）") + audio.map { it.index to it.displayTitle }
            ) { if (it == AUTOMATIC) state.resetTrackSelection(item, audio = true) else state.setTrackSelection(item, it, null) }
        }
        if (subtitles.isNotEmpty()) {
            TrackPicker(
                label = "字幕",
                current = when (chosenSubtitle) {
                    SUBTITLE_OFF -> "关闭"
                    null -> "自动"
                    else -> subtitles.firstOrNull { it.index == chosenSubtitle }?.displayTitle ?: "自动"
                },
                options = listOf(AUTOMATIC to "自动（按首选语言）") + subtitles.map { it.index to it.displayTitle } +
                    (SUBTITLE_OFF to "关闭字幕")
            ) { if (it == AUTOMATIC) state.resetTrackSelection(item, audio = false) else state.setTrackSelection(item, null, it) }
        }
    }
}

/** The pickers' "no choice of my own" entry; no stream has this index. */
private const val AUTOMATIC = Int.MIN_VALUE

@Composable
private fun TrackPicker(label: String, current: String, options: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("$label：$current", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (index, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(index) })
            }
        }
    }
}

/**
 * What the files themselves say, as opposed to what a scraper wrote. A series or
 * a season only knows the folder it was found in, so the block shrinks to
 * whatever that item actually carries.
 */
@Composable
private fun FileInfoSection(item: MediaItemDto) {
    val video = item.mediaStreams.firstOrNull { it.type == StreamType.VIDEO }
    val audio = item.mediaStreams.filter { it.type == StreamType.AUDIO }
    val subtitles = item.mediaStreams.filter { it.type == StreamType.SUBTITLE }

    val rows = buildList<Pair<String, String>> {
        item.path?.takeIf { it.isNotBlank() }?.let {
            add((if (item.isPlayable) "文件" else "目录") to it)
        }
        formatSize(item.sizeBytes).takeIf { it.isNotBlank() }?.let { add("大小" to it) }
        video?.let { stream ->
            add(
                "画面" to listOfNotNull(
                    stream.codec?.uppercase() ?: "未知",
                    if (stream.width != null && stream.height != null) "${stream.width}×${stream.height}" else null
                ).joinToString(" · ")
            )
        }
        add("刮削" to scrapeLabel(item))
        if (audio.isNotEmpty()) add("音轨" to audio.joinToString("\n") { it.displayTitle })
        if (subtitles.isNotEmpty()) add("字幕" to subtitles.joinToString("\n") { it.displayTitle })
        subtitles.mapNotNull { it.externalPath }.takeIf { it.isNotEmpty() }?.let {
            add("外挂字幕" to it.joinToString("\n"))
        }
    }
    if (rows.isEmpty()) return

    Column {
        SectionHeader("文件信息")
        // The paths are the part worth pulling out of the app, so the whole
        // block is selectable rather than growing a copy button per row.
        SelectionContainer {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rows.forEach { (label, value) -> InfoRow(label, value) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * The season folder is already printed above the list, so an episode row only
 * repeats what sits below it. Falls back to the whole path when the episode is
 * not inside the folder its season was named after.
 */
private fun pathUnder(path: String, base: String?): String {
    if (base.isNullOrBlank()) return path
    return path.removePrefix(base.trimEnd('/') + "/")
}

/**
 * The rest of the run, laid sideways under one episode's page, opened at the
 * episode you are on. A series page gets the vertical list instead; here the
 * page is about a single file and this is the way out of it.
 */
@Composable
private fun SeasonEpisodesRow(
    state: AppState,
    playback: PlaybackController,
    current: MediaItemDto,
    episodes: List<MediaItemDto>
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = episodes.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    )
    // Walking to the next episode reuses this row rather than building a new
    // one, so it has to be told to slide again. The second key names which list
    // this is, not what is in it: crossing into another season re-anchors, while
    // marking an episode watched rebuilds the list without moving the row out
    // from under someone who had scrolled it.
    LaunchedEffect(current.id, episodes.firstOrNull()?.id) {
        episodes.indexOfFirst { it.id == current.id }
            .takeIf { it >= 0 }
            ?.let { listState.scrollToItem(it) }
    }

    Column(Modifier.fillMaxWidth()) {
        // Season 0 is the specials folder, and the scanner already names the
        // season item "特别篇"; "第 0 季" here was the same row calling it
        // something else.
        SectionHeader(
            when (val season = current.parentIndexNumber) {
                null -> "本季剧集"
                0 -> "特别篇"
                else -> "第 $season 季"
            }
        ) {
            Text(
                "${episodes.size} 集",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyRow(
            state = listState,
            modifier = Modifier.wheelScrollsHorizontally(listState),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(episodes, key = { it.id }) { episode ->
                // Every tile in the row is the same show, so the series name the
                // card usually leads with says nothing; the number does.
                PosterCard(
                    episode,
                    width = 232.dp,
                    shape = CardShape.LANDSCAPE,
                    title = episode.episodeLabel ?: episode.name,
                    subtitle = episode.name,
                    downloaded = state.downloadOf(episode.id)?.state == DownloadState.DONE,
                    menu = { dismiss -> ItemMenuItems(state, playback, episode, dismiss) },
                    onPlay = { playback.play(episode) }
                ) { state.navigate(Screen.Detail(episode.id)) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PeopleRow(state: AppState, item: MediaItemDto) {
    Column {
        SectionHeader("演职人员")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(item.people, key = { it.name + (it.role ?: "") }) { person ->
                // A face leads to the other titles it appears in.
                Surface(
                    onClick = { state.searchFor(person.name) },
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.width(96.dp).padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(50)),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            if (person.imageUrl == null) {
                                ArtworkPlaceholder(null, Modifier.fillMaxSize(), person = true)
                            }
                            person.imageUrl?.let {
                                AsyncImage(
                                    model = it,
                                    // The name is right below; saying it twice is noise.
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            person.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        person.role?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: MediaItemDto,
    basePath: String?,
    isNext: Boolean,
    download: DownloadDto?,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    onToggleWatched: () -> Unit
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var lastPointer by remember { mutableStateOf(PointerType.Unknown) }
    val density = LocalDensity.current

    // The menu hangs off the box rather than the card's own content column, so
    // the offset the gesture reported is measured from the same corner it was.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
        // A third of the row, held between something legible and something that
        // does not dwarf the text beside it on a wide window.
        val thumbnailWidth = (maxWidth * 0.32f).coerceIn(96.dp, 220.dp)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .secondaryClick(
                    onPointerType = { lastPointer = it },
                    onOpen = { at -> menuAt = at }
                )
                // A row opens the episode, as a card does everywhere else; the
                // play button on its picture plays it.
                .combinedClickable(
                    onClick = onOpen,
                    onLongClickLabel = "打开菜单",
                    onLongClick = { if (lastPointer != PointerType.Mouse) menuAt = Offset.Zero }
                ),
            colors = CardDefaults.cardColors(
                containerColor = if (isNext) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    // Proportional, not a fixed 148dp: on a 360dp phone the
                    // still, the padding and the trailing button left the text
                    // column 86dp, which is about six Chinese characters of
                    // title with the metadata folded onto three lines.
                    modifier = Modifier
                        .width(thumbnailWidth)
                        .aspectRatio(16f / 9f),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box {
                        if (episode.posterUrl == null) {
                            ArtworkPlaceholder(ItemKind.EPISODE, Modifier.fillMaxSize())
                        }
                        episode.posterUrl?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Surface(
                            onClick = onPlay,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "播放 ${episode.episodeLabel ?: episode.name}",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(6.dp).size(22.dp)
                            )
                        }
                        val progress = episode.userData.playedPercentage.toFloat()
                        if (progress > 0.01f && !episode.userData.played) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                                color = MaterialTheme.colorScheme.primary,
                                // A track under the bar, so a short one reads as
                                // "a little of the way" rather than a stray line.
                                trackColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                                gapSize = 0.dp,
                                drawStopIndicator = {}
                            )
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (isNext) {
                        Text(
                            "接下来",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        listOfNotNull(episode.episodeLabel, episode.name).joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    episode.overview?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            watchedLabel(episode),
                            formatRuntime(episode.runtimeMs),
                            formatSize(episode.sizeBytes).takeIf { it.isNotBlank() },
                            episode.mediaStreams.count { it.type == StreamType.SUBTITLE }
                                .takeIf { it > 0 }?.let { "$it 条字幕" },
                            downloadLabel(download)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    episode.path?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            pathUnder(it, basePath),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                val tip = if (episode.userData.played) "标记为未观看" else "标记为已观看"
                TipIconButton(tip, onClick = onToggleWatched) {
                    WatchedIcon(episode.playedState, tip)
                }
            }
        }

        AppMenu(
            expanded = menuAt != null,
            onDismissRequest = { menuAt = null },
            offset = menuAt.toDpOffset(density)
        ) {
            menu { menuAt = null }
        }
    }
}
