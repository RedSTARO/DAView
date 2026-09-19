package com.daview.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.app.data.AppState
import com.daview.app.data.LibraryLayout
import com.daview.app.data.OnEscape
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.data.tracksTextInput
import com.daview.shared.model.DownloadState
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
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

/** Card widths for the three densities. */
private val densityWidths = listOf(112.dp, 150.dp, 200.dp)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)
@Composable
fun LibraryScreen(state: AppState, playback: PlaybackController, libraryId: String) {
    val library = state.libraries.firstOrNull { it.id == libraryId }
    val view = state.viewOf(libraryId)
    val scope = rememberCoroutineScope()

    // Whether the list in hand is this library's. A re-sort keeps it true, so
    // the entries stay put while the new order is fetched; moving to another
    // library makes it false until that library's own entries arrive.
    val loaded = state.libraryItemsOf == libraryId

    // Filed with this page's stack entry, so it is still here when the page is
    // come back to — which is the loop picking something to watch actually is.
    val gridState = rememberLazyGridState()
    // The list view's own position. Jumping to a letter and "back to the top
    // after a new order" only ever moved the grid's, so in the list they did
    // nothing at all.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    suspend fun scrollTo(position: Int) {
        if (view.layout == LibraryLayout.LIST) listState.scrollToItem(position) else gridState.scrollToItem(position)
    }

    var searchOpen by rememberSaveable(libraryId) { mutableStateOf(state.searchOf(libraryId).isNotBlank()) }
    var searchText by rememberSaveable(libraryId) { mutableStateOf(state.searchOf(libraryId)) }
    // Set when the field is opened by hand; only then does it take the focus.
    // Coming back to the page with it open used to pull the keyboard up again.
    var focusSearch by remember { mutableStateOf(false) }
    var selection by remember(libraryId) { mutableStateOf<Set<String>?>(null) }
    var editOpen by remember { mutableStateOf(false) }

    LaunchedEffect(libraryId) {
        snapshotFlow { searchText }
            .debounce(250)
            .distinctUntilChanged()
            .collect { state.setLibrarySearch(libraryId, it) }
    }

    // "Clear filters" clears the search as well; the field has to say so,
    // or it shows words the list is no longer filtered by.
    LaunchedEffect(libraryId) {
        snapshotFlow { state.searchOf(libraryId) }.collect { stored ->
            if (stored.isEmpty() && searchText.isNotEmpty()) {
                searchText = ""
                searchOpen = false
            }
        }
    }

    // A new order or filter starts from the top of it.
    LaunchedEffect(libraryId) {
        var seen = state.libraryScrollToTop
        snapshotFlow { state.libraryScrollToTop }.collect {
            if (it != seen) {
                seen = it
                scrollTo(0)
            }
        }
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

    OnEscape(enabled = selection != null || searchOpen) {
        when {
            selection != null -> selection = null
            searchOpen -> {
                searchOpen = false
                searchText = ""
            }
        }
    }

    if (editOpen && library != null) {
        LibraryEditDialog(state, library, onDismiss = { editOpen = false })
    }

    val scan = state.scanStatus.firstOrNull { it.libraryId == libraryId && it.running }
    val selected = selection

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
            ScreenTopBar(
                title = library?.name ?: "媒体库",
                subtitle = if (loaded) {
                    // The real size, not how much of it happens to be loaded.
                    if (state.libraryItems.size < state.libraryTotal) {
                        "已载入 ${state.libraryItems.size} / 共 ${state.libraryTotal} 项"
                    } else "${state.libraryTotal} 项"
                } else null,
                onBack = if (state.canGoBack) ({ state.back() }) else null
            ) {
                TipIconButton(if (searchOpen) "关闭库内搜索（Esc）" else "在这个媒体库里搜索", onClick = {
                    searchOpen = !searchOpen
                    focusSearch = searchOpen
                    if (!searchOpen) searchText = ""
                }) {
                    Icon(
                        if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchOpen) "关闭库内搜索" else "在这个媒体库里搜索"
                    )
                }
                if (view.sort == "sortName") LetterJump(state, libraryId, view.descending) { position ->
                    scope.launch {
                        state.ensureLibraryLoaded(libraryId, position)
                        scrollTo(position.coerceIn(0, (state.libraryItems.size - 1).coerceAtLeast(0)))
                    }
                }
                TipIconButton(
                    if (view.layout == LibraryLayout.GRID) "列表视图" else "海报视图",
                    onClick = {
                        state.updateView(libraryId) {
                            it.copy(layout = if (it.layout == LibraryLayout.GRID) LibraryLayout.LIST else LibraryLayout.GRID)
                        }
                    }
                ) {
                    Icon(
                        if (view.layout == LibraryLayout.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                        contentDescription = if (view.layout == LibraryLayout.GRID) "切换为列表视图" else "切换为海报视图"
                    )
                }
                TipIconButton("多选", onClick = { selection = emptySet() }) {
                    Icon(Icons.Filled.Checklist, contentDescription = "进入多选")
                }
                LibraryMoreMenu(
                    state = state,
                    libraryId = libraryId,
                    density = view.density,
                    showDensity = view.layout == LibraryLayout.GRID,
                    onDensity = { d -> state.updateView(libraryId) { it.copy(density = d) } },
                    onEdit = { editOpen = true }
                )
            }
        }

        if (searchOpen && selected == null) {
            val focus = remember { FocusRequester() }
            // It opens to be typed into; a second click on the field was needed.
            LaunchedEffect(Unit) {
                if (focusSearch) {
                    focusSearch = false
                    runCatching { focus.requestFocus() }
                }
            }
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("在「${library?.name ?: "媒体库"}」中搜索") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .focusRequester(focus)
                    .tracksTextInput()
            )
        }

        FilterRow(state, libraryId)

        scan?.let { status ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    "正在扫描 · ${scanPhaseLabel(status.phase)}" + (if (status.total > 0) " ${status.current}/${status.total}" else ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (status.total > 0) {
                    LinearWavyProgressIndicator(progress = { status.current.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

        // The entries already on screen stay while a new order or filter is
        // fetched; a thin bar says something is coming.
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (state.libraryRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        when {
            state.libraryError != null -> EmptyState(
                title = "读取失败",
                description = state.libraryError.orEmpty()
            ) {
                Button(onClick = { state.loadLibrary(libraryId) }) { Text("重试") }
            }

            !loaded -> LoadingPane()

            state.libraryItems.isEmpty() -> {
                val narrowed = state.searchOf(libraryId).isNotBlank() || view.filtered
                EmptyState(
                    title = if (narrowed) "没有符合条件的条目" else "这个媒体库还是空的",
                    description = if (narrowed) "换个关键词，或者去掉筛选。"
                    else if (scan != null) "正在扫描，扫到的条目会出现在这里。"
                    else "扫描后，DAView 会读取目录里的视频并刮削元数据。"
                ) {
                    if (narrowed) {
                        Button(onClick = {
                            searchText = ""
                            searchOpen = false
                            state.clearLibraryFilters(libraryId)
                        }) { Text("清除筛选") }
                    } else if (scan == null) {
                        Button(onClick = { state.startScan(libraryId) }) { Text("扫描这个媒体库") }
                    }
                }
            }

            view.layout == LibraryLayout.LIST -> LibraryList(state, playback, selection, listState) { id ->
                selection = selection?.let { if (id in it) it - id else it + id }
            }

            else -> Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = densityWidths[view.density.coerceIn(0, 2)]),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
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
                                shape = CardShape.PORTRAIT,
                                downloaded = state.downloadOf(item.id)?.state == DownloadState.DONE,
                                selected = selected?.let { item.id in it },
                                menu = if (selected == null) ({ dismiss -> ItemMenuItems(state, playback, item, dismiss) }) else null,
                                // A series plays the episode it is up to.
                                onPlay = { playback.play(item) }
                            ) {
                                if (selected != null) {
                                    selection = if (item.id in selected) selected - item.id else selected + item.id
                                } else {
                                    state.navigate(Screen.Detail(item.id))
                                }
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

/** Filters, then sorts: the two things that decide which entries there are, and in what order. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilterRow(state: AppState, libraryId: String) {
    val view = state.viewOf(libraryId)
    // Scrollable: the sort buttons plus the filters do not fit across a 360dp
    // phone, and the last one used to be squeezed to a strip.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = view.onlyUnwatched,
            onClick = { state.updateView(libraryId) { it.copy(onlyUnwatched = !it.onlyUnwatched) } },
            label = { Text("未观看") }
        )
        FilterChip(
            selected = view.inProgress,
            onClick = { state.updateView(libraryId) { it.copy(inProgress = !it.inProgress) } },
            label = { Text("观看中") }
        )
        FilterChip(
            selected = view.onlyFavourite,
            onClick = { state.updateView(libraryId) { it.copy(onlyFavourite = !it.onlyFavourite) } },
            label = { Text("收藏") }
        )
        ChoiceChip(
            label = view.genre ?: "类型",
            selected = view.genre != null,
            options = listOf(null to "全部类型") + state.libraryGenres.map { it to it },
            onChoose = { genre -> state.updateView(libraryId) { it.copy(genre = genre) } }
        )
        ChoiceChip(
            label = view.decade?.let { "${it} 年代" } ?: "年代",
            selected = view.decade != null,
            options = listOf<Pair<Int?, String>>(null to "全部年代") + state.libraryDecades.map { it to "${it} 年代" },
            onChoose = { decade -> state.updateView(libraryId) { it.copy(decade = decade) } }
        )
        if (view.filtered) {
            TextButton(onClick = { state.clearLibraryFilters(libraryId) }) {
                Icon(Icons.Filled.FilterAltOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("清除筛选")
            }
        }
        Spacer(Modifier.width(4.dp))
        sortOptions.forEach { (key, label) ->
            ToggleButton(
                checked = view.sort == key,
                onCheckedChange = { state.setSort(key, libraryId) }
            ) {
                Text(label)
                if (view.sort == key) {
                    Spacer(Modifier.width(4.dp))
                    // Pressing the chosen field again flips this.
                    Icon(
                        if (view.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        contentDescription = if (view.descending) "降序" else "升序",
                        modifier = Modifier.height(16.dp)
                    )
                }
            }
        }
    }
}

/** A filter chip that opens a short list to pick from. */
@Composable
private fun <T> ChoiceChip(label: String, selected: Boolean, options: List<Pair<T, String>>, onChoose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) }
        )
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onChoose(value)
                    }
                )
            }
        }
    }
}

/**
 * Jumps to the first title under a letter. Latin titles sort by letter and
 * everything in Chinese or Japanese after them, so the index is the alphabet
 * plus one bucket for the rest.
 */
@Composable
private fun LetterJump(state: AppState, libraryId: String, descending: Boolean, onPosition: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val letters = ('a'..'z').toList()
    Box {
        TipIconButton("按首字母跳转", onClick = { open = true }) {
            Icon(Icons.Filled.SortByAlpha, contentDescription = "按首字母跳转")
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            fun jump(boundary: String) {
                open = false
                scope.launch { onPosition(state.libraryPosition(libraryId, boundary)) }
            }
            DropdownMenuItem(text = { Text("# 数字与符号") }, onClick = {
                open = false
                if (descending) jump("a") else onPosition(0)
            })
            letters.forEachIndexed { index, letter ->
                DropdownMenuItem(
                    text = { Text(letter.uppercaseChar().toString()) },
                    onClick = {
                        // Descending, a letter's titles start where the next
                        // letter's end.
                        if (descending) jump(letters.getOrNull(index + 1)?.toString() ?: "")
                        else jump(letter.toString())
                    }
                )
            }
            DropdownMenuItem(text = { Text("中文、日文等") }, onClick = {
                if (descending) {
                    open = false
                    onPosition(0)
                } else jump("")
            })
        }
    }
}

@Composable
private fun LibraryMoreMenu(
    state: AppState,
    libraryId: String,
    density: Int,
    showDensity: Boolean,
    onDensity: (Int) -> Unit,
    onEdit: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TipIconButton("更多", onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            ScanMenuItems(state, libraryId) { open = false }
            HorizontalDivider()
            if (showDensity) {
                listOf("小图", "中图", "大图").forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text("海报大小：$label") },
                        leadingIcon = {
                            if (density == index) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                        onClick = {
                            open = false
                            onDensity(index)
                        }
                    )
                }
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text("编辑媒体库…") },
                onClick = {
                    open = false
                    onEdit()
                }
            )
        }
    }
}

/** One row per entry, with the whole title and the facts a poster has no room for. */
@Composable
private fun LibraryList(
    state: AppState,
    playback: PlaybackController,
    selection: Set<String>?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggle: (String) -> Unit
) {
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val id = state.libraryItemsOf ?: return@collect
            if (last >= state.libraryItems.size - 12) state.loadMoreLibrary(id)
        }
    }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(state.libraryItems.size, key = { state.libraryItems[it].id }) { index ->
                val item = state.libraryItems[index]
                ListEntry(
                    item = item,
                    selected = selection?.let { item.id in it },
                    downloaded = state.downloadOf(item.id)?.state == DownloadState.DONE,
                    menu = { dismiss -> ItemMenuItems(state, playback, item, dismiss) },
                    onClick = {
                        if (selection != null) onToggle(item.id) else state.navigate(Screen.Detail(item.id))
                    }
                )
            }
        }
        VerticalScrollbarFor(listState, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ListEntry(
    item: MediaItemDto,
    selected: Boolean?,
    downloaded: Boolean,
    menu: @Composable androidx.compose.foundation.layout.ColumnScope.(dismiss: () -> Unit) -> Unit,
    onClick: () -> Unit
) {
    var menuAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var lastPointer by remember { mutableStateOf(androidx.compose.ui.input.pointer.PointerType.Unknown) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .secondaryClick(onPointerType = { lastPointer = it }, onOpen = { menuAt = it })
                .combinedClickable(
                    onClick = onClick,
                    onLongClickLabel = "打开菜单",
                    onLongClick = {
                        if (lastPointer != androidx.compose.ui.input.pointer.PointerType.Mouse) {
                            menuAt = androidx.compose.ui.geometry.Offset.Zero
                        }
                    }
                ),
            colors = CardDefaults.cardColors(
                containerColor = if (selected == true) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                }
                Surface(
                    modifier = Modifier.width(56.dp).aspectRatio(2f / 3f),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    if (item.posterUrl != null) {
                        AsyncImage(
                            model = item.posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else ArtworkPlaceholder(item.kind, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    item.originalName?.takeIf { it != item.name }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        listOfNotNull(
                            item.year?.toString(),
                            item.genres.take(3).joinToString(" / ").takeIf { it.isNotBlank() },
                            item.communityRating?.let { "评分 ${(it * 10).toInt() / 10.0}" },
                            when (item.kind) {
                                ItemKind.SERIES, ItemKind.SEASON -> item.episodeCount?.takeIf { it > 0 }?.let {
                                    "已看 ${item.playedEpisodeCount ?: 0} / $it 集"
                                }
                                else -> formatRuntime(item.runtimeMs)
                            },
                            "已下载".takeIf { downloaded }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.playedState == com.daview.shared.model.PlayedState.PLAYED) {
                    Icon(Icons.Filled.Check, contentDescription = "已观看", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        AppMenu(
            expanded = menuAt != null && selected == null,
            onDismissRequest = { menuAt = null },
            offset = menuAt.toDpOffset(density)
        ) {
            menu { menuAt = null }
        }
    }
}

/**
 * The bar a multi-select swaps in for the title: how many are picked, and what
 * can be done to all of them at once.
 */
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
    ScreenTopBar(
        title = "已选 $count 项",
        onBack = onClose
    ) {
        TextButton(onClick = onSelectAll) { Text("全选已载入") }
        var open by remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(onClick = { open = true }, enabled = count > 0) { Text("操作") }
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
    if (confirmMerge) {
        val target = items.first()
        AlertDialog(
            onDismissRequest = { confirmMerge = false },
            title = { Text("合并 ${items.size} 项") },
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
