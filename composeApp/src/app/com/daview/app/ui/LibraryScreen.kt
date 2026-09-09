package com.daview.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

private val sortOptions = listOf(
    "sortName" to "名称",
    "year" to "年份",
    "added" to "最近添加",
    "rating" to "评分",
    "played" to "最近播放"
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)
@Composable
fun LibraryScreen(state: AppState, playback: PlaybackController, libraryId: String) {
    val library = state.libraries.firstOrNull { it.id == libraryId }

    // Whether the list in hand is this library's. A re-sort keeps it true, so
    // the entries stay put while the new order is fetched; moving to another
    // library makes it false until that library's own entries arrive.
    val loaded = state.libraryItemsOf == libraryId

    // Survives leaving for a detail page and coming back, which is the loop
    // picking something to watch actually is — it used to land back at the top
    // of the grid every time.
    val gridState = rememberLazyGridState()

    var searchOpen by rememberSaveable(libraryId) { mutableStateOf(false) }
    var searchText by rememberSaveable(libraryId) { mutableStateOf("") }

    LaunchedEffect(libraryId) {
        snapshotFlow { searchText }
            .debounce(250)
            .distinctUntilChanged()
            .collect { if (it != state.librarySearch) state.setLibrarySearch(libraryId, it) }
    }

    // Fetch the next page a little before the end rather than at it, so the
    // grid does not stop dead while the rows are fetched.
    LaunchedEffect(gridState, libraryId) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { last ->
            if (last >= state.libraryItems.size - 12) state.loadMoreLibrary(libraryId)
        }
    }

    Column(Modifier.fillMaxSize()) {
        SectionHeader(library?.name ?: "媒体库") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loaded) {
                    Text(
                        // The real size, not how much of it happens to be
                        // loaded. The two numbers used to disagree.
                        if (state.libraryItems.size < state.libraryTotal) {
                            "${state.libraryItems.size} / ${state.libraryTotal} 项"
                        } else {
                            "${state.libraryTotal} 项"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) searchText = ""
                }) {
                    Icon(
                        if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchOpen) "关闭库内搜索" else "在这个媒体库里搜索"
                    )
                }
            }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("在「${library?.name ?: "媒体库"}」中搜索") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        // Scrollable: five sort buttons plus two filters and an arrow do not fit
        // across a 360dp phone, and the last one used to be squeezed to a strip.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = state.libraryOnlyUnwatched,
                onClick = {
                    state.setLibraryFilter(libraryId, onlyUnwatched = !state.libraryOnlyUnwatched)
                },
                label = { Text("未观看") }
            )
            FilterChip(
                selected = state.libraryOnlyFavourite,
                onClick = {
                    state.setLibraryFilter(libraryId, onlyFavourite = !state.libraryOnlyFavourite)
                },
                label = { Text("收藏") }
            )
            Spacer(Modifier.width(4.dp))
            sortOptions.forEach { (key, label) ->
                ToggleButton(
                    checked = state.librarySort == key,
                    onCheckedChange = { state.setSort(key, libraryId) }
                ) {
                    Text(label)
                    if (state.librarySort == key) {
                        Spacer(Modifier.width(4.dp))
                        // Pressing the chosen field again flips this.
                        Icon(
                            if (state.librarySortDescending) Icons.Filled.ArrowDownward
                            else Icons.Filled.ArrowUpward,
                            contentDescription = if (state.librarySortDescending) "降序" else "升序",
                            modifier = Modifier.height(16.dp)
                        )
                    }
                }
            }
        }

        when {
            state.libraryError != null -> EmptyState(
                title = "读取失败",
                description = state.libraryError.orEmpty()
            ) {
                Button(onClick = { state.loadLibrary(libraryId) }) { Text("重试") }
            }

            !loaded || state.libraryLoading -> LoadingPane()

            state.libraryItems.isEmpty() -> EmptyState(
                title = if (state.librarySearch.isNotBlank() ||
                    state.libraryOnlyUnwatched ||
                    state.libraryOnlyFavourite
                ) "没有符合条件的条目" else "这个媒体库还是空的",
                description = if (state.librarySearch.isNotBlank() ||
                    state.libraryOnlyUnwatched ||
                    state.libraryOnlyFavourite
                ) "换个关键词，或者去掉上面的筛选。"
                else "在设置里对它执行一次扫描，DAView 会读取 WebDAV 目录并刮削元数据。"
            )

            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.libraryItems, key = { it.id }) { item ->
                    // The cell is as wide as the adaptive grid made it; a card
                    // pinned to 150dp left the difference as dead space on its
                    // right, and the tile needs its real width to place its
                    // context menu under the cursor.
                    BoxWithConstraints {
                        PosterCard(
                            item,
                            width = maxWidth,
                            menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                            onPlay = if (item.isPlayable) {
                                { playback.playInternalOrExternal(item) }
                            } else null
                        ) { state.navigate(Screen.Detail(item.id)) }
                    }
                }

                if (state.libraryLoadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(72.dp)) { LoadingPane() }
                    }
                }
            }
        }
    }
}
