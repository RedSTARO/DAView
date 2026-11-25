package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Upcoming
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.data.ShelfKind
import com.daview.shared.model.ItemKind

/** The complete view behind a shelf on Home, with the same items and order. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShelfScreen(state: AppState, playback: PlaybackController, shelf: Screen.Shelf, showBack: Boolean = state.canGoBack) {
    val activeItemId = LocalSelectedBrowseItem.current
    val kind = shelf.kind
    val presentation = kind.presentation
    val libraryName = shelf.libraryId?.let { id -> state.libraries.firstOrNull { it.id == id }?.name }
    val loaded = state.shelfOf == shelf
    val gridState = rememberLazyGridState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = pageMargin(maxWidth)
        val fontGrowth = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
        val tight = maxHeight < (400f + 80f * fontGrowth).dp
        val prominent = maxWidth >= 600.dp && !tight
        Column(Modifier.fillMaxSize()) {
            BrowsePageHeading(
                title = kind.title,
                context = if (tight) libraryName else "放映室 · ${libraryName ?: "全部媒体库"}",
                status = when {
                    state.shelfLoading -> if (loaded) "正在更新" else "正在读取"
                    loaded -> "${state.shelfItems.size} ${if (kind == ShelfKind.NEXT_UP) "集" else "项"}"
                    else -> null
                },
                margin = margin,
                prominent = prominent,
                onBack = if (showBack) ({ state.back() }) else null
            )
            if (loaded && state.shelfItems.isNotEmpty() && !tight) {
                ShelfContext(presentation, inProgress = kind == ShelfKind.RESUME, modifier = Modifier.padding(horizontal = margin))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = margin).height(4.dp)) {
                if (loaded && state.shelfLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val list = maxWidth < (600f + 120f * fontGrowth).dp || maxHeight < (320f + 120f * fontGrowth).dp
                when {
                    !loaded && state.shelfError != null && !state.shelfLoading -> {
                        EmptyState("读取失败", state.shelfError.orEmpty(), icon = Icons.Filled.ErrorOutline) {
                            Button(onClick = { state.loadShelf(shelf) }) {
                                Text("重试", style = MaterialTheme.typography.labelLargeEmphasized)
                            }
                        }
                    }
                    !loaded -> LoadingPane()
                    state.shelfItems.isEmpty() -> EmptyState(presentation.emptyTitle, presentation.emptyBody, icon = presentation.icon)
                    else -> {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = when {
                                list -> GridCells.Fixed(1)
                                presentation.landscape -> GridCells.Adaptive(minSize = 224.dp)
                                else -> GridCells.Adaptive(minSize = 152.dp)
                            },
                            contentPadding = PaddingValues(start = margin, end = margin, top = 12.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(if (list) 8.dp else 16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.shelfItems, key = { it.id }) { item ->
                                val play = { playback.play(item) }
                                val open = { state.openBrowseDetail(item.id) }
                                if (list) {
                                    val metadata = if (item.kind == ItemKind.EPISODE) {
                                        listOfNotNull(item.seriesName, item.episodeLabel).joinToString(" · ")
                                    } else null
                                    ListEntry(
                                        item = item,
                                        selected = null,
                                        downloaded = state.isDownloaded(item),
                                        menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                                        onClick = open,
                                        subtitle = metadata,
                                        onPlay = play,
                                        landscape = presentation.landscape
                                    )
                                } else {
                                    BoxWithConstraints {
                                        PosterCard(
                                            item,
                                            width = maxWidth,
                                            shape = if (presentation.landscape) CardShape.LANDSCAPE else CardShape.PORTRAIT,
                                            downloaded = state.isDownloaded(item),
                                            detailOpen = item.id == activeItemId,
                                            menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                                            onPlay = play,
                                            onClick = open
                                        )
                                    }
                                }
                            }
                        }
                        VerticalScrollbarFor(gridState, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShelfContext(presentation: ShelfPresentation, inProgress: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.widthIn(max = 720.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = colors.surfaceContainerLow
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (inProgress) colors.tertiaryContainer else colors.secondaryContainer,
                contentColor = if (inProgress) colors.onTertiaryContainer else colors.onSecondaryContainer
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(presentation.icon, contentDescription = null)
                }
            }
            Text(
                presentation.description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
    }
}

private enum class ShelfPresentation(
    val icon: ImageVector,
    val description: String,
    val emptyTitle: String,
    val emptyBody: String,
    val landscape: Boolean = false
) {
    Resume(
        Icons.Filled.Restore,
        "按最近播放排列，从上次停下的地方继续。",
        "暂无未看完的条目",
        "开始播放一部影片或分集后，没看完的内容会出现在这里。",
        landscape = true
    ),
    NextUp(
        Icons.Filled.Upcoming,
        "这里是已开始追看的剧集里，接下来还没打开的一集。",
        "暂无待看的下一集",
        "开始观看有未看分集的剧集后，下一集会出现在这里。",
        landscape = true
    ),
    Latest(
        Icons.Filled.NewReleases,
        "按添加时间由近到远排列；每部剧只显示最新添加的一集。",
        "暂无最近添加的内容",
        "媒体库扫描发现的新影片和新分集会出现在这里。",
        landscape = true
    ),
    Favourites(
        Icons.Filled.Bookmark,
        "按名称排列，随时回到收藏过的作品。",
        "还没有收藏",
        "在条目详情或菜单中选择「收藏」，就能在这里找到它。"
    ),
    Unwatched(
        Icons.Filled.WatchLater,
        "这里收录这个媒体库中还没开始播放的影片与剧集。",
        "没有尚未观看的条目",
        "这个媒体库中未开始播放的影片和剧集会出现在这里。"
    )
}

private val ShelfKind.presentation: ShelfPresentation
    get() = when (this) {
        ShelfKind.RESUME -> ShelfPresentation.Resume
        ShelfKind.NEXT_UP -> ShelfPresentation.NextUp
        ShelfKind.LATEST -> ShelfPresentation.Latest
        ShelfKind.FAVOURITES -> ShelfPresentation.Favourites
        ShelfKind.UNWATCHED -> ShelfPresentation.Unwatched
    }
