@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.HomeSection
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.data.ShelfKind
import com.daview.app.libraryIcon
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.ScanProgressDto

private const val HomeHeroKey = "hero"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: AppState, playback: PlaybackController) {
    val home = state.home
    val sections = state.homeSections.filter { it.second }.map { it.first }
    val heroItem = if (HomeSection.HERO in sections) {
        home.resume.firstOrNull() ?: home.nextUp.firstOrNull() ?: home.latest.firstOrNull()
    } else null
    val highlight = when (heroItem?.id) {
        null -> ""
        home.resume.firstOrNull()?.id -> "继续观看"
        home.nextUp.firstOrNull()?.id -> "接下来"
        else -> "最近添加"
    }
    // Announce requested scans here. The automatic startup check stays quiet.
    val scanning = state.scanStatus.filter { it.running && !it.automatic }
    val firstIsHero = sections.firstOrNull() == HomeSection.HERO && heroItem != null && scanning.isEmpty()
    val itemMenu = cardMenu(state, playback)
    val downloaded = { item: MediaItemDto -> state.isDownloaded(item) }
    val play: (MediaItemDto) -> Unit = { playback.play(it) }
    val open: (MediaItemDto) -> Unit = { state.navigate(Screen.Detail(it.id)) }
    val listState = rememberLazyListState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = pageMargin(maxWidth)
        val wideShelves = maxWidth >= 720.dp
        val shortPane = maxHeight < 400.dp
        val ready = state.homeLoaded
        val noLibrary = state.libraries.isEmpty() && home.latest.isEmpty()
        val emptyLibraries = state.libraries.all { it.itemCount == 0 } && home.latest.isEmpty() &&
            home.resume.isEmpty() && home.nextUp.isEmpty() && home.favourites.isEmpty()

        if (!ready) {
            val failed = state.homeError
            Column(Modifier.fillMaxSize().clipToBounds()) {
                HomeHeading(state, margin)
                Box(Modifier.weight(1f)) {
                    if (failed == null || state.homeRefreshing) LoadingPane()
                    else EmptyState("首页读取失败", failed, icon = Icons.Outlined.ErrorOutline) {
                        Button(onClick = { state.refreshHome() }) { Text("重试") }
                    }
                }
            }
            return@BoxWithConstraints
        }

        val content: @Composable () -> Unit = {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                if (noLibrary || emptyLibraries || !firstIsHero) {
                    item(key = "header") { HomeHeading(state, margin) }
                }
                when {
                    noLibrary -> item(key = "first-run") { FirstRun(state, margin) }
                    emptyLibraries -> {
                        item(key = "empty-libraries") { EmptyLibraries(state, margin) }
                        if (HomeSection.LIBRARIES in sections) item(key = "libraries") {
                            LibraryShortcuts(state.libraries, margin) { state.switchTo(Screen.Library(it.id)) }
                        }
                    }
                    else -> {
                        if (scanning.isNotEmpty()) item(key = "scan") {
                            ScanBanner(scanning, margin) { state.switchTo(Screen.Settings) }
                        }
                        sections.forEach { section ->
                            when (section) {
                                HomeSection.HERO -> heroItem?.let { hero ->
                                    item(key = HomeHeroKey) {
                                        Column {
                                            if (firstIsHero) HomeHeading(state, margin)
                                            HomeSpotlight(
                                                item = hero,
                                                label = highlight,
                                                shortPane = shortPane,
                                                onPlay = { play(hero) },
                                                onOpen = { open(hero) },
                                                onSeries = hero.seriesId?.let { id -> { state.navigate(Screen.Detail(id)) } },
                                                modifier = Modifier.padding(start = margin, end = margin, bottom = 16.dp)
                                            ) { dismiss -> ItemMenuItems(state, playback, hero, dismiss) }
                                        }
                                    }
                                }
                                HomeSection.LIBRARIES -> item(key = "libraries") {
                                    LibraryShortcuts(state.libraries, margin) { state.switchTo(Screen.Library(it.id)) }
                                }
                                HomeSection.RESUME -> item(key = "resume") {
                                    MediaRow(
                                        "继续观看", home.resume.withoutSpotlight(heroItem, highlight == "继续观看"),
                                        itemWidth = if (wideShelves) 256.dp else 212.dp, shape = CardShape.LANDSCAPE,
                                        onSeeAll = { state.navigate(Screen.Shelf(ShelfKind.RESUME)) }.takeIf { home.resume.size > 1 },
                                        downloaded = downloaded, menu = itemMenu, onItemPlay = play, horizontal = margin, onItemClick = open
                                    )
                                }
                                HomeSection.NEXT_UP -> item(key = "nextUp") {
                                    MediaRow(
                                        "接下来", home.nextUp.withoutSpotlight(heroItem, highlight == "接下来"),
                                        itemWidth = if (wideShelves) 256.dp else 212.dp, shape = CardShape.LANDSCAPE,
                                        onSeeAll = { state.navigate(Screen.Shelf(ShelfKind.NEXT_UP)) },
                                        downloaded = downloaded, menu = itemMenu, onItemPlay = play, horizontal = margin, onItemClick = open
                                    )
                                }
                                HomeSection.LATEST -> item(key = "latest") {
                                    MediaRow(
                                        "最近添加", home.latest.withoutSpotlight(heroItem, highlight == "最近添加"),
                                        itemWidth = if (wideShelves) 256.dp else 212.dp, shape = CardShape.LANDSCAPE,
                                        onSeeAll = { state.navigate(Screen.Shelf(ShelfKind.LATEST)) },
                                        downloaded = downloaded, menu = itemMenu, onItemPlay = play, horizontal = margin, onItemClick = open
                                    )
                                }
                                HomeSection.FAVOURITES -> item(key = "favourites") {
                                    MediaRow(
                                        "收藏", home.favourites, onSeeAll = { state.navigate(Screen.Shelf(ShelfKind.FAVOURITES)) },
                                        downloaded = downloaded, menu = itemMenu, onItemPlay = play, horizontal = margin, onItemClick = open
                                    )
                                }
                                HomeSection.UNWATCHED -> items(state.libraries, key = { "unwatched-" + it.id }) { library ->
                                    MediaRow(
                                        "${library.name} · 未观看", home.unwatched[library.id].orEmpty(),
                                        onSeeAll = { state.navigate(Screen.Shelf(ShelfKind.UNWATCHED, library.id)) },
                                        downloaded = downloaded, menu = itemMenu, onItemPlay = play, horizontal = margin, onItemClick = open
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (hasHoverPointer || noLibrary) content()
        else PullToRefreshBox(
            isRefreshing = state.homePulling,
            onRefresh = { state.refreshHome(pulled = true) },
            modifier = Modifier.fillMaxSize(),
            content = { content() }
        )
        VerticalScrollbarFor(listState, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
    }
}

private fun List<MediaItemDto>.withoutSpotlight(hero: MediaItemDto?, matchingShelf: Boolean) =
    if (matchingShelf && firstOrNull()?.id == hero?.id && isNotEmpty()) drop(1) else this

@Composable
private fun HomeHeading(state: AppState, margin: Dp) {
    Row(
        Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("放映室", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineMediumEmphasized)
        Tooltip("刷新（F5）") {
            FilledTonalIconButton(onClick = { state.refreshHome() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新首页")
            }
        }
    }
}

@Composable
private fun LibraryShortcuts(libraries: List<LibraryDto>, margin: Dp, onClick: (LibraryDto) -> Unit) {
    if (libraries.isEmpty()) return
    Column(Modifier.padding(bottom = 12.dp)) {
        SectionHeader("媒体库", margin)
        val scroll = rememberLazyListState()
        LazyRow(
            state = scroll,
            modifier = Modifier.wheelScrollsHorizontally(scroll),
            contentPadding = PaddingValues(horizontal = margin),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(libraries, key = { it.id }) { library ->
                Surface(onClick = { onClick(library) }, shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Row(
                        Modifier.width(200.dp).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Icon(libraryIcon(library.kind), contentDescription = null)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(library.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${library.itemCount} 项", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FirstRun(state: AppState, margin: Dp) {
    val storageReady = state.serverInfo?.storageConfigured == true
    HomeMessage(margin) {
        AppMark()
        Text("还没有媒体库", style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.semantics { heading() })
        Text("连接 WebDAV 后，就能在这里浏览影片和剧集。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SetupStep("1", if (storageReady) "已配置 WebDAV 存储" else "配置 WebDAV 存储",
            if (storageReady) "接下来选择影片所在的目录。" else "在设置里填写地址和账号，并测试连接。", active = !storageReady, complete = storageReady)
        SetupStep("2", "添加媒体库", "选择电影、电视剧或番剧目录，添加后会自动扫描。", active = storageReady)
        PrimaryPageAction(
            if (storageReady) "添加媒体库" else "前往设置",
            if (storageReady) Icons.Outlined.FolderOpen else Icons.Outlined.Storage,
            onClick = { state.switchTo(Screen.Settings) }
        )
    }
}

@Composable
private fun SetupStep(number: String, title: String, description: String, active: Boolean, complete: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (complete) Icon(Icons.Filled.Check, contentDescription = null)
                else Text(number, style = MaterialTheme.typography.titleMediumEmphasized)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyLibraries(state: AppState, margin: Dp) {
    val scans = state.scanStatus.filter { it.running }
    val storageReady = state.serverInfo?.storageConfigured == true
    if (scans.isNotEmpty()) ScanBanner(scans, margin) { state.switchTo(Screen.Settings) }
    HomeMessage(margin) {
        Text("媒体库里还没有内容", style = MaterialTheme.typography.headlineSmallEmphasized,
            modifier = Modifier.semantics { heading() })
        Text(when {
            scans.isNotEmpty() -> "扫描到的影片会出现在这里。"
            !storageReady -> "先连接 WebDAV 存储，再扫描媒体库。"
            else -> "扫描媒体库，查找目录里的影片和剧集。"
        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (scans.isEmpty()) {
            if (storageReady) PrimaryPageAction("扫描全部媒体库", Icons.Filled.Refresh, { state.scanAllLibraries() })
            else PrimaryPageAction("前往设置", Icons.Outlined.Storage, { state.switchTo(Screen.Settings) })
        }
    }
}

@Composable
private fun HomeMessage(margin: Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLargeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun ScanBanner(scanning: List<ScanProgressDto>, margin: Dp, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = 8.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("正在扫描媒体库", Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
                TextButton(onClick = onOpen) { Text("查看") }
            }
            scanning.forEach { status ->
                Text("${status.libraryName} · ${scanPhaseLabel(status.phase)}" +
                    (if (status.total > 0) " ${status.current}/${status.total}" else ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status.total > 0) LinearWavyProgressIndicator(
                    progress = { (status.current.toFloat() / status.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()
                ) else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Plays [item] with whatever this device has. Kept for the call sites that still name it so. */
fun PlaybackController.playInternalOrExternal(item: MediaItemDto, startPositionMs: Long? = null) =
    play(item, startPositionMs)
