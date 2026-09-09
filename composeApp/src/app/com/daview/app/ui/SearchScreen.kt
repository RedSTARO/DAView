package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(state: AppState, playback: PlaybackController) {
    // The field owns its own text. Routing every keystroke through the query
    // meant the character you typed only appeared once a coroutine had run,
    // which an IME makes very obvious.
    var query by remember { mutableStateOf(state.searchQuery) }

    // One search per pause in typing rather than one per keystroke, and the
    // result of a stale query cannot land after a newer one.
    LaunchedEffect(query) {
        state.searchQuery = query
        delay(250)
        state.search(query)
    }

    Column(Modifier.fillMaxSize()) {
        val focusRequester = remember { FocusRequester() }
        // The page exists to be typed into, so it opens ready for that rather
        // than needing a tap on the one field it has.
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            // A label rather than only a placeholder: once there is text in the
            // box the placeholder is gone and a screen reader has nothing left
            // to say the field is for.
            label = { Text("搜索影片、剧集、番剧") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                    }
                }
            },
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(20.dp).focusRequester(focusRequester)
        )

        if (state.searchLoading) {
            // Without this the first keystroke of every search showed "没有匹配
            // 的结果" for the length of the debounce before any query had run.
            LoadingPane()
        } else if (state.searchResults.isEmpty()) {
            EmptyState(
                title = if (state.searchQuery.isBlank()) "搜索媒体库" else "没有匹配的结果",
                description = if (state.searchQuery.isBlank()) "按名称搜索已刮削的条目。" else "换个关键词试试。"
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(state.searchResults, key = { it.id }) { item ->
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
