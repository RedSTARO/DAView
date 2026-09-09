package com.daview.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.app.libraryIcon
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(state: AppState, playback: PlaybackController) {
    val home = state.home
    val hero = home.resume.firstOrNull() ?: home.nextUp.firstOrNull() ?: home.latest.firstOrNull()
    val itemMenu = cardMenu(state, playback)

    // The play button the tiles draw over their artwork. A series or a season
    // has no bytes of its own, so it keeps opening its page instead.
    val onPlay: (MediaItemDto) -> Unit = {
        if (it.isPlayable) playback.playInternalOrExternal(it)
    }

    if (state.libraries.isEmpty() && home.latest.isEmpty()) {
        EmptyState(
            title = "还没有媒体库",
            description = "在设置里选择 WebDAV 上的目录作为电影 / 电视剧 / 番剧库，然后扫描。"
        ) {
            Button(onClick = { state.navigate(Screen.Settings) }) { Text("前往设置") }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        hero?.let { heroItem ->
            val openSeries: (() -> Unit)? = heroItem.seriesId?.let { id ->
                { state.navigate(Screen.Detail(id)) }
            }
            item {
                HeroBanner(
                    heroItem,
                    onPlay = { playback.playInternalOrExternal(heroItem) },
                    onSeries = openSeries
                ) { state.navigate(Screen.Detail(heroItem.id)) }
            }
        }

        item { LibraryShortcuts(state.libraries) { state.navigate(Screen.Library(it.id)) } }

        item {
            MediaRow(
                "继续观看",
                home.resume,
                itemWidth = 232.dp,
                menu = itemMenu,
                onItemPlay = onPlay,
            ) { state.navigate(Screen.Detail(it.id)) }
        }
        item {
            MediaRow(
                "接下来",
                home.nextUp,
                itemWidth = 232.dp,
                menu = itemMenu,
                onItemPlay = onPlay,
            ) { state.navigate(Screen.Detail(it.id)) }
        }
        item {
            MediaRow("最近添加", home.latest, menu = itemMenu, onItemPlay = onPlay) {
                state.navigate(Screen.Detail(it.id))
            }
        }

        // One row per library rather than a single pooled one: which shelf a
        // thing sits on is most of what decides whether you want it tonight.
        items(state.libraries, key = { it.id }) { library ->
            MediaRow(
                "${library.name} · 未观看",
                home.unwatched[library.id].orEmpty(),
                menu = itemMenu,
                onItemPlay = onPlay,
            ) { state.navigate(Screen.Detail(it.id)) }
        }

        val running = state.scanStatus.filter { it.running }
        if (running.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("正在扫描")
                    running.forEach { status ->
                        Text(
                            "${status.libraryName} · ${scanPhaseLabel(status.phase)} " +
                                "${status.current}/${status.total} ${status.message}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearWavyProgressIndicator(
                            progress = {
                                if (status.total > 0) status.current.toFloat() / status.total else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBanner(
    item: MediaItemDto,
    onPlay: () -> Unit,
    onSeries: (() -> Unit)?,
    onOpen: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 260.dp, max = 380.dp)
    ) {
        val art = item.backdropUrl ?: item.posterUrl
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.75f),
                    1f to MaterialTheme.colorScheme.background
                )
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(24.dp)
        ) {
            item.seriesName?.let { name ->
                LinkText(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    onClick = onSeries
                )
            }
            Text(
                text = item.name,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item.episodeLabel?.let { Chip(it) }
                item.year?.let { Chip(it.toString()) }
                item.runtimeMs?.let { Chip(formatDuration(it)) }
                item.communityRating?.let { Chip("★ ${(it * 10).toInt() / 10.0}") }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (item.userData.positionMs > 0) "继续播放" else "播放")
                }
                Button(
                    onClick = onOpen,
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
                ) {
                    Text("详情")
                }
            }
        }
    }
}

@Composable
private fun LibraryShortcuts(libraries: List<LibraryDto>, onClick: (LibraryDto) -> Unit) {
    if (libraries.isEmpty()) return
    Column {
        SectionHeader("媒体库")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(libraries, key = { it.id }) { library ->
                Card(
                    onClick = { onClick(library) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = libraryIcon(library.kind),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(library.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${library.itemCount} 项",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Uses the in-app player where one exists, otherwise the first external player. */
fun PlaybackController.playInternalOrExternal(item: MediaItemDto) = playAnyhow(item)
