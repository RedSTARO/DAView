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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.Screen
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(state: AppState) {
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
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("搜索影片、剧集、番剧") },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        )

        if (state.searchResults.isEmpty()) {
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
                    PosterCard(item, width = 150.dp) { state.navigate(Screen.Detail(item.id)) }
                }
            }
        }
    }
}
