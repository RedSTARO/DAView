package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen

private val sortOptions = listOf(
    "sortName" to "名称",
    "year" to "年份",
    "added" to "最近添加",
    "rating" to "评分",
    "played" to "最近播放"
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen(state: AppState, playback: PlaybackController, libraryId: String) {
    val library = state.libraries.firstOrNull { it.id == libraryId }

    // Whether the list in hand is this library's. A re-sort keeps it true, so
    // the entries stay put while the new order is fetched; moving to another
    // library makes it false until that library's own entries arrive.
    val loaded = state.libraryItemsOf == libraryId

    Column(Modifier.fillMaxSize()) {
        SectionHeader(library?.name ?: "媒体库") {
            if (loaded) {
                Text(
                    "${state.libraryItems.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sortOptions.forEach { (key, label) ->
                ToggleButton(
                    checked = state.librarySort == key,
                    onCheckedChange = { state.setSort(key, libraryId) }
                ) {
                    Text(label)
                }
            }
        }

        when {
            !loaded -> LoadingPane()

            state.libraryItems.isEmpty() -> EmptyState(
                title = "这个媒体库还是空的",
                description = "在设置里对它执行一次扫描，DAView 会读取 WebDAV 目录并刮削元数据。"
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.libraryItems, key = { it.id }) { item ->
                    PosterCard(
                        item,
                        width = 150.dp,
                        menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                        onPlay = if (item.isPlayable) {
                            { playback.playInternalOrExternal(item) }
                        } else null
                    ) { state.navigate(Screen.Detail(item.id)) }
                }
            }
        }
    }
}
