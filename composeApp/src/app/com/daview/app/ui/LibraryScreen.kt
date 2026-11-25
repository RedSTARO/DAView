package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.app.data.AppState
import com.daview.app.data.LibraryLayout
import com.daview.app.data.LibraryView
import com.daview.app.data.ModalMarker
import com.daview.app.data.OnEscape
import com.daview.app.data.PlaybackController
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlayedState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val sortOptions = listOf(
    "sortName" to "名称",
    "year" to "年份",
    "added" to "最近添加",
    "rating" to "评分",
    "played" to "最近播放"
)

/** Minimum cell widths for the three user-selected poster sizes, not Material tokens. */
private val densityWidths = listOf(112.dp, 144.dp, 200.dp)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)
@Composable
fun LibraryScreen(state: AppState, playback: PlaybackController, libraryId: String, showBack: Boolean = state.canGoBack) {
    val activeItemId = LocalSelectedBrowseItem.current
    val library = state.libraries.firstOrNull { it.id == libraryId }
    val view = state.viewOf(libraryId)
    val loaded = state.libraryItemsOf == libraryId
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val currentLayout by rememberUpdatedState(view.layout)
    suspend fun scrollTo(position: Int) {
        if (currentLayout == LibraryLayout.LIST) listState.scrollToItem(position) else gridState.scrollToItem(position)
    }

    var searchOpen by rememberSaveable(libraryId) { mutableStateOf(state.searchOf(libraryId).isNotBlank()) }
    var searchText by rememberSaveable(libraryId) { mutableStateOf(state.searchOf(libraryId)) }
    // Only opening the field by hand should request focus, not returning from a detail.
    var focusSearch by remember { mutableStateOf(false) }
    var selection by remember(libraryId) { mutableStateOf<Set<String>?>(null) }
    var editOpen by remember { mutableStateOf(false) }

    LaunchedEffect(libraryId) {
        snapshotFlow { searchText }
            .debounce(250)
            .distinctUntilChanged()
            .collect { state.setLibrarySearch(libraryId, it) }
    }
    LaunchedEffect(libraryId) {
        var previous = state.searchOf(libraryId)
        snapshotFlow { state.searchOf(libraryId) }.collect { stored ->
            if (stored.isEmpty() && previous.isNotEmpty() && searchText.isNotEmpty()) {
                searchText = ""
                searchOpen = false
            }
            previous = stored
        }
    }
    LaunchedEffect(libraryId) {
        var seen = state.libraryScrollToTop
        snapshotFlow { state.libraryScrollToTop }.collect {
            if (it != seen) {
                seen = it
                scrollTo(0)
            }
        }
    }
    LaunchedEffect(gridState, libraryId) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.libraryItems.size - 12) state.loadMoreLibrary(libraryId)
        }
    }

    fun closeSearch() {
        searchOpen = false
        searchText = ""
    }
    fun clearFilters() {
        closeSearch()
        state.clearLibraryFilters(libraryId)
    }
    fun toggleLayout() {
        state.updateView(libraryId) {
            it.copy(layout = if (it.layout == LibraryLayout.GRID) LibraryLayout.LIST else LibraryLayout.GRID)
        }
    }
    val jumpTo: (String?) -> Unit = { boundary ->
        // The popup dismisses immediately; the page owns the lookup and scroll.
        scope.launch {
            val position = boundary?.let { state.libraryPosition(libraryId, it) } ?: 0
            state.ensureLibraryLoaded(libraryId, position)
            scrollTo(position.coerceIn(0, (state.libraryItems.size - 1).coerceAtLeast(0)))
        }
    }
    OnEscape(enabled = selection != null || searchOpen) {
        if (selection != null) selection = null else closeSearch()
    }
    if (editOpen && library != null) {
        LibraryEditDialog(state, library, onDismiss = { editOpen = false })
    }

    val scan = state.scanStatus.firstOrNull { it.libraryId == libraryId && it.running }
    val selected = selection
    val narrowed = state.searchOf(libraryId).isNotBlank() || view.filtered
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontGrowth = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
        val margin = pageMargin(maxWidth)
        val tight = maxHeight < (400f + 100f * fontGrowth).dp
        val veryShort = maxHeight < (300f + 80f * fontGrowth).dp
        val editorOnly = searchOpen && veryShort
        val inlineFilters = maxWidth >= (600f + 160f * fontGrowth).dp && !tight
        val expandedTools = maxWidth >= (840f + 240f * fontGrowth).dp && !tight
        val wideHeading = maxWidth >= (600f + 160f * fontGrowth).dp && !tight
        val cellWidth = densityWidths[view.density.coerceIn(densityWidths.indices)]
        Column(Modifier.fillMaxSize()) {
            if (selected != null) {
                SelectionBar(
                    state = state,
                    count = selected.size,
                    items = state.libraryItems.filter { it.id in selected },
                    onSelectAll = { selection = state.libraryItems.map { it.id }.toSet() },
                    onClose = { selection = null }
                )
            } else {
                BrowsePageHeading(
                    title = library?.name ?: "媒体库",
                    context = if (veryShort) null else library?.let { "媒体库 · ${kindLabel(it.kind)}" },
                    status = if (loaded && !veryShort) {
                        val unit = if (narrowed) "项匹配" else "项"
                        if (state.libraryItems.size < state.libraryTotal) {
                            "已载入 ${state.libraryItems.size} / ${state.libraryTotal} $unit"
                        } else "${state.libraryTotal} $unit"
                    } else null,
                    margin = margin,
                    prominent = wideHeading,
                    onBack = if (showBack) ({ state.back() }) else null
                ) {
                    TipIconButton(if (searchOpen) "关闭库内搜索（Esc）" else "在这个媒体库里搜索", onClick = {
                        if (searchOpen) closeSearch() else {
                            focusSearch = true
                            searchOpen = true
                        }
                    }) {
                        Icon(
                            if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searchOpen) "关闭库内搜索" else "在这个媒体库里搜索"
                        )
                    }
                    // A short IME pane leaves room for the title and close-search, not another action.
                    if (!editorOnly) {
                        LibraryMoreMenu(
                            state = state,
                            libraryId = libraryId,
                            view = view,
                            onEdit = { editOpen = true },
                            compactActions = if (expandedTools) null else { dismiss ->
                                DropdownMenuItem(
                                    text = { Text(layoutAction(view.layout)) },
                                    onClick = { dismiss(); toggleLayout() }
                                )
                                DropdownMenuItem(
                                    text = { Text("进入多选") },
                                    onClick = { dismiss(); selection = emptySet() }
                                )
                            }
                        )
                    }
                }
            }

            if (searchOpen && selected == null) {
                val focusRequester = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(focusSearch) {
                    if (focusSearch) {
                        focusSearch = false
                        runCatching { focusRequester.requestFocus() }
                    }
                }
                BrowseSearchField(
                    query = searchText,
                    onQueryChange = { searchText = it },
                    label = "在「${library?.name ?: "媒体库"}」中搜索",
                    hint = "搜索此媒体库",
                    clearLabel = "清空库内搜索",
                    onSearch = { keyboard?.hide() },
                    focusRequester = focusRequester,
                    modifier = Modifier.padding(start = margin, end = margin, bottom = 8.dp)
                        .widthIn(max = 720.dp).fillMaxWidth()
                )
            }

            if (selected == null && !editorOnly) {
                LibraryBrowseTools(
                    state, libraryId, view, margin, inlineFilters, expandedTools, narrowed,
                    clearFilters = ::clearFilters, jumpTo = jumpTo, toggleLayout = ::toggleLayout,
                    selectItems = { selection = emptySet() }
                )
            }

            if (!editorOnly) scan?.let { status ->
                Column(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = 8.dp)) {
                    Text(
                        "正在扫描 · ${scanPhaseLabel(status.phase)}" + (if (status.total > 0) " ${status.current}/${status.total}" else ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    if (status.total > 0) {
                        LinearWavyProgressIndicator(progress = { status.current.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }

            // Keep the entries in place while a new order or filter is fetched.
            Box(Modifier.fillMaxWidth().padding(horizontal = margin).height(4.dp)) {
                if (state.libraryRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val compactRows = maxHeight < cellWidth * 1.5f + (48f + 48f * fontGrowth).dp
                when {
                    state.libraryError != null -> EmptyState("读取失败", state.libraryError.orEmpty(), icon = Icons.Filled.ErrorOutline) {
                        Button(onClick = { state.loadLibrary(libraryId) }) {
                            Text("重试", style = MaterialTheme.typography.labelLargeEmphasized)
                        }
                    }
                    !loaded -> LoadingPane()
                    state.libraryItems.isEmpty() -> EmptyState(
                        title = if (narrowed) "没有符合条件的条目" else "这个媒体库还是空的",
                        description = when {
                            narrowed -> "换个关键词，或者清除筛选后浏览全部条目。"
                            scan != null -> "扫描到的条目会陆续出现在这里。"
                            else -> "扫描媒体库，DAView 会读取目录里的视频并获取海报和简介。"
                        },
                        icon = if (narrowed) Icons.Filled.SearchOff else Icons.Filled.GridView
                    ) {
                        if (narrowed) {
                            Button(onClick = ::clearFilters) {
                                Text("清除筛选", style = MaterialTheme.typography.labelLargeEmphasized)
                            }
                        } else if (scan == null) {
                            Button(onClick = { state.startScan(libraryId) }) {
                                Text("扫描这个媒体库", style = MaterialTheme.typography.labelLargeEmphasized)
                            }
                        }
                    }
                    view.layout == LibraryLayout.LIST -> LibraryList(state, playback, selected, listState, margin) { id ->
                        selection = selection?.let { if (id in it) it - id else it + id }
                    }
                    else -> {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = if (compactRows) GridCells.Fixed(1) else GridCells.Adaptive(minSize = cellWidth),
                            contentPadding = PaddingValues(start = margin, end = margin, top = 8.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(if (compactRows) 8.dp else 16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.libraryItems, key = { it.id }) { item ->
                                val checked = selected?.contains(item.id)
                                val open = {
                                    if (selected == null) state.openBrowseDetail(item.id)
                                    else selection = if (checked == true) selected - item.id else selected + item.id
                                }
                                if (compactRows) {
                                    // Still the same keyed grid, so its anchor survives the IME or fold.
                                    ListEntry(
                                        item = item,
                                        selected = checked,
                                        downloaded = state.isDownloaded(item),
                                        menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                                        onClick = open,
                                        onPlay = if (selected == null) ({ playback.play(item) }) else null
                                    )
                                } else {
                                    BoxWithConstraints {
                                        PosterCard(
                                            item,
                                            width = maxWidth,
                                            shape = CardShape.PORTRAIT,
                                            downloaded = state.isDownloaded(item),
                                            selected = checked,
                                            detailOpen = activeItemId == item.id,
                                            menu = if (selected == null) ({ dismiss -> ItemMenuItems(state, playback, item, dismiss) }) else null,
                                            onPlay = { playback.play(item) },
                                            onClick = open
                                        )
                                    }
                                }
                            }
                            if (state.libraryLoadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(Modifier.fillMaxWidth().height(72.dp)) { LoadingPane() }
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

/** In-pane editorial heading; the leading and trailing controls still use native button targets. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BrowsePageHeading(
    title: String,
    context: String?,
    status: String?,
    margin: Dp,
    prominent: Boolean,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp).padding(horizontal = margin, vertical = if (prominent) 16.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TipIconButton("返回（Alt + ←）", onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            context?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                title,
                style = if (prominent) MaterialTheme.typography.displaySmallEmphasized else MaterialTheme.typography.headlineSmallEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
            if (!prominent && status != null) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (prominent && status != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(status, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        actions()
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryBrowseTools(
    state: AppState,
    libraryId: String,
    view: LibraryView,
    margin: Dp,
    inlineFilters: Boolean,
    expandedTools: Boolean,
    narrowed: Boolean,
    clearFilters: () -> Unit,
    jumpTo: (String?) -> Unit,
    toggleLayout: () -> Unit,
    selectItems: () -> Unit
) {
    val filters = listOf(
        LibraryQuickFilter("未观看", view.onlyUnwatched) { state.updateView(libraryId) { it.copy(onlyUnwatched = !it.onlyUnwatched) } },
        LibraryQuickFilter("观看中", view.inProgress) { state.updateView(libraryId) { it.copy(inProgress = !it.inProgress) } },
        LibraryQuickFilter("收藏", view.onlyFavourite) { state.updateView(libraryId) { it.copy(onlyFavourite = !it.onlyFavourite) } }
    )
    val genre = LibraryFilterChoice(
        "类型", view.genre, listOf(null to "全部类型") + state.libraryGenres.map { it to it }
    ) { value -> state.updateView(libraryId) { it.copy(genre = value) } }
    val decade = LibraryFilterChoice(
        "年代", view.decade, listOf(null to "全部年代") + state.libraryDecades.map { it to "$it 年代" }
    ) { value -> state.updateView(libraryId) { it.copy(decade = value) } }
    val activeCount = filters.count { it.checked } + listOfNotNull(view.genre, view.decade).size

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = margin),
        shape = if (inlineFilters) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (inlineFilters) 16.dp else 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            if (inlineFilters) {
                LibraryFilterGroup(
                    filters, genre, decade, narrowed, clearFilters,
                    modifier = if (expandedTools) Modifier.weight(1f) else Modifier.fillMaxWidth()
                )
            }
            if (!inlineFilters) CompactLibraryFilters(filters, genre, decade, activeCount, narrowed, clearFilters)
            LibrarySortMenu(view, onSort = { state.setSort(it, libraryId) })
            if (view.sort == "sortName") LetterJump(view.descending, jumpTo)
            if (expandedTools) {
                TipIconButton(layoutAction(view.layout), onClick = toggleLayout) {
                    Icon(
                        if (view.layout == LibraryLayout.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                        contentDescription = layoutAction(view.layout)
                    )
                }
                TipIconButton("多选", onClick = selectItems) {
                    Icon(Icons.Filled.Checklist, contentDescription = "进入多选")
                }
            }
        }
    }
}

private data class LibraryQuickFilter(val label: String, val checked: Boolean, val toggle: () -> Unit)

private data class LibraryFilterChoice<T>(
    val name: String,
    val value: T?,
    val options: List<Pair<T?, String>>,
    val choose: (T?) -> Unit
) {
    val activeLabel: String? get() = options.firstOrNull { it.first != null && it.first == value }?.second
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryFilterGroup(
    filters: List<LibraryQuickFilter>,
    genre: LibraryFilterChoice<String>,
    decade: LibraryFilterChoice<Int>,
    narrowed: Boolean,
    clearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
            Text("筛选", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        filters.forEach { filter ->
            LibraryFilterChip(filter.label, filter.checked, filter.toggle)
        }
        ChoiceChip(genre)
        ChoiceChip(decade)
        if (narrowed) {
            TextButton(onClick = clearFilters) {
                Icon(Icons.Filled.FilterAltOff, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("清除筛选")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryFilterChip(label: String, checked: Boolean, onClick: () -> Unit, hasMenu: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = checked,
        onClick = onClick,
        shapes = FilterChipDefaults.shapes(),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = colors.secondaryContainer,
            selectedLabelColor = colors.onSecondaryContainer,
            selectedLeadingIconColor = colors.onSecondaryContainer,
            selectedTrailingIconColor = colors.onSecondaryContainer
        ),
        label = {
            Text(
                label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (checked) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge
            )
        },
        leadingIcon = if (checked) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
        trailingIcon = if (hasMenu) ({ Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null
    )
}

@Composable
private fun <T> ChoiceChip(choice: LibraryFilterChoice<T>) {
    var open by remember { mutableStateOf(false) }
    Box {
        LibraryFilterChip(choice.activeLabel ?: choice.name, choice.value != null, { open = true }, hasMenu = true)
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            FilterChoiceMenuItems(choice) { open = false }
        }
    }
}

@Composable
private fun <T> FilterChoiceMenuItems(choice: LibraryFilterChoice<T>, dismiss: () -> Unit) {
    choice.options.forEach { (value, label) ->
        FilterMenuItem(label, checked = value == choice.value) {
            dismiss()
            choice.choose(value)
        }
    }
}

private enum class LibraryFilterMenuPage { Filters, Genre, Decade }

/** One pane-aware menu at a time; selecting type or decade never opens a popup across a hinge. */
@Composable
private fun CompactLibraryFilters(
    filters: List<LibraryQuickFilter>,
    genre: LibraryFilterChoice<String>,
    decade: LibraryFilterChoice<Int>,
    activeCount: Int,
    narrowed: Boolean,
    clearFilters: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(LibraryFilterMenuPage.Filters) }
    Box {
        TextButton(onClick = {
            page = LibraryFilterMenuPage.Filters
            open = true
        }) {
            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (activeCount == 0) "筛选" else "筛选 ($activeCount)")
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            if (page != LibraryFilterMenuPage.Filters) {
                DropdownMenuItem(
                    text = { Text("返回筛选") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                    onClick = { page = LibraryFilterMenuPage.Filters }
                )
                HorizontalDivider()
            }
            when (page) {
                LibraryFilterMenuPage.Filters -> {
                    filters.forEach { filter -> FilterMenuItem(filter.label, filter.checked, filter.toggle) }
                    HorizontalDivider()
                    listOf(genre to LibraryFilterMenuPage.Genre, decade to LibraryFilterMenuPage.Decade).forEach { (choice, next) ->
                        DropdownMenuItem(
                            text = { Text(choice.activeLabel?.let { "${choice.name}：$it" } ?: choice.name) },
                            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            onClick = { page = next }
                        )
                    }
                    if (narrowed) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("清除筛选") }, onClick = { open = false; clearFilters() })
                    }
                }
                LibraryFilterMenuPage.Genre -> FilterChoiceMenuItems(genre) { open = false }
                LibraryFilterMenuPage.Decade -> FilterChoiceMenuItems(decade) { open = false }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilterMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        modifier = Modifier.semantics { selected = checked },
        text = { Text(label, style = if (checked) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge) },
        leadingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
        onClick = onClick
    )
}

@Composable
private fun LibrarySortMenu(view: LibraryView, onSort: (String) -> Unit) {
    val label = sortOptions.firstOrNull { it.first == view.sort }?.second ?: "排序"
    val direction = if (view.descending) "降序" else "升序"
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics {
            contentDescription = "排序"
            stateDescription = "$label，$direction"
        }) {
            Icon(
                if (view.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = null, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            sortOptions.forEach { (key, title) ->
                FilterMenuItem(title, checked = view.sort == key) { open = false; onSort(key) }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (view.descending) "改为升序" else "改为降序") },
                onClick = { open = false; onSort(view.sort) }
            )
        }
    }
}

/** Jumps to the first item filed under a letter or one of the extra name buckets. */
@Composable
private fun LetterJump(descending: Boolean, onPosition: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TipIconButton("按首字母跳转", onClick = { open = true }) {
            Icon(Icons.Filled.SortByAlpha, contentDescription = "按首字母跳转")
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            fun jump(boundary: String?) {
                open = false
                onPosition(boundary)
            }
            DropdownMenuItem(text = { Text("# 数字与符号") }, onClick = { jump("a".takeIf { descending }) })
            for (letter in 'a'..'z') {
                DropdownMenuItem(text = { Text(letter.uppercaseChar().toString()) }, onClick = {
                    // In descending order, the boundary is the next letter.
                    jump(if (!descending) letter.toString() else if (letter < 'z') (letter + 1).toString() else "\u007f")
                })
            }
            DropdownMenuItem(text = { Text("中文、日文等") }, onClick = { jump("\u007f".takeUnless { descending }) })
        }
    }
}

private fun layoutAction(layout: LibraryLayout) = if (layout == LibraryLayout.GRID) "切换为列表视图" else "切换为海报视图"

@Composable
private fun LibraryMoreMenu(
    state: AppState,
    libraryId: String,
    view: LibraryView,
    onEdit: () -> Unit,
    compactActions: (@Composable (dismiss: () -> Unit) -> Unit)?
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TipIconButton("媒体库选项", onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            compactActions?.let {
                it { open = false }
                HorizontalDivider()
            }
            if (view.layout == LibraryLayout.GRID) {
                listOf("小图", "中图", "大图").forEachIndexed { index, label ->
                    FilterMenuItem("海报大小：$label", view.density == index) {
                        open = false
                        state.updateView(libraryId) { it.copy(density = index) }
                    }
                }
                HorizontalDivider()
            }
            ScanMenuItems(state, libraryId) { open = false }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("编辑媒体库…") }, onClick = { open = false; onEdit() })
        }
    }
}

/** One row per entry, with the title and the facts a poster has no room for. */
@Composable
private fun LibraryList(
    state: AppState,
    playback: PlaybackController,
    selection: Set<String>?,
    listState: LazyListState,
    margin: Dp,
    onToggle: (String) -> Unit
) {
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val id = state.libraryItemsOf ?: return@collect
            if (last >= state.libraryItems.size - 12) state.loadMoreLibrary(id)
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = margin, end = margin, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(state.libraryItems, key = { it.id }) { item ->
                ListEntry(
                    item = item,
                    selected = selection?.let { item.id in it },
                    downloaded = state.isDownloaded(item),
                    menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                    onClick = { if (selection != null) onToggle(item.id) else state.openBrowseDetail(item.id) }
                )
            }
            if (state.libraryLoadingMore) item { Box(Modifier.fillMaxWidth().height(72.dp)) { LoadingPane() } }
        }
        VerticalScrollbarFor(listState, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListEntry(
    item: MediaItemDto,
    selected: Boolean?,
    downloaded: Boolean,
    menu: @Composable androidx.compose.foundation.layout.ColumnScope.(dismiss: () -> Unit) -> Unit,
    onClick: () -> Unit,
    subtitle: String? = null,
    onPlay: (() -> Unit)? = null,
    landscape: Boolean = item.kind == ItemKind.EPISODE
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var lastPointer by remember { mutableStateOf(PointerType.Unknown) }
    val density = LocalDensity.current
    val selecting by rememberUpdatedState(selected != null)
    val detailOpen = selected == null && LocalSelectedBrowseItem.current == item.id
    val colors = MaterialTheme.colorScheme
    val highlighted = selected == true || detailOpen
    val metadata = subtitle ?: listOfNotNull(
        item.year?.toString(),
        item.genres.take(3).joinToString(" / ").takeIf { it.isNotBlank() },
        item.communityRating?.let { "评分 ${(it * 10).toInt() / 10.0}" },
        when (item.kind) {
            ItemKind.SERIES, ItemKind.SEASON -> item.episodeCount?.takeIf { it > 0 }?.let {
                "已看 ${item.playedEpisodeCount ?: 0} / $it 集"
            }
            else -> formatRuntime(item.runtimeMs)
        }
    ).joinToString(" · ")
    val watchStatus = when {
        item.playedState == PlayedState.PLAYED -> "已观看".takeIf { onPlay != null }
        item.userData.playedPercentage > 0.01 -> "已看 ${(item.userData.playedPercentage * 100).toInt()}%"
        else -> null
    }
    val supporting = listOfNotNull(metadata.takeIf { it.isNotBlank() }, watchStatus, "已下载".takeIf { downloaded }).joinToString(" · ")
    Box {
        ListItem(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
                .secondaryClick(onPointerType = { lastPointer = it }, onOpen = { if (!selecting) menuAt = it })
                .semantics {
                    if (selected != null) {
                        role = Role.Checkbox
                        toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                    } else {
                        role = Role.Button
                        if (detailOpen) stateDescription = "正在查看"
                    }
                },
            onLongClickLabel = "打开菜单".takeUnless { selecting },
            onLongClick = if (selected == null) ({
                if (!selecting && lastPointer != PointerType.Mouse) menuAt = Offset.Zero
            }) else null,
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selected != null) Checkbox(checked = selected, onCheckedChange = null)
                    val art = if (landscape && item.kind != ItemKind.EPISODE) item.backdropUrl ?: item.posterUrl else item.posterUrl
                    Surface(
                        modifier = Modifier.width(if (landscape) 80.dp else 48.dp)
                            .aspectRatio(if (landscape) 16f / 9f else 2f / 3f),
                        shape = MaterialTheme.shapes.small,
                        color = colors.surfaceContainerHighest
                    ) {
                        if (art != null) {
                            AsyncImage(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else ArtworkPlaceholder(item.kind, Modifier.fillMaxSize())
                    }
                }
            },
            supportingContent = if (supporting.isNotEmpty()) ({ Text(supporting, style = MaterialTheme.typography.bodySmall) }) else null,
            trailingContent = when {
                onPlay != null -> ({
                    TipIconButton("播放", onClick = onPlay) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = if (item.isPlayable) "播放" else "播放下一集")
                    }
                })
                detailOpen -> ({ Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) })
                item.playedState == PlayedState.PLAYED -> ({ Icon(Icons.Filled.Check, contentDescription = "已观看") })
                else -> null
            },
            verticalAlignment = Alignment.CenterVertically,
            shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
            colors = ListItemDefaults.colors(
                containerColor = if (highlighted) colors.secondaryContainer else colors.surfaceContainerLow,
                contentColor = if (highlighted) colors.onSecondaryContainer else colors.onSurface,
                trailingContentColor = if (highlighted) colors.onSecondaryContainer else colors.onSurfaceVariant,
                supportingContentColor = if (highlighted) colors.onSecondaryContainer else colors.onSurfaceVariant
            )
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.name, style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = if (subtitle == null) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis
                )
                item.originalName?.takeIf { subtitle == null && it != item.name }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (detailOpen || selected == true) colors.onSecondaryContainer else colors.onSurfaceVariant)
                }
            }
        }
        AppMenu(expanded = menuAt != null && selected == null, onDismissRequest = { menuAt = null }, offset = menuAt.toDpOffset(density)) {
            menu { menuAt = null }
        }
    }
}

/** The contextual actions that replace the page heading while picking entries. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SelectionBar(
    state: AppState,
    count: Int,
    items: List<MediaItemDto>,
    onSelectAll: () -> Unit,
    onClose: () -> Unit
) {
    var confirmMerge by remember { mutableStateOf(false) }
    val mergeable = items.size >= 2 && items.map { it.kind }.distinct().size == 1 &&
        items.first().kind.let { it == ItemKind.MOVIE || it == ItemKind.SERIES }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val margin = pageMargin(maxWidth)
        val fontGrowth = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
        val iconActions = maxWidth < (360f + 160f * fontGrowth).dp
        BrowsePageHeading(title = "已选 $count 项", context = null, status = null, margin = margin, prominent = false, onBack = onClose) {
            if (iconActions) {
                TipIconButton("全选已载入", onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, contentDescription = "全选已载入") }
            } else {
                TextButton(onClick = onSelectAll) { Text("全选已载入") }
            }
            var open by remember { mutableStateOf(false) }
            Box {
                if (iconActions) {
                    TipIconButton("操作", onClick = { open = true }, enabled = count > 0) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = "操作")
                    }
                } else {
                    FilledTonalButton(onClick = { open = true }, enabled = count > 0) {
                        Text("操作", style = MaterialTheme.typography.labelLargeEmphasized)
                    }
                }
                AppMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("标记为已观看") }, onClick = {
                        open = false; state.markMany(items, true); onClose()
                    })
                    DropdownMenuItem(text = { Text("标记为未观看") }, onClick = {
                        open = false; state.markMany(items, false); onClose()
                    })
                    DropdownMenuItem(text = { Text("收藏") }, onClick = {
                        open = false; state.favouriteMany(items, true); onClose()
                    })
                    DropdownMenuItem(text = { Text("取消收藏") }, onClick = {
                        open = false; state.favouriteMany(items, false); onClose()
                    })
                    DropdownMenuItem(text = { Text("下载到本机") }, onClick = {
                        open = false; state.downloadMany(items); onClose()
                    })
                    if (mergeable) {
                        DropdownMenuItem(text = { Text("合并为一项…") }, onClick = {
                            open = false; confirmMerge = true
                        })
                    }
                }
            }
        }
    }
    if (confirmMerge) {
        // Esc belongs to the dialog, not to the selection behind it.
        ModalMarker()
        val target = items.first()
        AdaptiveAlertDialog(
            onDismissRequest = { confirmMerge = false },
            title = { Text("合并 ${items.size} 项", style = MaterialTheme.typography.headlineSmallEmphasized) },
            text = {
                Text(
                    "其余 ${items.size - 1} 项会并入「${target.name}」：它们的季与分集移到这里，自己不再单独出现。" +
                        "原始记录会保留，可以在该条目的「合并重复条目」里拆分。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmMerge = false
                    state.mergeInto(target, items.drop(1))
                    onClose()
                }) { Text("合并") }
            },
            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text("取消") } }
        )
    }
}
