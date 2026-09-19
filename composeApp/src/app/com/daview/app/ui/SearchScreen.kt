package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.data.tracksTextInput
import com.daview.shared.model.DownloadState
import com.daview.shared.model.MediaItemDto
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(state: AppState, playback: PlaybackController) {
    // The field owns its own text. Routing every keystroke through the query
    // meant the character you typed only appeared once a coroutine had run,
    // which an IME makes very obvious.
    var query by rememberSaveable { mutableStateOf(state.searchQuery) }

    // One search per pause in typing rather than one per keystroke, and the
    // result of a stale query cannot land after a newer one. Coming back to the
    // page with the same query asks nothing: the answer is already here.
    LaunchedEffect(query) {
        state.searchQuery = query
        delay(250)
        state.search(query)
    }

    val focusRequester = remember { FocusRequester() }
    // The page opens ready to be typed into — but only when there is nothing in
    // it yet. Coming back from a result with a query in place, the keyboard
    // used to spring up over the results every time.
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusedOnce && query.isBlank()) runCatching { focusRequester.requestFocus() }
        focusedOnce = true
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.searchResults.size - 12) state.loadMoreSearch()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 8.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.canGoBack) {
                TipIconButton("返回（Alt + ←）", onClick = { state.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                // A label rather than only a placeholder: once there is text in the
                // box the placeholder is gone and a screen reader has nothing left
                // to say the field is for.
                label = { Text("搜索片名、原名、演员或类型") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.rememberSearch(query) }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                        }
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (state.canGoBack) 4.dp else 12.dp)
                    .focusRequester(focusRequester)
                    .tracksTextInput()
            )
        }

        // Earlier results stay while a new query runs; a thin bar says so.
        // Swapping them for a spinner made them blink out at every keystroke.
        Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 20.dp)) {
            if (state.searchLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        when {
            query.isBlank() -> SearchStart(state) { query = it }

            state.searchResults.isEmpty() && state.searchEpisodes.isEmpty() && !state.searchLoading ->
                EmptyState(title = "没有匹配的结果", description = "换个关键词试试：片名、原名、演员名或类型都可以。")

            else -> Box(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        if (state.searchResults.isNotEmpty()) {
                            header(
                                "影片与剧集" + if (state.searchTotal > 0) "（${state.searchTotal}）" else ""
                            )
                            results(state, playback, state.searchResults)
                        }
                        if (state.searchEpisodes.isNotEmpty()) {
                            // Only the best matches are fetched; the number is how
                            // many are shown, not how many there are.
                            val shown = state.searchEpisodes.size
                            header(if (shown >= EPISODE_RESULTS) "分集（最相关的 $shown 条）" else "分集（$shown）")
                            results(state, playback, state.searchEpisodes, episodes = true)
                        }
                    }
                    VerticalScrollbarFor(gridState, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
                }
            }
        }
    }
}

private fun LazyGridScope.header(title: String) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    }
}

private fun LazyGridScope.results(
    state: AppState,
    playback: PlaybackController,
    items: List<MediaItemDto>,
    episodes: Boolean = false
) {
    // An episode's picture is a 16:9 still; in a poster-shaped card it was cut
    // down to a strip. Two cells wide, it keeps its shape.
    items(items, key = { it.id }, span = { GridItemSpan(if (episodes) minOf(2, maxLineSpan) else 1) }) { item ->
        // As wide as the cell, like the library grid; pinned to 150dp it left a
        // strip of dead space on the right of every card.
        BoxWithConstraints {
            val libraryName = state.libraries.firstOrNull { it.id == item.libraryId }?.name
            PosterCard(
                item,
                width = maxWidth,
                shape = if (episodes) CardShape.LANDSCAPE else CardShape.PORTRAIT,
                // Which library it is in is part of the answer when two of them
                // hold the same title. An episode already names its series, and
                // the library name at the end of that line was always cut off.
                subtitle = if (episodes) subtitleFor(item) else listOfNotNull(subtitleFor(item).takeIf { it.isNotBlank() }, libraryName)
                    .joinToString(" · "),
                downloaded = state.downloadOf(item.id)?.state == DownloadState.DONE,
                menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                onPlay = { playback.play(item) }
            ) {
                state.rememberSearch(state.searchQuery)
                state.navigate(Screen.Detail(item.id))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchStart(state: AppState, onPick: (String) -> Unit) {
    if (state.searchHistory.isEmpty()) {
        EmptyState(title = "搜索媒体库", description = "按片名、原名、演员名或类型搜索，所有媒体库一起找。")
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最近搜索", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.clearSearchHistory() }) { Text("清空") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.searchHistory.forEach { past ->
                Chip(past, onClick = { onPick(past) })
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "  点一个再搜一次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** How many episodes a search fetches; kept in step with AppState. */
private const val EPISODE_RESULTS = 30
