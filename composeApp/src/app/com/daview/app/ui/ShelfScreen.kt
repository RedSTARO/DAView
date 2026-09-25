package com.daview.app.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.data.ShelfKind

/**
 * Everything on one of the home shelves. The shelves stop at twenty or two
 * dozen, and there was no way to see the rest of what was recently added, or
 * every favourite across the libraries at once.
 */
@Composable
fun ShelfScreen(state: AppState, playback: PlaybackController, shelf: Screen.Shelf) {
    val kind = shelf.kind
    val landscape = kind != ShelfKind.FAVOURITES && kind != ShelfKind.UNWATCHED
    val libraryName = shelf.libraryId?.let { id -> state.libraries.firstOrNull { it.id == id }?.name }
    val gridState = rememberLazyGridState()
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = libraryName?.let { "$it · ${kind.title}" } ?: kind.title,
            subtitle = if (state.shelfOf == shelf) "${state.shelfItems.size} 项" else null,
            onBack = if (state.canGoBack) ({ state.back() }) else null
        )
        when {
            state.shelfOf != shelf -> {
                val failed = state.shelfError
                if (failed != null && !state.shelfLoading) {
                    EmptyState("读取失败", failed) {
                        Button(onClick = { state.loadShelf(shelf) }) { Text("重试") }
                    }
                } else {
                    LoadingPane()
                }
            }
            state.shelfItems.isEmpty() -> EmptyState("这里还没有内容", "看过、收藏过或新添加的条目会出现在这里。")
            else -> Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = if (landscape) 220.dp else 150.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.shelfItems, key = { it.id }) { item ->
                        BoxWithConstraints {
                            PosterCard(
                                item,
                                width = maxWidth,
                                shape = if (landscape) CardShape.LANDSCAPE else CardShape.PORTRAIT,
                                downloaded = state.isDownloaded(item),
                                menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                                onPlay = { playback.play(item) }
                            ) { state.navigate(Screen.Detail(item.id)) }
                        }
                    }
                }
                VerticalScrollbarFor(gridState, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
            }
        }
    }
}
