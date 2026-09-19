package com.daview.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.daview.app.data.HomeSection
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.data.ShelfKind
import com.daview.app.libraryIcon
import com.daview.shared.model.DownloadState
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.ScanProgressDto

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: AppState, playback: PlaybackController) {
    val home = state.home
    val sections = state.homeSections.filter { it.second }.map { it.first }
    val heroItem = if (HomeSection.HERO in sections) {
        home.resume.firstOrNull() ?: home.nextUp.firstOrNull() ?: home.latest.firstOrNull()
    } else null
    val itemMenu = cardMenu(state, playback)
    val downloaded = { item: MediaItemDto -> state.downloadOf(item.id)?.state == DownloadState.DONE }

    // The play button the tiles draw over their artwork. A series has no bytes
    // of its own; pressing it plays the episode it is up to.
    val onPlay: (MediaItemDto) -> Unit = { playback.play(it) }
    val open: (MediaItemDto) -> Unit = { state.navigate(Screen.Detail(it.id)) }

    if (!state.homeLoaded) {
        LoadingPane()
        return
    }

    if (state.libraries.isEmpty() && home.latest.isEmpty()) {
        FirstRun(state)
        return
    }

    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        // A share of the height the page actually has, not a fixed band: on a
        // phone held sideways a 380dp banner was the whole screen and more.
        val heroHeight = (maxHeight * 0.52f).coerceIn(220.dp, 460.dp)
        val scanning = state.scanStatus.filter { it.running }

        // Pull down to refresh where there is a finger to pull with; the
        // desktop has F5 and the button in the corner.
        MaybePullToRefresh(
            enabled = !hasHoverPointer,
            refreshing = state.homeRefreshing,
            onRefresh = { state.refreshHome() }
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            if (heroItem == null) {
                item { Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(16.dp)) }
            }
            // The running scan leads the page, where it can be seen; it used to
            // sit under every shelf, a couple of thousand dp down.
            if (scanning.isNotEmpty()) {
                item {
                    ScanBanner(
                        scanning,
                        topInset = heroItem == null,
                        onOpen = { state.switchTo(Screen.Settings) }
                    )
                }
            }
            sections.forEach { section ->
                when (section) {
                    HomeSection.HERO -> heroItem?.let { hero ->
                        item(key = "hero") {
                            HeroBanner(
                                hero,
                                height = heroHeight,
                                menu = { dismiss -> ItemMenuItems(state, playback, hero, dismiss) },
                                onPlay = { playback.play(hero) },
                                onSeries = hero.seriesId?.let { id -> { state.navigate(Screen.Detail(id)) } }
                            ) { state.navigate(Screen.Detail(hero.id)) }
                        }
                    }
                    // The rail already lists every library on a wide window.
                    HomeSection.LIBRARIES -> if (!wide) {
                        item(key = "libraries") { LibraryShortcuts(state.libraries) { state.switchTo(Screen.Library(it.id)) } }
                    }
                    HomeSection.RESUME -> item(key = "resume") {
                        // The banner already shows the first of these; the row
                        // starts after it rather than repeating it right below.
                        val rows = if (heroItem != null && heroItem.id == home.resume.firstOrNull()?.id) {
                            home.resume.drop(1)
                        } else home.resume
                        MediaRow(
                            "继续观看", rows, itemWidth = 240.dp, shape = CardShape.LANDSCAPE,
                            onSeeAll = { state.navigate(Screen.Shelf(ShelfKind.RESUME)) }.takeIf { home.resume.size > 1 },
                            downloaded = downloaded, menu = itemMenu, onItemPlay = onPlay, onItemClick = open
                        )
                    }
                    HomeSection.NEXT_UP -> item(key = "nextUp") {
                        val rows = if (heroItem != null && heroItem.id == home.nextUp.firstOrNull()?.id && home.resume.isEmpty()) {
                            home.nextUp.drop(1)
                        } else home.nextUp
                        MediaRow(
                            "接下来", rows, itemWidth = 240.dp, shape = CardShape.LANDSCAPE,
                            onSeeAll = { state.navigate(Screen.Shelf(ShelfKind.NEXT_UP)) },
                            downloaded = downloaded, menu = itemMenu, onItemPlay = onPlay, onItemClick = open
                        )
                    }
                    HomeSection.LATEST -> item(key = "latest") {
                        MediaRow(
                            "最近添加", home.latest, itemWidth = 240.dp, shape = CardShape.LANDSCAPE,
                            onSeeAll = { state.navigate(Screen.Shelf(ShelfKind.LATEST)) },
                            downloaded = downloaded, menu = itemMenu, onItemPlay = onPlay, onItemClick = open
                        )
                    }
                    HomeSection.FAVOURITES -> item(key = "favourites") {
                        MediaRow(
                            "收藏", home.favourites,
                            onSeeAll = { state.navigate(Screen.Shelf(ShelfKind.FAVOURITES)) },
                            downloaded = downloaded, menu = itemMenu, onItemPlay = onPlay, onItemClick = open
                        )
                    }
                    // One row per library rather than a single pooled one: which
                    // shelf a thing sits on is most of what decides whether you
                    // want it tonight. The selection turns over once a day.
                    HomeSection.UNWATCHED -> items(state.libraries, key = { "unwatched-" + it.id }) { library ->
                        MediaRow(
                            "${library.name} · 未观看",
                            home.unwatched[library.id].orEmpty(),
                            onSeeAll = {
                                state.updateView(library.id) { it.copy(onlyUnwatched = true) }
                                state.switchTo(Screen.Library(library.id))
                            },
                            downloaded = downloaded, menu = itemMenu, onItemPlay = onPlay, onItemClick = open
                        )
                    }
                }
            }
        }
        }

        // Somewhere to ask for fresh shelves that is not only F5 or a gesture.
        Tooltip("刷新（F5）") {
            FilledTonalIconButton(
                onClick = { state.refreshHome() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新首页")
            }
        }
        VerticalScrollbarFor(listState, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
    }
}

/**
 * The page before there is anything to show: the two steps, in order, with a
 * way to each. It used to say "choose a directory on WebDAV" to someone who had
 * not yet told the app where WebDAV was.
 */
@Composable
private fun FirstRun(state: AppState) {
    val storageReady = state.serverInfo?.storageConfigured == true
    EmptyState(
        title = "还没有媒体库",
        description = if (storageReady) {
            "WebDAV 已连接。下一步：在设置里添加一个目录作为电影 / 电视剧 / 番剧库，添加后会自动扫描。"
        } else {
            "第一步：在设置里填写 WebDAV 地址与账号，并测试连接。\n第二步：添加一个目录作为媒体库，添加后会自动扫描。"
        }
    ) {
        Button(onClick = { state.switchTo(Screen.Settings) }) {
            Text(if (storageReady) "添加媒体库" else "前往设置")
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanBanner(scanning: List<ScanProgressDto>, topInset: Boolean, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (topInset) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars))
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            scanning.forEach { status ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "正在扫描「${status.libraryName}」 · ${scanPhaseLabel(status.phase)}" +
                            (if (status.total > 0) " ${status.current}/${status.total}" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpen) { Text("查看") }
                }
                // Until the scan knows how much there is, it says it is working
                // rather than sitting at 0%.
                if (status.total > 0) {
                    LinearWavyProgressIndicator(
                        progress = { status.current.toFloat() / status.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroBanner(
    item: MediaItemDto,
    height: androidx.compose.ui.unit.Dp,
    menu: @Composable androidx.compose.foundation.layout.ColumnScope.(dismiss: () -> Unit) -> Unit,
    onPlay: () -> Unit,
    onSeries: (() -> Unit)?,
    onOpen: () -> Unit
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var lastPointer by remember { mutableStateOf(PointerType.Unknown) }
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            // The same menu its card has on the shelves below.
            .secondaryClick(onPointerType = { lastPointer = it }, onOpen = { menuAt = it })
            .combinedClickable(
                onClick = onOpen,
                onLongClickLabel = "打开菜单",
                onLongClick = { if (lastPointer != PointerType.Mouse) menuAt = Offset.Zero }
            )
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
        // Dark behind the status bar icons at the top, dense behind the text at
        // the bottom, clear in between where the picture is.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                    0.18f to Color.Transparent,
                    0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                    1f to MaterialTheme.colorScheme.background
                )
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 20.dp).widthIn(max = 720.dp)
        ) {
            // An episode leads with its show, which is what the viewer knows it
            // by; its own title is the smaller line.
            val headline = item.seriesName ?: item.name
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.seriesName != null) {
                LinkText(
                    listOfNotNull(item.episodeLabel, item.name).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    onClick = null
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item.year?.let { Chip(it.toString()) }
                formatRuntime(item.runtimeMs)?.let { Chip(it) }
                item.communityRating?.let { Chip("评分 ${(it * 10).toInt() / 10.0}") }
            }
            if (item.userData.positionMs > 0 && !item.userData.played) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { item.userData.playedPercentage.toFloat() },
                    modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().height(4.dp),
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (item.userData.positionMs > 0) "继续播放" else "播放")
                }
                Button(onClick = onOpen, colors = ButtonDefaults.filledTonalButtonColors()) { Text("详情") }
                if (onSeries != null) {
                    TextButton(onClick = onSeries) { Text("整部剧") }
                }
            }
        }
        DropdownMenu(
            expanded = menuAt != null,
            onDismissRequest = { menuAt = null },
            offset = menuAt.toDpOffset(density).let { androidx.compose.ui.unit.DpOffset(it.x, it.y - height) }
        ) {
            menu { menuAt = null }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaybePullToRefresh(
    enabled: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        content()
    }
}

/** Plays [item] with whatever this device has. Kept for the call sites that still name it so. */
fun PlaybackController.playInternalOrExternal(item: MediaItemDto, startPositionMs: Long? = null) =
    play(item, startPositionMs)
