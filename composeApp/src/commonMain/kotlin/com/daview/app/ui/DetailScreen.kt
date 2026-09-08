package com.daview.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.StreamType

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailScreen(state: AppState, playback: PlaybackController) {
    val item = state.detailItem ?: return
    val seasons = state.detailChildren.filter { it.kind == ItemKind.SEASON }
    val relatedMovies = state.detailChildren.filter { it.kind == ItemKind.MOVIE }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item { DetailHeader(state, playback, item) }

        if (item.people.isNotEmpty()) {
            item { PeopleRow(item) }
        }

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
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(state.detailEpisodes, key = { it.id }) { episode ->
                EpisodeRow(episode, onPlay = { playback.playInternalOrExternal(episode) }) {
                    state.togglePlayed(episode)
                }
            }
        }

        if (relatedMovies.isNotEmpty()) {
            item {
                MediaRow("相关影片", relatedMovies) { state.navigate(Screen.Detail(it.id)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetailHeader(state: AppState, playback: PlaybackController, item: MediaItemDto) {
    Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
        val art = item.backdropUrl ?: item.posterUrl
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                    0.5f to MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
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
                item.seriesName?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
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

    if (identifyOpen) {
        IdentifyDialog(state, item, onDismiss = { identifyOpen = false })
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
            if (item.isPlayable) {
                IconButton(onClick = { state.togglePlayed(item) }) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "已观看",
                        tint = if (item.userData.played) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
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

        if (item.isPlayable && item.mediaStreams.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            StreamSummary(item)
        }
    }
}

@Composable
private fun StreamSummary(item: MediaItemDto) {
    val audio = item.mediaStreams.filter { it.type == StreamType.AUDIO }
    val subtitles = item.mediaStreams.filter { it.type == StreamType.SUBTITLE }
    val video = item.mediaStreams.firstOrNull { it.type == StreamType.VIDEO }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        video?.let {
            Text(
                "画面: ${it.codec?.uppercase() ?: "未知"}" +
                    (if (it.width != null && it.height != null) " · ${it.width}×${it.height}" else "") +
                    (formatSize(item.sizeBytes).takeIf { size -> size.isNotBlank() }?.let { size -> " · $size" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (audio.isNotEmpty()) {
            Text(
                "音轨: " + audio.joinToString(" / ") { it.displayTitle },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (subtitles.isNotEmpty()) {
            Text(
                "字幕: " + subtitles.joinToString(" / ") { it.displayTitle },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
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

@Composable
private fun EpisodeRow(episode: MediaItemDto, onPlay: () -> Unit, onToggleWatched: () -> Unit) {
    Card(
        onClick = onPlay,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
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
                        episode.runtimeMs?.let { formatDuration(it) },
                        formatSize(episode.sizeBytes).takeIf { it.isNotBlank() },
                        episode.mediaStreams.count { it.type == StreamType.SUBTITLE }
                            .takeIf { it > 0 }?.let { "$it 条字幕" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleWatched) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "标记观看状态",
                    tint = if (episode.userData.played) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
