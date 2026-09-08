package com.daview.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlayedState
import com.daview.shared.model.ScrapeStatus
import com.daview.shared.model.StreamType

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailScreen(state: AppState, playback: PlaybackController) {
    val item = state.detailItem ?: return
    val seasons = state.detailChildren.filter { it.kind == ItemKind.SEASON }
    val relatedMovies = state.detailChildren.filter { it.kind == ItemKind.MOVIE }
    val selectedSeason = seasons.firstOrNull { it.id == state.detailSeasonId }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item { DetailHeader(state, playback, item) }

        if (item.people.isNotEmpty()) {
            item { PeopleRow(item) }
        }

        item { FileInfoSection(item) }

        if (seasons.isNotEmpty()) {
            item {
                Column {
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
                            }
                        }
                    }
                    selectedSeason?.let { season ->
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
                            TextButton(onClick = { state.togglePlayed(season) }) {
                                Text(
                                    if (season.playedState == PlayedState.PLAYED) "整季标记未看"
                                    else "整季标记已看"
                                )
                            }
                        }
                    }
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
                EpisodeRow(
                    episode,
                    basePath = selectedSeason?.path,
                    onPlay = { playback.playInternalOrExternal(episode) },
                    menu = { dismiss -> ItemMenuItems(state, playback, episode, dismiss) }
                ) {
                    state.togglePlayed(episode)
                }
            }
        }

        if (item.kind == ItemKind.EPISODE && state.detailEpisodes.isNotEmpty()) {
            item { SeasonEpisodesRow(state, playback, item, state.detailEpisodes) }
        }

        if (relatedMovies.isNotEmpty()) {
            item {
                MediaRow("相关影片", relatedMovies, menu = cardMenu(state, playback)) {
                    state.navigate(Screen.Detail(it.id))
                }
            }
        }
    }
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
    return when (item.scrapeStatus) {
        ScrapeStatus.MANUAL -> "手动指定 · $ids"
        ScrapeStatus.MATCHED -> "自动匹配 · $ids"
        ScrapeStatus.FALLBACK -> "次级来源顶替（未可靠匹配，建议核对） · $ids"
        ScrapeStatus.UNMATCHED -> "所有来源都没有匹配"
        ScrapeStatus.NONE -> if (item.scrapedAt != null) "已刮削 · $ids" else "尚未刮削"
    }
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetailHeader(state: AppState, playback: PlaybackController, item: MediaItemDto) {
    // A season or an episode names the show it belongs to; the show itself has
    // no parent to point at, so the label stays plain text there.
    val openSeries: (() -> Unit)? = item.seriesId?.let { id ->
        { state.navigate(Screen.Detail(id)) }
    }

    Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
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

        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.Bottom) {
            item.posterUrl?.let { poster ->
                Surface(
                    modifier = Modifier.width(160.dp).height(240.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    AsyncImage(
                        model = poster,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(20.dp))
            }

            Column(Modifier.weight(1f)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.year?.let { Chip(it.toString()) }
                    item.runtimeMs?.let { Chip(formatDuration(it)) }
                    item.communityRating?.let { Chip("★ ${(it * 10).toInt() / 10.0}") }
                    item.childCount?.takeIf { item.kind == ItemKind.SERIES }?.let { Chip("$it 季") }
                    Chip(watchedLabel(item))
                }

                if (item.genres.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.genres.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item.overview?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(18.dp))
                PlayActions(state, playback, item)
            }
        }
    }
}

@Composable
private fun PlayActions(state: AppState, playback: PlaybackController, item: MediaItemDto) {
    val target = if (item.isPlayable) item else state.detailEpisodes.firstOrNull { !it.userData.played }
        ?: state.detailEpisodes.firstOrNull()
    var menuOpen by remember { mutableStateOf(false) }
    var identifyOpen by remember { mutableStateOf(false) }
    var mergeOpen by remember { mutableStateOf(false) }

    if (identifyOpen) {
        IdentifyDialog(state, item, onDismiss = { identifyOpen = false })
    }
    if (mergeOpen) {
        MergeDialog(state, item, onDismiss = { mergeOpen = false })
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (target != null) {
                Button(onClick = { playback.playInternalOrExternal(target) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        when {
                            target.userData.positionMs > 0 -> "继续 ${formatDuration(target.userData.positionMs)}"
                            item.kind == ItemKind.SERIES -> "播放 ${target.episodeLabel ?: ""}".trim()
                            else -> "播放"
                        }
                    )
                }

                Box {
                    FilledTonalButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("外部播放器")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                                    menuOpen = false
                                    playback.playExternal(target, player)
                                }
                            )
                        }
                    }
                }
            }

            IconButton(onClick = { state.toggleFavorite(item) }) {
                Icon(
                    if (item.userData.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (item.userData.favorite) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Series and seasons get the toggle too: marking one watched marks
            // every episode under it, which is the only thing "watched" can mean
            // for something that has no bytes of its own.
            if (item.kind != ItemKind.SEASON || (item.episodeCount ?: 0) > 0) {
                IconButton(onClick = { state.togglePlayed(item) }) {
                    Icon(
                        if (item.playedState == PlayedState.PLAYED) Icons.Filled.Check
                        else Icons.Filled.CheckCircleOutline,
                        contentDescription = if (item.playedState == PlayedState.PLAYED) "标记为未观看" else "标记为已观看",
                        tint = when (item.playedState) {
                            PlayedState.PLAYED -> MaterialTheme.colorScheme.primary
                            PlayedState.PARTIAL -> MaterialTheme.colorScheme.secondary
                            PlayedState.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            // Duplicates only happen at the top level: a whole series or film
            // scanned twice, not an individual episode.
            if (item.kind == ItemKind.MOVIE || item.kind == ItemKind.SERIES) {
                IconButton(onClick = { mergeOpen = true }) {
                    Icon(
                        Icons.Filled.CallMerge,
                        contentDescription = "合并重复条目",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Only whole films and series carry scraped metadata, so only they
            // can be re-pointed at a different entry.
            if (item.kind == ItemKind.MOVIE || item.kind == ItemKind.SERIES) {
                IconButton(onClick = { identifyOpen = true }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "手动指定刮削条目",
                        tint = if (item.lockedProvider != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        playback.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(episodes, key = { it.id }) { episode ->
                // Every tile in the row is the same show, so the series name the
                // card usually leads with says nothing; the number does.
                PosterCard(
                    episode,
                    width = 232.dp,
                    title = episode.episodeLabel ?: episode.name,
                    subtitle = episode.name,
                    menu = { dismiss -> ItemMenuItems(state, playback, episode, dismiss) }
                ) { state.navigate(Screen.Detail(episode.id)) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PeopleRow(item: MediaItemDto) {
    Column {
        SectionHeader("演职人员")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(item.people, key = { it.name + (it.role ?: "") }) { person ->
                Column(
                    modifier = Modifier.width(96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(50)),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        person.imageUrl?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = person.name,
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
        Spacer(Modifier.height(20.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: MediaItemDto,
    basePath: String?,
    onPlay: () -> Unit,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    onToggleWatched: () -> Unit
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var lastPointer by remember { mutableStateOf(PointerType.Unknown) }
    val density = LocalDensity.current

    // The menu hangs off the box rather than the card's own content column, so
    // the offset the gesture reported is measured from the same corner it was.
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .secondaryClick(
                    onPointerType = { lastPointer = it },
                    onOpen = { at -> menuAt = at }
                )
                .combinedClickable(
                    onClick = onPlay,
                    onLongClick = { if (lastPointer == PointerType.Touch) menuAt = Offset.Zero }
                ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.width(148.dp).height(84.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box {
                        episode.posterUrl?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        val progress = episode.userData.playedPercentage.toFloat()
                        if (progress > 0.01f && !episode.userData.played) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                                color = MaterialTheme.colorScheme.secondary,
                                trackColor = Color.Transparent,
                                gapSize = 0.dp,
                                drawStopIndicator = {}
                            )
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
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
                            episode.runtimeMs?.let { formatDuration(it) },
                            formatSize(episode.sizeBytes).takeIf { it.isNotBlank() },
                            episode.mediaStreams.count { it.type == StreamType.SUBTITLE }
                                .takeIf { it > 0 }?.let { "$it 条字幕" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    episode.path?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            pathUnder(it, basePath),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onToggleWatched) {
                    Icon(
                        if (episode.userData.played) Icons.Filled.Check else Icons.Filled.CheckCircleOutline,
                        contentDescription = if (episode.userData.played) "标记为未观看" else "标记为已观看",
                        tint = when (episode.playedState) {
                            PlayedState.PLAYED -> MaterialTheme.colorScheme.primary
                            PlayedState.PARTIAL -> MaterialTheme.colorScheme.secondary
                            PlayedState.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuAt != null,
            onDismissRequest = { menuAt = null },
            offset = menuAt.toDpOffset(density)
        ) {
            menu { menuAt = null }
        }
    }
}
