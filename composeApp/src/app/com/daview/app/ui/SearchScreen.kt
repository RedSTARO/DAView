package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.tracksTextInput
import com.daview.shared.model.MediaItemDto
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(state: AppState, playback: PlaybackController, showBack: Boolean = state.canGoBack) {
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
    val keyboard = LocalSoftwareKeyboardController.current
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val short = maxHeight < 360.dp
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 8.dp, end = 20.dp, top = if (short) 4.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBack) {
                    TipIconButton("返回（Alt + ←）", onClick = { state.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    // A label still names the editor when it has text in it.
                    label = { Text("搜索片名、原名、演员或类型", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        state.rememberSearch(query)
                        keyboard?.hide()
                    }),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .padding(start = if (showBack) 4.dp else 12.dp)
                        .focusRequester(focusRequester)
                        .trackBrowseEditorFocus()
                        .tracksTextInput()
                )
            }

            // Keep the current results in place while the next query runs.
            Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 20.dp)) {
                if (state.searchLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            when {
                query.isBlank() -> SearchStart(state) { query = it }
                state.searchResults.isEmpty() && state.searchEpisodes.isEmpty() && !state.searchLoading ->
                    EmptyState(title = "没有匹配的结果", description = "换个关键词试试：片名、原名、演员名或类型都可以。")
                else -> SearchResults(state, playback, gridState)
            }
        }
    }
}

@Composable
private fun SearchResults(state: AppState, playback: PlaybackController, gridState: LazyGridState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        // An artwork-first tile can fill the whole usable height without showing its
        // title. Keep the same keyed lazy grid, using text-first rows in that case.
        val list = maxHeight < (400f + 120f * (fontScale - 1f)).dp || maxWidth < 340.dp
        LazyVerticalGrid(
            state = gridState,
            columns = if (list) GridCells.Fixed(1) else GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(if (list) 8.dp else 18.dp)
        ) {
            if (state.searchResults.isNotEmpty()) {
                header("影片与剧集" + if (state.searchTotal > 0) "（${state.searchTotal}）" else "")
                results(state, playback, state.searchResults, list)
            }
            if (state.searchEpisodes.isNotEmpty()) {
                val shown = state.searchEpisodes.size
                header(if (shown >= EPISODE_RESULTS) "分集（最相关的 $shown 条）" else "分集（$shown）")
                results(state, playback, state.searchEpisodes, list, episodes = true)
            }
        }
        VerticalScrollbarFor(gridState, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
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
    list: Boolean,
    episodes: Boolean = false
) {
    items(items, key = { it.id }, span = { GridItemSpan(if (episodes) minOf(2, maxLineSpan) else 1) }) { item ->
        val libraryName = state.libraries.firstOrNull { it.id == item.libraryId }?.name
        val subtitle = if (episodes) listOfNotNull(item.seriesName, subtitleFor(item).takeIf { it.isNotBlank() })
            .joinToString(" · ") else listOfNotNull(subtitleFor(item).takeIf { it.isNotBlank() }, libraryName).joinToString(" · ")
        val open = {
            state.rememberSearch(state.searchQuery)
            state.openBrowseDetail(item.id)
        }
        if (list) {
            ListEntry(
                item = item,
                selected = null,
                downloaded = state.isDownloaded(item),
                menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                onClick = open,
                subtitle = subtitle,
                onPlay = { playback.play(item) }
            )
        } else {
            BoxWithConstraints {
                PosterCard(
                    item,
                    width = maxWidth,
                    shape = if (episodes) CardShape.LANDSCAPE else CardShape.PORTRAIT,
                    subtitle = if (episodes) subtitleFor(item) else subtitle,
                    downloaded = state.isDownloaded(item),
                    detailOpen = LocalSelectedBrowseItem.current == item.id,
                    menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                    onPlay = { playback.play(item) },
                    onClick = open
                )
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
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
