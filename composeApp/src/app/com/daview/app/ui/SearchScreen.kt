package com.daview.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.tracksTextInput
import com.daview.shared.model.MediaItemDto
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchScreen(state: AppState, playback: PlaybackController, showBack: Boolean = state.canGoBack) {
    // The editor owns each keystroke; the catalogue query follows after a pause.
    var query by rememberSaveable { mutableStateOf(state.searchQuery) }
    var requestedQuery by rememberSaveable { mutableStateOf(query) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(query) {
        state.searchQuery = query
        if (requestedQuery != query) gridState.requestScrollToItem(0)
        delay(250)
        state.search(query)
        requestedQuery = query
    }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusedOnce && query.isBlank()) runCatching { focusRequester.requestFocus() }
        focusedOnce = true
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.searchResults.size - 12) state.loadMoreSearch()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontGrowth = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
        val margin = pageMargin(maxWidth)
        val short = maxHeight < (400f + 100f * fontGrowth).dp
        val wide = maxWidth >= (840f + 160f * fontGrowth).dp
        val pending = state.searchLoading || requestedQuery != query
        Column(Modifier.fillMaxSize()) {
            if (!short) {
                BrowsePageHeading(
                    title = "搜索媒体库",
                    context = "媒体目录",
                    status = "全部媒体库",
                    margin = margin,
                    prominent = wide,
                    onBack = null
                )
            }
            // Only the vertical layout changes in a short pane. The editor and progress
            // share the same width, and Back lives inside it instead of taking extra space.
            Column(
                Modifier
                    .then(if (short) Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)) else Modifier)
                    .padding(start = margin, end = margin, top = if (short) 4.dp else 0.dp)
                    .widthIn(max = 720.dp).fillMaxWidth()
            ) {
                BrowseSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    label = "搜索片名、原名、演员或类型",
                    hint = "搜索片名、演员或类型",
                    clearLabel = "清空搜索",
                    onSearch = {
                        state.rememberSearch(query)
                        keyboard?.hide()
                        focusManager.clearFocus()
                    },
                    focusRequester = focusRequester,
                    modifier = Modifier.fillMaxWidth(),
                    onBack = if (showBack) ({ state.back() }) else null
                )
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp)) {
                    if (pending && query.isNotBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    query.isBlank() -> SearchStart(state, margin, wide, short) {
                        query = it
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }
                    state.searchResults.isEmpty() && state.searchEpisodes.isEmpty() -> if (pending) {
                        LoadingPane()
                    } else {
                        EmptyState(
                            title = "没有匹配的结果",
                            description = "试试较短的关键词，或改用原名、演员名和类型。",
                            icon = Icons.Filled.SearchOff
                        ) {
                            TextButton(onClick = {
                                query = ""
                                focusRequester.requestFocus()
                                keyboard?.show()
                            }) { Text("换个关键词", style = MaterialTheme.typography.labelLargeEmphasized) }
                        }
                    }
                    else -> SearchResults(state, playback, gridState, margin, pending, onUseResult = {
                        state.rememberSearch(query)
                        keyboard?.hide()
                        focusManager.clearFocus()
                    })
                }
            }
        }
    }
}

/** Contained search editor used in-place so the browse pane owns focus, IME and back navigation. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BrowseSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String,
    hint: String,
    clearLabel: String,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline by animateColorAsState(
        if (focused) colors.primary else colors.outline,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "search-field-outline"
    )
    TextField(
        value = query,
        onValueChange = onQueryChange,
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            if (onBack == null) Icon(Icons.Filled.Search, contentDescription = null)
            else TipIconButton("返回（Alt + ←）", onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        trailingIcon = if (query.isNotEmpty()) ({
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, contentDescription = clearLabel)
            }
        }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interaction,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceContainerHigh,
            unfocusedContainerColor = colors.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedLeadingIconColor = colors.onSurfaceVariant,
            unfocusedLeadingIconColor = colors.onSurfaceVariant
        ),
        modifier = modifier
            .border(if (focused) 2.dp else 1.dp, outline, CircleShape)
            .focusRequester(focusRequester)
            .trackBrowseEditorFocus()
            .tracksTextInput()
            .semantics { contentDescription = label }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchResults(
    state: AppState,
    playback: PlaybackController,
    gridState: LazyGridState,
    margin: Dp,
    pending: Boolean,
    onUseResult: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontGrowth = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
        // In a narrow companion pane, or above a keyboard, put titles alongside artwork.
        val list = maxWidth < (600f + 120f * fontGrowth).dp || maxHeight < (360f + 120f * fontGrowth).dp
        LazyVerticalGrid(
            state = gridState,
            columns = if (list) GridCells.Fixed(1) else GridCells.Adaptive(minSize = 148.dp),
            contentPadding = PaddingValues(start = margin, end = margin, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(if (list) 8.dp else 16.dp)
        ) {
            item(key = "search-result-status", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (pending) "正在搜索…" else "搜索结果",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            if (state.searchResults.isNotEmpty()) {
                header("search-result-works", "影片与剧集", "${state.searchTotal} 项", Icons.Filled.Movie)
                results(state, playback, state.searchResults, list, onUseResult)
                if (state.searchLoadingMore) {
                    item(key = "search-result-more", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(72.dp)) { LoadingPane() }
                    }
                }
            }
            if (state.searchEpisodes.isNotEmpty()) {
                val shown = state.searchEpisodes.size
                header(
                    "search-result-episodes", "分集", if (shown >= EPISODE_RESULTS) "最相关的 $shown 集" else "$shown 集",
                    Icons.Filled.Slideshow
                )
                results(state, playback, state.searchEpisodes, list, onUseResult, episodes = true)
            }
        }
        VerticalScrollbarFor(gridState, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyGridScope.header(key: String, title: String, count: String, icon: ImageVector) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(
                title, style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape) {
                Text(count, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }
}

private fun LazyGridScope.results(
    state: AppState,
    playback: PlaybackController,
    items: List<MediaItemDto>,
    list: Boolean,
    onUseResult: () -> Unit,
    episodes: Boolean = false
) {
    items(items, key = { it.id }, span = { GridItemSpan(if (episodes) minOf(2, maxLineSpan) else 1) }) { item ->
        val libraryName = state.libraries.firstOrNull { it.id == item.libraryId }?.name
        val metadata = subtitleFor(item).takeIf { it.isNotBlank() }
        val subtitle = if (episodes) listOfNotNull(item.seriesName, metadata).joinToString(" · ")
            else listOfNotNull(metadata, libraryName).joinToString(" · ")
        val open = {
            onUseResult()
            state.openBrowseDetail(item.id)
        }
        val play = {
            onUseResult()
            playback.play(item)
        }
        if (list) {
            ListEntry(
                item = item,
                selected = null,
                downloaded = state.isDownloaded(item),
                menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                onClick = open,
                subtitle = subtitle,
                onPlay = play
            )
        } else {
            BoxWithConstraints {
                PosterCard(
                    item,
                    width = maxWidth,
                    shape = if (episodes) CardShape.LANDSCAPE else CardShape.PORTRAIT,
                    subtitle = if (episodes) metadata else subtitle,
                    downloaded = state.isDownloaded(item),
                    detailOpen = LocalSelectedBrowseItem.current == item.id,
                    menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                    onPlay = play,
                    onClick = open
                )
            }
        }
    }
}

@Composable
private fun SearchStart(state: AppState, margin: Dp, wide: Boolean, short: Boolean, onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = margin, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val history = state.searchHistory
        if (wide && !short && history.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SearchHistory(history, state::clearSearchHistory, onPick, Modifier.weight(1f).widthIn(max = 560.dp))
                SearchGuide(Modifier.widthIn(max = 360.dp).fillMaxWidth(), hasHistory = true)
            }
        } else if (history.isNotEmpty()) {
            SearchHistory(history, state::clearSearchHistory, onPick)
        } else {
            SearchGuide(Modifier.widthIn(max = 560.dp).fillMaxWidth(), hasHistory = false, short = short)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchHistory(history: List<String>, onClear: () -> Unit, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("最近搜索", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onClear, modifier = Modifier.semantics { contentDescription = "清空搜索历史" }) { Text("清空") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            history.forEachIndexed { index, past ->
                SegmentedListItem(
                    onClick = { onPick(past) },
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = history.size),
                    leadingContent = { Icon(Icons.Filled.History, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp)) }
                ) {
                    Text(past, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchGuide(modifier: Modifier, hasHistory: Boolean, short: Boolean = false) {
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(if (short) 16.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!short) Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(
                if (hasHistory) "搜索范围" else "从关键词开始",
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                "输入片名、原名、演员名或类型，即可搜索全部媒体库。也可以用分集名称查找对应的一集。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** How many episodes a search fetches; kept in step with AppState. */
private const val EPISODE_RESULTS = 30
