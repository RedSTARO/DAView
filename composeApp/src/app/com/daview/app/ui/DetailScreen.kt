@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.daview.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.daview.app.data.AppState
import com.daview.app.data.Confirmation
import com.daview.app.data.ModalMarker
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.platform.openUrl
import com.daview.app.platform.pickImageFile
import com.daview.shared.model.DownloadDto
import com.daview.shared.model.DownloadState
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlayedState
import com.daview.shared.model.SUBTITLE_OFF
import com.daview.shared.model.ScrapeStatus
import com.daview.shared.model.StreamType
import kotlinx.coroutines.launch

private val SingleReadingWidth = 880.dp
private val SplitReadingWidth = 1320.dp
private val DetailBackHeight = 48.dp
private const val HeroKey = "hero"
private const val PeopleKey = "people"
private const val SeasonKey = "seasons"
private const val RelatedKey = "related"
private const val FileKey = "files"
private const val EpisodeKeyPrefix = "episode:"
private fun episodeKey(id: String) = "$EpisodeKeyPrefix$id"
private fun String.episodeId() = removePrefix(EpisodeKeyPrefix).takeIf { it != this }

@Composable
fun DetailScreen(state: AppState, playback: PlaybackController, itemId: String, entryKey: Long? = null) {
    // The host saves this page under its back-stack entry key, not its media id.
    val bookmark = rememberSaveable(itemId, stateSaver = detailBookmarkSaver) { mutableStateOf(DetailBookmark()) }
    var chosenSeason by rememberSaveable(itemId) { mutableStateOf<String?>(null) }
    var expandedSynopsis by rememberSaveable(itemId) { mutableStateOf<String?>(null) }
    val item = state.detailItem?.takeIf { it.id == itemId }
    val active = state.current == Screen.Detail(itemId) && (entryKey == null || state.currentEntry.key == entryKey)
    val canGoBack = active && state.canGoBack
    val topOverlap = LocalBrowseTopOverlap.current
    val surface = MaterialTheme.colorScheme.surface
    val statusScrim = if (topOverlap > 0.dp) Modifier.drawWithContent {
        drawContent()
        // Artwork scrolls with the hero, but system icon contrast must not.
        val inset = topOverlap.toPx().coerceAtMost(size.height)
        val fadeEnd = (inset + 8.dp.toPx()).coerceAtMost(size.height)
        if (fadeEnd > 0f) {
            val tint = surface.copy(alpha = 0.76f)
            drawRect(
                brush = Brush.verticalGradient(0f to tint, inset / fadeEnd to tint, 1f to Color.Transparent, endY = fadeEnd),
                size = Size(size.width, fadeEnd)
            )
        }
    } else Modifier

    Column(Modifier.fillMaxSize().then(statusScrim).browseSafeTop(topOverlap).then(if (item == null) Modifier.clipToBounds() else Modifier)) {
        // One Back target, even while the detail is loading or its lists are rearranged.
        if (canGoBack) {
            Row(
                Modifier.fillMaxWidth().height(DetailBackHeight).padding(start = 12.dp).zIndex(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tooltip("返回（Alt + ←）") {
                    FilledTonalIconButton(
                        onClick = { state.back() },
                        shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            }
        }
        if (item == null) {
            val error = state.detailError
            if (error != null) {
                LazyColumn(Modifier.readingWidth(SingleReadingWidth).fillMaxHeight(), contentPadding = PaddingValues(16.dp)) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.large
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                DetailHeading("读取失败")
                                Text(error, style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { if (active) state.loadDetail(itemId) }, shapes = ButtonDefaults.shapes()) {
                                    Text("重试", style = MaterialTheme.typography.labelLargeEmphasized)
                                }
                            }
                        }
                    }
                }
            } else LoadingPane()
        } else {
            val children = state.detailChildren
            val seasons = remember(children) { children.filter { it.kind == ItemKind.SEASON } }
            val related = remember(children) { children.filter { it.kind == ItemKind.MOVIE } }
            val modelSeason = state.detailSeasonId
            val selected = seasons.firstOrNull { it.id == chosenSeason } ?: seasons.firstOrNull { it.id == modelSeason }
            val restoringSeason = selected != null && selected.id != modelSeason
            LaunchedEffect(active, selected?.id, modelSeason) {
                if (active && selected != null) {
                    chosenSeason = selected.id
                    if (restoringSeason) state.selectSeason(selected.id)
                }
            }
            val episodeSource = when {
                restoringSeason -> emptyList()
                item.kind == ItemKind.SEASON -> children
                else -> state.detailEpisodes
            }
            val episodes = remember(episodeSource) { episodeSource.filter { it.kind == ItemKind.EPISODE } }
            val loading = restoringSeason || state.seasonLoading || (state.detailLoading && episodes.isEmpty())
            val error = state.seasonError.takeUnless { restoringSeason }
            val seasonOverview = selected?.overview?.takeIf { overview ->
                overview.isNotBlank() && seasons.mapNotNull { it.indexNumber }.filter { it > 0 }.distinct().size >= 2
            }
            val synopsisId = selected?.id?.takeIf { seasonOverview != null } ?: item.id
            DetailBody(
                state, playback, item, seasons, selected, episodes, related, bookmark, loading, error,
                heroOverlap = topOverlap + if (topOverlap > 0.dp && canGoBack) DetailBackHeight else 0.dp,
                overview = seasonOverview ?: item.overview?.takeIf { it.isNotBlank() },
                expanded = expandedSynopsis == synopsisId,
                onToggleOverview = { expandedSynopsis = if (expandedSynopsis == synopsisId) null else synopsisId },
                onSeason = { id ->
                    if (active && (id != selected?.id || error != null)) {
                        chosenSeason = id
                        state.selectSeason(id)
                    }
                }
            )
        }
    }
}

@Composable
private fun DetailBody(
    state: AppState,
    playback: PlaybackController,
    item: MediaItemDto,
    seasons: List<MediaItemDto>,
    selected: MediaItemDto?,
    episodes: List<MediaItemDto>,
    related: List<MediaItemDto>,
    bookmark: MutableState<DetailBookmark>,
    loading: Boolean,
    error: String?,
    heroOverlap: Dp,
    overview: String?,
    expanded: Boolean,
    onToggleOverview: () -> Unit,
    onSeason: (String) -> Unit
) {
    val seasonStrip = rememberLazyListState(seasons.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0))
    val peopleStrip = rememberLazyListState()
    val episodesById = remember(episodes) { episodes.associateBy { it.id } }
    val episodeIds = remember(episodesById) { episodesById.keys.toList() }
    val episodeIndices = remember(episodeIds) { episodeIds.withIndex().associate { (index, id) -> id to index } }
    val currentEpisode = item.id.takeIf { item.kind == ItemKind.EPISODE && it in episodesById }
    val hasEpisodeSection = item.kind == ItemKind.SERIES || item.kind == ItemKind.SEASON || episodes.isNotEmpty()
    val hasSeasonChoices = seasons.size >= 2 && (episodes.isNotEmpty() || seasons.any { (it.episodeCount ?: 0) > 0 })

    // This is already the safe contiguous pane assigned by Browse, less the Back row.
    // Do not infer geometry from window metrics or subtract a hinge/system bar again.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val extraText = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
        val twoPanes = detailUsesTwoPanes(
            maxWidth, maxHeight, LocalDensity.current.fontScale, LocalBrowseCompanionVisible.current,
            episodeCount = if (loading) maxOf(episodes.size, selected?.episodeCount ?: 0) else episodes.size,
            hasSeasonChoices = hasSeasonChoices, relatedCount = related.size
        )
        val short = maxHeight < detailMinHeight(extraText)
        val narrow = maxWidth < (340f + 140f * extraText).dp
        val horizontal = when {
            twoPanes || maxWidth >= 600.dp -> 24.dp
            maxWidth < 260.dp -> 12.dp
            else -> 16.dp
        }
        val compact = short || narrow
        val regularEpisodeRows = !short && !narrow && (twoPanes || maxWidth >= 600.dp)
        val shelf = !twoPanes && item.kind == ItemKind.EPISODE && hasEpisodeSection
        val rows = remember(episodeIds, hasEpisodeSection, related.isNotEmpty(), item.people.isNotEmpty(), shelf) {
            detailRows(hasEpisodeSection, episodeIds, related.isNotEmpty(), item.people.isNotEmpty(), shelf)
        }
        val primaryKeys = if (twoPanes) rows.contents else rows.stacked
        val primary = rememberDetailList(primaryKeys, selected?.id) {
            bookmark.value.primaryAnchor(twoPanes, currentEpisode)
        }
        val siblings = if (shelf) {
            rememberDetailList(episodeIds) { bookmark.value.siblingAnchor(currentEpisode ?: item.id) }
        } else null
        TrackDetailScroll(primary) { anchor ->
            val shelfPosition = siblings?.takeIf { anchor.key == SeasonKey }?.visibleDetailAnchor()
            bookmark.value = bookmark.value.record(anchor, shelfPosition)
        }
        if (siblings != null) {
            TrackDetailScroll(siblings) { anchor -> bookmark.value = bookmark.value.recordSibling(anchor) }
        }

        val renderRow: @Composable (String) -> Unit = { key ->
            when (key) {
                HeroKey -> DetailHeader(
                    state, playback, item, short, horizontal, overview, expanded, onToggleOverview,
                    drawArtwork = heroOverlap == 0.dp
                )
                PeopleKey -> PeopleRow(state, item, peopleStrip, compact, horizontal)
                SeasonKey -> if (siblings != null) {
                    val cardWidth = (maxWidth - horizontal * 2).coerceIn(48.dp, if (compact) 148.dp else 232.dp)
                    SiblingEpisodes(state, playback, item, episodes, siblings, cardWidth, horizontal)
                } else {
                    DetailSeasonControls(state, item, seasons, selected, seasonStrip, episodes.size, loading, error, horizontal) { id ->
                        if (id != selected?.id) bookmark.value = bookmark.value.changeSeason(twoPanes)
                        onSeason(id)
                    }
                }
                RelatedKey -> MediaRow(
                    "相关影片", related, itemWidth = if (compact) 116.dp else 152.dp,
                    menu = cardMenu(state, playback), onItemPlay = { playback.play(it) }
                ) { state.navigate(Screen.Detail(it.id)) }
                FileKey -> FileInfoSection(item, horizontal)
                else -> episodesById[key.episodeId()]?.let { episode ->
                    EpisodeRow(
                        episode,
                        basePath = selected?.path ?: item.path.takeIf { item.kind == ItemKind.SEASON },
                        isNext = episode.id == state.detailNextUp?.id,
                        isCurrent = episode.id == item.id,
                        roomy = regularEpisodeRows,
                        modifier = Modifier.padding(horizontal = horizontal, vertical = ListItemDefaults.SegmentedGap / 2),
                        index = episodeIndices.getValue(episode.id),
                        count = episodeIds.size,
                        download = state.downloadOf(episode.id),
                        onPlay = { playback.play(episode) },
                        onOpen = { state.navigate(Screen.Detail(episode.id)) },
                        menu = { dismiss -> ItemMenuItems(state, playback, episode, dismiss) }
                    ) { state.togglePlayed(episode) }
                }
            }
        }

        val heroArtwork: @Composable BoxScope.() -> Unit = { DetailHeroArtwork(item, short, heroOverlap) }
        Row(Modifier.readingWidth(if (twoPanes) SplitReadingWidth else SingleReadingWidth).fillMaxHeight()) {
            if (twoPanes) {
                val summary = rememberDetailList(rows.summary) { bookmark.value.summary }
                TrackDetailScroll(summary) { anchor -> bookmark.value = bookmark.value.record(anchor) }
                DetailColumn(summary, rows.summary, Modifier.weight(0.46f), "媒体简介", renderRow, heroOverlap, heroArtwork)
                Spacer(Modifier.width(24.dp))
            }
            DetailColumn(
                primary, primaryKeys, Modifier.weight(0.54f),
                pane = if (!twoPanes) null else if (hasEpisodeSection) "剧集与文件信息" else "相关影片与文件信息",
                renderRow = renderRow,
                heroOverlap = if (twoPanes) 0.dp else heroOverlap,
                heroArtwork = heroArtwork
            )
        }
    }
}

private fun detailMinHeight(extraText: Float) = (500f + 80f * extraText).dp

internal fun detailUsesTwoPanes(
    width: Dp,
    height: Dp,
    fontScale: Float,
    hasBrowseCompanion: Boolean,
    episodeCount: Int,
    hasSeasonChoices: Boolean,
    relatedCount: Int
): Boolean {
    val extraText = (fontScale - 1f).coerceAtLeast(0f)
    return !hasBrowseCompanion && (episodeCount >= 2 || hasSeasonChoices || relatedCount >= 2) &&
        width.coerceAtMost(SplitReadingWidth) >= (880f + 220f * extraText).dp && height >= detailMinHeight(extraText)
}

internal data class DetailRows(val summary: List<String>, val contents: List<String>) {
    val stacked: List<String> = summary.take(1) + contents.dropLast(1) + summary.drop(1) + FileKey
}

internal fun detailRows(
    hasEpisodes: Boolean, episodeIds: List<String>, related: Boolean, people: Boolean, shelf: Boolean = false
): DetailRows = DetailRows(
    summary = if (people) listOf(HeroKey, PeopleKey) else listOf(HeroKey),
    contents = buildList {
        if (hasEpisodes) {
            add(SeasonKey)
            if (!shelf) episodeIds.forEach { add(episodeKey(it)) }
        }
        if (related) add(RelatedKey)
        add(FileKey)
    }
)

internal data class DetailAnchor(val key: String, val offset: Int = 0)

internal data class DetailBookmark(
    val summary: DetailAnchor = DetailAnchor(HeroKey),
    val contents: DetailAnchor? = null,
    val sibling: DetailAnchor? = null,
    val readingContents: Boolean = false
) {
    fun primaryAnchor(split: Boolean, currentEpisode: String?): DetailAnchor? {
        if (!split) return if (readingContents) contents else summary
        // A shelf's horizontal offset is meaningless to a vertical list.
        if (contents?.key == SeasonKey && currentEpisode != null && sibling != null) {
            return DetailAnchor(episodeKey(sibling.key))
        }
        return contents ?: currentEpisode?.let { DetailAnchor(episodeKey(it)) }
    }

    fun siblingAnchor(currentEpisode: String): DetailAnchor {
        val episode = contents?.key?.episodeId() ?: sibling?.key ?: currentEpisode
        return sibling?.takeIf { it.key == episode } ?: DetailAnchor(episode)
    }

    fun record(anchor: DetailAnchor, shelfPosition: DetailAnchor? = null): DetailBookmark = when (anchor.key) {
        HeroKey, PeopleKey -> copy(summary = anchor, readingContents = false)
        else -> copy(contents = anchor, sibling = shelfPosition ?: sibling, readingContents = true)
    }

    fun recordSibling(anchor: DetailAnchor) = copy(
        contents = contents?.takeIf { it.key == SeasonKey } ?: DetailAnchor(SeasonKey),
        sibling = anchor,
        readingContents = true
    )

    fun changeSeason(split: Boolean) = copy(contents = DetailAnchor(SeasonKey), readingContents = split || readingContents)
}

private val detailBookmarkSaver = listSaver<DetailBookmark, Any>(
    save = { bookmark ->
        listOf(bookmark.summary, bookmark.contents, bookmark.sibling).flatMap {
            listOf(it?.key.orEmpty(), it?.offset ?: 0)
        } + bookmark.readingContents
    },
    restore = { saved ->
        fun anchor(index: Int) = (saved[index] as String).takeIf { it.isNotEmpty() }
            ?.let { DetailAnchor(it, saved[index + 1] as Int) }
        DetailBookmark(anchor(0) ?: DetailAnchor(HeroKey), anchor(2), anchor(4), saved[6] as Boolean)
    }
)

/** Resolve a missing episode to its section without changing the saved key. */
internal fun resolveDetailAnchor(keys: List<String>, wanted: DetailAnchor?): Pair<Int, Int> {
    val exact = keys.indexOf(wanted?.key)
    if (exact >= 0 && wanted != null) return exact to wanted.offset
    val section = if (wanted?.key?.episodeId() != null) keys.indexOf(SeasonKey) else -1
    return section.coerceAtLeast(0) to 0
}

@Composable
private fun rememberDetailList(keys: List<String>, seasonId: String? = null, anchor: () -> DetailAnchor?) =
    remember(keys, seasonId) {
        // Read the bookmark only when the list's shape changes, not at every scroll pixel.
        val (index, offset) = resolveDetailAnchor(keys, anchor())
        LazyListState(index, offset)
    }

private fun LazyListState.visibleDetailAnchor(): DetailAnchor? {
    val index = firstVisibleItemIndex
    val key = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String
    return key?.let { DetailAnchor(it, firstVisibleItemScrollOffset) }
}

@Composable
private fun TrackDetailScroll(list: LazyListState, onScroll: (DetailAnchor) -> Unit) {
    val record by rememberUpdatedState(onScroll)
    LaunchedEffect(list) {
        var previous: DetailAnchor? = null
        var wasScrolling = false
        snapshotFlow { list.visibleDetailAnchor() to list.isScrollInProgress }.collect { (visible, scrolling) ->
            // Reflow or an empty/loading fallback can clamp the measured list. It is not
            // a read position the user chose, so don't save it over a pending bookmark.
            if (visible != null && visible != previous && (scrolling || wasScrolling)) record(visible)
            previous = visible
            wasScrolling = scrolling
        }
    }
}

private fun Modifier.readingWidth(max: Dp) = fillMaxWidth().wrapContentWidth(Alignment.Start).widthIn(max = max)

@Composable
private fun DetailColumn(
    state: LazyListState,
    rows: List<String>,
    modifier: Modifier,
    pane: String?,
    renderRow: @Composable (String) -> Unit,
    heroOverlap: Dp,
    heroArtwork: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier.fillMaxHeight().semantics {
            if (pane != null) {
                paneTitle = pane
                isTraversalGroup = true
            }
        }
    ) {
        if (heroOverlap > 0.dp) {
            BrowseHeroBackdrop(state, HeroKey, heroOverlap, Modifier.matchParentSize().zIndex(-1f), heroArtwork)
        }
        LazyColumn(Modifier.fillMaxSize().clipToBounds(), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(rows, key = { it }, contentType = { if (it.episodeId() == null) it else EpisodeKeyPrefix }) { renderRow(it) }
        }
        VerticalScrollbarFor(state, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
    }
}

@Composable
private fun DetailHeading(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.titleLargeEmphasized)
}

private fun episodeSectionTitle(item: MediaItemDto): String = when {
    item.kind != ItemKind.EPISODE -> "剧集"
    item.parentIndexNumber == 0 -> "特别篇"
    item.parentIndexNumber != null -> "第 ${item.parentIndexNumber} 季"
    else -> "本季剧集"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailSeasonControls(
    state: AppState,
    item: MediaItemDto,
    seasons: List<MediaItemDto>,
    selected: MediaItemDto?,
    strip: LazyListState,
    episodeCount: Int,
    loading: Boolean,
    error: String?,
    horizontal: Dp,
    onSeason: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = horizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            DetailHeading(episodeSectionTitle(item))
            if (!loading && error == null && episodeCount > 0) {
                Text("$episodeCount 集", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (seasons.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val buttonWidth = (maxWidth - horizontal * 2).coerceIn(48.dp, 280.dp)
                val colors = MaterialTheme.colorScheme
                LazyRow(
                    state = strip,
                    modifier = Modifier.wheelScrollsHorizontally(strip).selectableGroup(),
                    contentPadding = PaddingValues(horizontal = horizontal),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(seasons, key = { it.id }) { season ->
                        val total = season.episodeCount ?: 0
                        val checked = selected?.id == season.id
                        ToggleButton(
                            checked = checked,
                            onCheckedChange = { onSeason(season.id) },
                            colors = ToggleButtonDefaults.toggleButtonColors(
                                containerColor = colors.surfaceContainerLow,
                                contentColor = colors.onSurfaceVariant,
                                checkedContainerColor = colors.secondaryContainer,
                                checkedContentColor = colors.onSecondaryContainer
                            ),
                            modifier = Modifier.widthIn(max = buttonWidth).semantics {
                                if (total > 0) stateDescription = watchedLabel(season)
                            }
                        ) {
                            if (checked) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                                Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                            }
                            Text(
                                season.name,
                                style = if (checked) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            selected?.let { SeasonActions(state, it, horizontal) }
        }
        when {
            loading -> Column(Modifier.padding(start = horizontal, top = 8.dp, end = horizontal)) {
                Text("正在加载剧集…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
            error != null -> Column(Modifier.padding(start = horizontal, top = 8.dp, end = horizontal)) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                selected?.id?.let { id -> TextButton(onClick = { onSeason(id) }) { Text("重试") } }
            }
            episodeCount == 0 -> Text(
                "暂无剧集", Modifier.padding(start = horizontal, top = 8.dp, end = horizontal),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** 「整季标记」 for the season on screen, which asks before changing a whole season. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonActions(state: AppState, season: MediaItemDto, horizontal: Dp) {
    var confirm by remember(season.id) { mutableStateOf(false) }
    val played = season.playedState == PlayedState.PLAYED
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = horizontal, top = 8.dp, end = horizontal),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
            Text(watchedLabel(season), style = MaterialTheme.typography.labelLarge)
            season.path?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "目录：$it", Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SeasonDownloadButton(state, season)
                TextButton(onClick = { confirm = true }) {
                    Text(if (played) "整季标记未看" else "整季标记已看")
                }
            }
        }
    }
    if (confirm) {
        ConfirmMarkDialog(season, onConfirm = { state.togglePlayed(season) }, onDismiss = { confirm = false })
    }
}

/**
 * Asks before marking a whole season or series. Marking clears where each
 * episode had got to, and one tap used to do that to every episode at once.
 */
@Composable
fun ConfirmMarkDialog(item: MediaItemDto, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalMarker()
    val played = item.playedState == PlayedState.PLAYED
    val count = item.episodeCount ?: 0
    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (played) "把 $count 集都标记为未观看？" else "把 $count 集都标记为已观看？") },
        text = {
            Text(
                "「${item.name}」下的每一集都会改，已有的播放进度会被清除。" +
                    "改完后提示条上可以撤销。"
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(if (played) "全部标记未看" else "全部标记已看") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * Where the metadata came from, and how much it should be trusted. A fallback
 * match is the one worth acting on: it means no source matched confidently and
 * the least-bad candidate was taken.
 */
private fun scrapeLabel(item: MediaItemDto): String {
    val ids = item.providerIds.entries
        .filter { it.key != "imdb" }
        .joinToString(" · ") { "${it.key} ${it.value}" }
        .ifBlank { "无来源 id" }
    val base = when (item.scrapeStatus) {
        ScrapeStatus.MANUAL -> if (item.lockedProvider != null) "手动指定 · $ids" else "手动编辑 · $ids"
        ScrapeStatus.MATCHED -> "自动匹配 · $ids"
        ScrapeStatus.FALLBACK -> "次级来源顶替（未可靠匹配，建议核对） · $ids"
        ScrapeStatus.UNMATCHED -> "所有来源都没有匹配"
        ScrapeStatus.NONE -> if (item.scrapedAt != null) "已刮削 · $ids" else "尚未刮削"
    }
    return if (item.manualFields.isNotEmpty()) "$base · ${item.manualFields.size} 个字段为手动填写" else base
}

/**
 * One line of watched state for any kind of item: a film or episode says where
 * it stopped, a series or season how much of it is done.
 */
private fun watchedLabel(item: MediaItemDto): String = when {
    item.isPlayable -> when {
        item.userData.played -> "已看完"
        item.userData.positionMs > 0 -> "看到 ${formatDuration(item.userData.positionMs)}"
        else -> "未观看"
    }
    (item.episodeCount ?: 0) == 0 -> "未观看"
    item.playedEpisodeCount == item.episodeCount -> "已看完 ${item.episodeCount} 集"
    else -> "已看 ${item.playedEpisodeCount ?: 0} / ${item.episodeCount} 集"
}

/** Where a provider shows an id, so the ids on the page can be opened there. */
private fun providerUrl(key: String, id: String, item: MediaItemDto): String? = when (key.lowercase()) {
    "tmdb" -> "https://www.themoviedb.org/${if (item.kind == ItemKind.MOVIE) "movie" else "tv"}/$id"
    "tvdb" -> "https://thetvdb.com/dereferrer/${if (item.kind == ItemKind.MOVIE) "movie" else "series"}/$id"
    "bangumi" -> "https://bgm.tv/subject/$id"
    "imdb" -> "https://www.imdb.com/title/$id"
    else -> null
}

@Composable
private fun DetailHeader(
    state: AppState,
    playback: PlaybackController,
    item: MediaItemDto,
    short: Boolean,
    horizontal: Dp,
    overview: String?,
    expanded: Boolean,
    onToggleOverview: () -> Unit,
    drawArtwork: Boolean
) {
    val openSeries: (() -> Unit)? = item.seriesId?.let { id -> { state.navigate(Screen.Detail(id)) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (drawArtwork) DetailHeroArtwork(item, short, overlap = 0.dp)
        val fontScale = LocalDensity.current.fontScale
        val tight = maxWidth < (300f + 140f * (fontScale - 1f).coerceAtLeast(0f)).dp
        val wideHero = maxWidth >= 600.dp && fontScale <= 1.4f && !short
        val showPoster = maxWidth >= 220.dp && !(tight && fontScale > 1.4f)
        val posterWidth = when {
            wideHero -> 160.dp
            short -> 64.dp
            tight -> 76.dp
            else -> 96.dp
        }
        val hasArt = item.backdropUrl != null || item.posterUrl != null
        val inset = Modifier.padding(
            start = horizontal,
            top = detailArtworkHeadroom(maxWidth, short, hasArt),
            end = horizontal,
            bottom = 24.dp
        )
        val title: @Composable () -> Unit = { HeroTitle(item, openSeries, wideHero, tight || short) }
        val controls: @Composable () -> Unit = {
            PlayActions(state, playback, item, compact = tight || short)
            HeroFacts(state, item)
        }
        Column(inset, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (wideHero) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeroPoster(item, posterWidth)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        title()
                        controls()
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showPoster) HeroPoster(item, posterWidth)
                    Column(Modifier.weight(1f)) { title() }
                }
                controls()
            }
            HeroBlurb(state, item, overview, expanded, onToggleOverview, short)
        }
    }
}

private fun detailArtworkHeadroom(width: Dp, short: Boolean, hasArtwork: Boolean): Dp = when {
    !hasArtwork || short -> 8.dp
    width >= 600.dp -> 64.dp
    else -> 28.dp
}

@Composable
private fun BoxScope.DetailHeroArtwork(item: MediaItemDto, short: Boolean, overlap: Dp) {
    val art = item.backdropUrl ?: item.posterUrl ?: return
    // Match the scroll row, but draw the image in a shallow band. Even a very long
    // title or expanded synopsis cannot stretch the image or move the scrim over text.
    BoxWithConstraints(Modifier.matchParentSize()) {
        val surface = MaterialTheme.colorScheme.surface
        val titleStart = overlap + detailArtworkHeadroom(maxWidth, short, hasArtwork = true)
        val height = titleStart + if (short) 88.dp else 148.dp
        val imageStop = (titleStart / 2 / height).coerceIn(0f, 1f)
        val textStop = (titleStart / height).coerceIn(imageStop, 1f)
        Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
            AsyncImage(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to surface.copy(alpha = 0.52f),
                        imageStop to surface.copy(alpha = 0.36f),
                        textStop to surface.copy(alpha = 0.94f),
                        1f to surface
                    )
                )
            )
        }
    }
}

@Composable
private fun HeroPoster(item: MediaItemDto, width: Dp) {
    Surface(
        modifier = Modifier.width(width).aspectRatio(2f / 3f),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        if (item.posterUrl == null) ArtworkPlaceholder(item.kind, Modifier.fillMaxSize())
        else AsyncImage(item.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** The series link and full title remain readable independently of the poster. */
@Composable
private fun HeroTitle(item: MediaItemDto, openSeries: (() -> Unit)?, wide: Boolean, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val type = when (item.kind) {
            ItemKind.MOVIE -> "电影"
            ItemKind.SERIES -> "剧集"
            ItemKind.SEASON -> "季"
            ItemKind.EPISODE -> "分集"
        }
        val seriesName = item.seriesName
        if (seriesName != null) {
            LinkText(
                seriesName, style = MaterialTheme.typography.labelLarge,
                color = if (openSeries != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = openSeries
            )
        } else {
            Text(type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            item.name, Modifier.semantics { heading() },
            style = when {
                wide -> MaterialTheme.typography.displaySmallEmphasized
                compact -> MaterialTheme.typography.headlineSmallEmphasized
                else -> MaterialTheme.typography.headlineMediumEmphasized
            }
        )
        item.originalName?.takeIf { it.isNotBlank() && it != item.name }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroFacts(state: AppState, item: MediaItemDto) {
    val colors = MaterialTheme.colorScheme
    val facts = listOfNotNull(
        item.episodeLabel,
        formatDate(item.premiereDate) ?: item.year?.let { "$it 年" },
        item.officialRating?.takeIf { it.isNotBlank() },
        formatRuntime(item.runtimeMs),
        item.childCount?.takeIf { item.kind == ItemKind.SERIES }?.let { "$it 季" }
    )
    val download = state.downloadOf(item.id)
    val localCopy = when {
        item.isPlayable && download?.state == DownloadState.DONE ->
            if (download.subtitleCount > 0) "已下载到本机 · 含 ${download.subtitleCount} 条字幕" else "已下载到本机"
        item.isPlayable && download?.active == true ->
            if (download.state == DownloadState.RUNNING) "正在下载 ${(download.fraction * 100).toInt()}%"
            else download.note ?: "等待下载"
        !item.isPlayable -> state.downloadsUnder(item).count { it.state == DownloadState.DONE }
            .takeIf { it > 0 }?.let { "已下载 $it/${item.episodeCount ?: it} 集" }
        else -> null
    }
    val downloaded = !item.isPlayable || download?.state == DownloadState.DONE
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (facts.isNotEmpty()) {
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        // Quiet metadata is text, not a wall of controls. Only noteworthy statuses get contained labels.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            Text(watchedLabel(item), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            item.communityRating?.let {
                val source = item.communityRatingSource?.displayName ?: "评分"
                DetailStatus("$source ${(it * 10).toInt() / 10.0}")
            }
            localCopy?.let {
                if (downloaded) DetailStatus(it, special = true)
                else Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailStatus(text: String, special: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (special) colors.tertiaryContainer else colors.surfaceContainerHigh,
        contentColor = if (special) colors.onTertiaryContainer else colors.onSurface,
        shape = MaterialTheme.shapes.small
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMediumEmphasized)
    }
}

/** Genres and synopsis — the part that reads best across the full width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroBlurb(
    state: AppState, item: MediaItemDto, overview: String?, expanded: Boolean, onToggle: () -> Unit, short: Boolean
) {
    if (item.genres.isEmpty() && overview == null) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailHeading(if (overview != null) "简介" else "类型")
            if (overview != null) {
                var overflowing by remember(overview) { mutableStateOf(false) }
                SelectionContainer {
                    Text(
                        overview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else if (short) 3 else 5,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                        modifier = Modifier.widthIn(max = 600.dp)
                    )
                }
                if (overflowing || expanded) {
                    TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(if (expanded) "收起" else "展开全文")
                    }
                }
            }
            if (item.genres.isNotEmpty()) {
                // Genres open search; they do not toggle a filter within this page.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.genres.forEach { genre ->
                        AssistChip(onClick = { state.searchFor(genre) }, label = { Text(genre) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayActions(state: AppState, playback: PlaybackController, item: MediaItemDto, compact: Boolean) {
    // Continue the actual next-up episode, independent of the selected season.
    val target = if (item.isPlayable) item else state.detailNextUp
    var playerMenu by remember { mutableStateOf(false) }
    var confirmMark by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (target != null) {
            val buttonHeight = if (compact) ButtonDefaults.MinHeight else ButtonDefaults.MediumContainerHeight
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { playback.play(target) },
                    modifier = Modifier.heightIn(min = buttonHeight),
                    shapes = ButtonDefaults.shapesFor(buttonHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight, hasStartIcon = true)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)))
                    Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(buttonHeight)))
                    val episode = target.episodeLabel?.takeIf { !item.isPlayable }
                    val label = if (target.userData.positionMs > 0) {
                        listOfNotNull("继续", episode, formatDuration(target.userData.positionMs)).joinToString(" ")
                    } else listOfNotNull("播放", episode).joinToString(" ")
                    Text(
                        label,
                        style = if (compact) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.titleMediumEmphasized
                    )
                }
                // Starting again remains one tap, separate from the primary resume affordance.
                if (target.userData.positionMs > 0) {
                    OutlinedButton(
                        onClick = { playback.play(target, startPositionMs = 0L) },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.SmallContentPadding
                    ) {
                        Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(ButtonDefaults.SmallIconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("从头播放")
                    }
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            val favoriteTip = if (item.userData.favorite) "取消收藏" else "收藏"
            Tooltip(favoriteTip) {
                FilledTonalIconToggleButton(
                    checked = item.userData.favorite,
                    onCheckedChange = { state.toggleFavorite(item) },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                        containerColor = colors.surfaceContainerLow,
                        contentColor = colors.onSurfaceVariant,
                        checkedContainerColor = colors.secondaryContainer,
                        checkedContentColor = colors.onSecondaryContainer
                    )
                ) {
                    Icon(
                        if (item.userData.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = favoriteTip
                    )
                }
            }
            // Marking a season/series changes all its episodes, so it still asks first.
            if (item.kind != ItemKind.SEASON || (item.episodeCount ?: 0) > 0) {
                val playedTip = if (item.playedState == PlayedState.PLAYED) "标记为未观看" else "标记为已观看"
                TipIconButton(playedTip, onClick = {
                    if (item.isPlayable) state.togglePlayed(item) else confirmMark = true
                }) { WatchedIcon(item.playedState, playedTip) }
            }
            DownloadAction(state, item)
            if (target != null && playback.externalPlayers.isNotEmpty()) {
                Box {
                    TipIconButton("外部播放器", onClick = { playerMenu = true }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = "外部播放器")
                    }
                    AppMenu(expanded = playerMenu, onDismissRequest = { playerMenu = false }) {
                        playback.externalPlayers.forEach { player ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(player.label)
                                        player.executablePath?.let {
                                            Text(
                                                it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    playerMenu = false
                                    playback.playExternal(target, player)
                                }
                            )
                        }
                    }
                }
            }
            DetailMoreMenu(state, item)
        }

        // On a series page what played is its episode, whose failure is filed
        // under the episode's id.
        (playback.errorFor(item.id) ?: target?.let { playback.errorFor(it.id) })?.let {
            Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall)
        }
        target?.takeIf { it.isPlayable }?.let { TrackPickers(state, it) }
    }

    if (confirmMark) {
        ConfirmMarkDialog(item, onConfirm = { state.togglePlayed(item) }, onDismiss = { confirmMark = false })
    }
}

/** Filled tick for watched, a partly drawn ring for started, an empty ring for neither. */
@Composable
private fun WatchedIcon(played: PlayedState, description: String) {
    when (played) {
        PlayedState.PLAYED -> Icon(Icons.Filled.CheckCircle, contentDescription = description, tint = MaterialTheme.colorScheme.primary)
        PlayedState.PARTIAL -> Icon(Icons.Filled.Timelapse, contentDescription = description, tint = MaterialTheme.colorScheme.primary)
        PlayedState.NONE -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One word on an episode row for where its copy has got to, or nothing. */
private fun downloadLabel(download: DownloadDto?): String? = when (download?.state) {
    DownloadState.DONE -> "已下载"
    DownloadState.RUNNING -> "下载中 ${(download.fraction * 100).toInt()}%"
    DownloadState.QUEUED -> download.note ?: "等待下载"
    DownloadState.FAILED -> "下载失败"
    null -> null
}

/**
 * The download button. What it says and does follows where the copy has got
 * to: a film or an episode is one file, fetched, cancelled or deleted; a series
 * or a season is its episodes, which are queued, cancelled and deleted as one.
 */
@Composable
private fun DownloadAction(state: AppState, item: MediaItemDto) {
    if (item.isPlayable) {
        val download = state.downloadOf(item.id)
        val (tip, icon, tint) = when (download?.state) {
            DownloadState.DONE -> Triple("删除本地文件…", Icons.Filled.DownloadDone, MaterialTheme.colorScheme.tertiary)
            DownloadState.RUNNING -> Triple(
                "取消下载（${(download.fraction * 100).toInt()}%）", Icons.Filled.Downloading, MaterialTheme.colorScheme.primary
            )
            DownloadState.QUEUED -> Triple(
                "取消下载（${download.note ?: "排队中"}）", Icons.Filled.Downloading, MaterialTheme.colorScheme.primary
            )
            DownloadState.FAILED -> Triple(
                "重新下载（上次失败：${download.error ?: "未知原因"}）", Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error
            )
            null -> Triple("下载到本机（连同外挂字幕）", Icons.Filled.Download, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TipIconButton(tip, onClick = {
            when (download?.state) {
                // Deleting a finished copy asks first: the bytes took minutes to arrive.
                DownloadState.DONE -> state.confirm(
                    Confirmation(
                        title = "删除「${item.name}」的本地文件？",
                        text = ("删除后要再联网播放或重新下载。" + formatSize(download.totalBytes)).trim(),
                        confirmLabel = "删除",
                        destructive = true,
                        action = { state.removeDownload(item.id) }
                    )
                )
                DownloadState.RUNNING, DownloadState.QUEUED ->
                    state.stopDownloads("停止下载「${item.name}」？", listOf(download)) { state.cancelDownload(item) }
                else -> state.download(item)
            }
        }) {
            Icon(icon, contentDescription = tip, tint = tint)
        }
        return
    }

    val total = item.episodeCount ?: 0
    if (total == 0) return
    val under = state.downloadsUnder(item)
    val pending = under.count { it.active }
    val done = under.count { it.state == DownloadState.DONE }
    val scope = if (item.kind == ItemKind.SEASON) "本季" else "全部"
    when {
        pending > 0 -> TipIconButton("取消下载（$pending 集）", onClick = {
            state.stopDownloads("停止下载「${item.name}」的 $pending 集？", under.filter { it.active }) {
                state.cancelDownloadsUnder(item)
            }
        }) {
            Icon(Icons.Filled.Downloading, contentDescription = "取消下载", tint = MaterialTheme.colorScheme.primary)
        }
        done >= total -> TipIconButton("删除本地文件（$done 集）…", onClick = {
            state.confirm(
                Confirmation(
                    title = "删除「${item.name}」的 $done 个本地文件？",
                    text = ("删除后要再联网播放或重新下载。" +
                        formatSize(under.filter { it.state == DownloadState.DONE }.sumOf { it.totalBytes })).trim(),
                    confirmLabel = "删除",
                    destructive = true,
                    action = { state.removeDownloadsUnder(item) }
                )
            )
        }) {
            Icon(Icons.Filled.DownloadDone, contentDescription = "删除本地文件", tint = MaterialTheme.colorScheme.tertiary)
        }
        else -> TipIconButton("下载$scope（$total 集）…", onClick = { state.download(item) }) {
            Icon(Icons.Filled.Download, contentDescription = "下载$scope", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The season's own download control, beside its watched toggle. */
@Composable
private fun SeasonDownloadButton(state: AppState, season: MediaItemDto) {
    val under = state.downloadsUnder(season)
    val pending = under.count { it.active }
    val done = under.count { it.state == DownloadState.DONE }
    val total = season.episodeCount ?: 0
    when {
        pending > 0 -> TextButton(onClick = {
            state.stopDownloads("停止下载「${season.name}」的 $pending 集？", under.filter { it.active }) {
                state.cancelDownloadsUnder(season)
            }
        }) { Text("取消下载（$pending 集）") }
        total > 0 && done >= total -> TextButton(onClick = {
            state.confirm(
                Confirmation(
                    title = "删除「${season.name}」的 $done 个本地文件？",
                    text = ("删除后要再联网播放或重新下载。" +
                        formatSize(under.filter { it.state == DownloadState.DONE }.sumOf { it.totalBytes })).trim(),
                    confirmLabel = "删除",
                    destructive = true,
                    action = { state.removeDownloadsUnder(season) }
                )
            )
        }) { Text("删除本季文件…") }
        else -> TextButton(onClick = { state.download(season) }) {
            Text(if (done > 0) "下载本季（还差 ${total - done} 集）…" else "下载本季…")
        }
    }
}

/**
 * The rarely needed things — fixing the metadata, merging a duplicate, keeping
 * a copy — kept in one menu. They sat as a row of look-alike pencil icons at
 * the same weight as play and favourite.
 */
@Composable
private fun DetailMoreMenu(state: AppState, item: MediaItemDto) {
    var open by remember { mutableStateOf(false) }
    var identifyOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var mergeOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val whole = item.kind == ItemKind.MOVIE || item.kind == ItemKind.SERIES

    if (identifyOpen) IdentifyDialog(state, item, onDismiss = { identifyOpen = false })
    if (editOpen) EditItemDialog(state, item, onDismiss = { editOpen = false })
    if (mergeOpen) MergeDialog(state, item, onDismiss = { mergeOpen = false })

    Box {
        TipIconButton("更多", onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            if (whole) {
                DropdownMenuItem(text = { Text("编辑条目信息…") }, onClick = { open = false; editOpen = true })
                DropdownMenuItem(
                    text = { Text("更换海报…") },
                    onClick = {
                        open = false
                        scope.launch {
                            pickImageFile()?.let { state.setArtwork(item, "poster", it.bytes, it.extension) }
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("更换背景图…") },
                    onClick = {
                        open = false
                        scope.launch {
                            pickImageFile()?.let { state.setArtwork(item, "backdrop", it.bytes, it.extension) }
                        }
                    }
                )
                if (item.manualFields.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("恢复为刮削结果…") },
                        onClick = {
                            open = false
                            state.confirm(
                                Confirmation(
                                    title = "恢复「${item.name}」的刮削结果？",
                                    text = "手动改过的 ${item.manualFields.size} 个字段会被丢弃，换回刮削源的内容。",
                                    confirmLabel = "恢复",
                                    destructive = true,
                                    action = { state.revertManualEdits(item) }
                                )
                            )
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            if (item.lockedProvider != null) "更换手动指定的条目…（当前 ${item.lockedProvider?.displayName}）"
                            else "手动指定刮削条目…"
                        )
                    },
                    onClick = { open = false; identifyOpen = true }
                )
                if (item.lockedProvider != null) {
                    DropdownMenuItem(
                        text = { Text("解除手动指定，重新自动匹配…") },
                        onClick = {
                            open = false
                            state.confirm(
                                Confirmation(
                                    title = "解除「${item.name}」的手动指定？",
                                    text = "不再固定为 ${item.lockedProvider?.displayName} 上的这个条目，会立即重新自动匹配，" +
                                        "匹配到的可能是另一部作品。",
                                    confirmLabel = "解除",
                                    destructive = true,
                                    action = { state.unpin(item) }
                                )
                            )
                        }
                    )
                }
                DropdownMenuItem(text = { Text("重新刮削") }, onClick = { open = false; state.refreshMetadata(item) })
                DropdownMenuItem(text = { Text("合并重复条目…") }, onClick = { open = false; mergeOpen = true })
            }
            if (item.providerIds.isNotEmpty()) {
                HorizontalDivider()
                item.providerIds.forEach { (key, id) ->
                    providerUrl(key, id, item)?.let { url ->
                        DropdownMenuItem(
                            text = { Text("在 ${key.uppercase()} 上查看") },
                            onClick = { open = false; openUrl(url) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * The audio and subtitle to start on, chosen before pressing play. The file
 * details listed every track and offered no way to pick one; the choice could
 * only be made once the film was running.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackPickers(state: AppState, item: MediaItemDto) {
    val audio = item.mediaStreams.filter { it.type == StreamType.AUDIO }
    val subtitles = item.mediaStreams.filter { it.type == StreamType.SUBTITLE }
    if (audio.size < 2 && subtitles.isEmpty()) return
    val chosenAudio = item.userData.audioStreamIndex
    val chosenSubtitle = item.userData.subtitleStreamIndex
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (audio.size >= 2) {
            TrackPicker(
                label = "音轨",
                current = audio.firstOrNull { it.index == chosenAudio }?.displayTitle ?: "自动",
                options = listOf(AUTOMATIC to "自动（按首选语言）") + audio.map { it.index to it.displayTitle }
            ) { if (it == AUTOMATIC) state.resetTrackSelection(item, audio = true) else state.setTrackSelection(item, it, null) }
        }
        if (subtitles.isNotEmpty()) {
            TrackPicker(
                label = "字幕",
                current = when (chosenSubtitle) {
                    SUBTITLE_OFF -> "关闭"
                    null -> "自动"
                    else -> subtitles.firstOrNull { it.index == chosenSubtitle }?.displayTitle ?: "自动"
                },
                options = listOf(AUTOMATIC to "自动（按首选语言）") + subtitles.map { it.index to it.displayTitle } +
                    (SUBTITLE_OFF to "关闭字幕")
            ) { if (it == AUTOMATIC) state.resetTrackSelection(item, audio = false) else state.setTrackSelection(item, null, it) }
        }
    }
}

/** The pickers' "no choice of my own" entry; no stream has this index. */
private const val AUTOMATIC = Int.MIN_VALUE

@Composable
private fun TrackPicker(label: String, current: String, options: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, contentPadding = ButtonDefaults.SmallContentPadding) {
            Text("$label：$current", Modifier.weight(1f, fill = false).widthIn(max = 240.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(ButtonDefaults.SmallIconSize))
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (index, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(index) })
            }
        }
    }
}

/**
 * What the files themselves say, as opposed to what a scraper wrote. A series or
 * a season only knows the folder it was found in, so the block shrinks to
 * whatever that item actually carries.
 */
@Composable
private fun FileInfoSection(item: MediaItemDto, horizontal: Dp) {
    val video = item.mediaStreams.firstOrNull { it.type == StreamType.VIDEO }
    val audio = item.mediaStreams.filter { it.type == StreamType.AUDIO }
    val subtitles = item.mediaStreams.filter { it.type == StreamType.SUBTITLE }

    val rows = buildList<Pair<String, String>> {
        item.path?.takeIf { it.isNotBlank() }?.let {
            add((if (item.isPlayable) "文件" else "目录") to it)
        }
        formatSize(item.sizeBytes).takeIf { it.isNotBlank() }?.let { add("大小" to it) }
        video?.let { stream ->
            add(
                "画面" to listOfNotNull(
                    stream.codec?.uppercase() ?: "未知",
                    if (stream.width != null && stream.height != null) "${stream.width}×${stream.height}" else null
                ).joinToString(" · ")
            )
        }
        add("刮削" to scrapeLabel(item))
        if (audio.isNotEmpty()) add("音轨" to audio.joinToString("\n") { it.displayTitle })
        if (subtitles.isNotEmpty()) add("字幕" to subtitles.joinToString("\n") { it.displayTitle })
        subtitles.mapNotNull { it.externalPath }.takeIf { it.isNotEmpty() }?.let {
            add("外挂字幕" to it.joinToString("\n"))
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = horizontal, top = 24.dp, end = horizontal)) {
        DetailHeading("文件信息")
        Spacer(Modifier.height(12.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
                val verticalFields = maxWidth < 288.dp || LocalDensity.current.fontScale > 1.4f
                val unverified = item.scrapeStatus == ScrapeStatus.FALLBACK || item.scrapeStatus == ScrapeStatus.UNMATCHED
                // Paths and tracks stay selectable, including where they wrap.
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        rows.forEachIndexed { index, (label, value) ->
                            if (index > 0) HorizontalDivider()
                            InfoRow(label, value, verticalFields, special = label == "刮削" && unverified)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, vertical: Boolean, special: Boolean) {
    val field: @Composable (Modifier) -> Unit = { modifier ->
        Text(label, modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val valueColor = if (special) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            field(Modifier)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            field(Modifier.width(72.dp))
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
    }
}

/**
 * The season folder is already printed above the list, so an episode row only
 * repeats what sits below it. Falls back to the whole path when the episode is
 * not inside the folder its season was named after.
 */
private fun pathUnder(path: String, base: String?): String {
    if (base.isNullOrBlank()) return path
    return path.removePrefix(base.trimEnd('/') + "/")
}

@Composable
private fun SiblingEpisodes(
    state: AppState,
    playback: PlaybackController,
    item: MediaItemDto,
    episodes: List<MediaItemDto>,
    list: LazyListState,
    cardWidth: Dp,
    horizontal: Dp
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        DetailHeading(episodeSectionTitle(item), Modifier.padding(start = horizontal, end = horizontal, bottom = 12.dp))
        LazyRow(
            state = list,
            modifier = Modifier.wheelScrollsHorizontally(list),
            contentPadding = PaddingValues(horizontal = horizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(episodes, key = { it.id }) { episode ->
                Column(Modifier.width(cardWidth)) {
                    PosterCard(
                        episode,
                        width = cardWidth,
                        shape = CardShape.LANDSCAPE,
                        title = episode.episodeLabel ?: episode.name,
                        subtitle = if (episode.id == item.id) "当前剧集 · ${episode.name}" else episode.name,
                        downloaded = state.downloadOf(episode.id)?.state == DownloadState.DONE,
                        detailOpen = episode.id == item.id,
                        menu = { dismiss -> ItemMenuItems(state, playback, episode, dismiss) }
                    ) { state.navigate(Screen.Detail(episode.id)) }
                    val label = "播放 ${episode.episodeLabel ?: episode.name}"
                    TipIconButton(label, onClick = { playback.play(episode) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = label)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PeopleRow(state: AppState, item: MediaItemDto, list: LazyListState, compact: Boolean, horizontal: Dp) {
    Column(Modifier.padding(top = 16.dp)) {
        DetailHeading("演职人员", Modifier.padding(start = horizontal, end = horizontal, bottom = 12.dp))
        LazyRow(
            state = list,
            modifier = Modifier.wheelScrollsHorizontally(list),
            contentPadding = PaddingValues(horizontal = horizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Positional keys: a provider can list the same name and role
            // twice, and a duplicate key takes the whole page down.
            items(item.people) { person ->
                // A face leads to the other titles it appears in.
                Surface(
                    onClick = { state.searchFor(person.name) },
                    color = Color.Transparent,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.width(if (compact) 76.dp else 96.dp).padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.size(if (compact) 56.dp else 72.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            if (person.imageUrl == null) {
                                ArtworkPlaceholder(null, Modifier.fillMaxSize(), person = true)
                            }
                            person.imageUrl?.let {
                                AsyncImage(
                                    model = it,
                                    // The name is right below; saying it twice is noise.
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            person.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        person.role?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun EpisodeRow(
    episode: MediaItemDto,
    basePath: String?,
    isNext: Boolean,
    isCurrent: Boolean,
    roomy: Boolean,
    modifier: Modifier,
    index: Int,
    count: Int,
    download: DownloadDto?,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    onToggleWatched: () -> Unit
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var lastPointer by remember { mutableStateOf(PointerType.Unknown) }
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val highlighted = isCurrent || isNext
    val watchedTip = if (episode.userData.played) "标记为未观看" else "标记为已观看"
    val playLabel = "播放 ${episode.episodeLabel ?: episode.name}"
    // Anchor the menu at the same origin from which the pointer offset was reported.
    BoxWithConstraints(Modifier.fillMaxWidth().then(modifier)) {
        val showStill = roomy && maxWidth >= (400f + 120f * (density.fontScale - 1f).coerceAtLeast(0f)).dp
        val inlineWatched = maxWidth >= (272f + 112f * (density.fontScale - 1f).coerceAtLeast(0f)).dp
        val stacked = maxWidth < (196f + 96f * (density.fontScale - 1f).coerceAtLeast(0f)).dp
        val openMenu = { menuAt = Offset.Zero }
        val play: @Composable () -> Unit = {
            TipIconButton(playLabel, onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = playLabel, tint = colors.primary)
            }
        }
        val actions: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!showStill) play()
                if (inlineWatched || stacked) {
                    TipIconButton(watchedTip, onClick = onToggleWatched) { WatchedIcon(episode.playedState, watchedTip) }
                }
                TipIconButton("剧集操作", onClick = openMenu) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "剧集操作")
                }
            }
        }
        SegmentedListItem(
            onClick = if (isCurrent) onPlay else onOpen,
            onLongClick = { if (lastPointer != PointerType.Mouse) openMenu() },
            onLongClickLabel = "打开菜单",
            modifier = Modifier.fillMaxWidth()
                .secondaryClick(onPointerType = { lastPointer = it }, onOpen = { menuAt = it }),
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(
                containerColor = if (highlighted) colors.secondaryContainer else colors.surfaceContainerLow,
                contentColor = if (highlighted) colors.onSecondaryContainer else colors.onSurface
            ),
            leadingContent = if (showStill) ({ EpisodeStill(episode, onPlay, playLabel) }) else null,
            trailingContent = if (!stacked) actions else null,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                EpisodeInfo(episode, basePath, isNext, isCurrent, dense = !showStill, download = download)
                if (stacked) Box(Modifier.align(Alignment.End)) { actions() }
            }
        }
        AppMenu(expanded = menuAt != null, onDismissRequest = { menuAt = null }, offset = menuAt.toDpOffset(density)) {
            menu { menuAt = null }
        }
    }
}

@Composable
private fun EpisodeStill(episode: MediaItemDto, onPlay: () -> Unit, playLabel: String) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.width(114.dp).height(64.dp),
        shape = MaterialTheme.shapes.small,
        color = colors.surfaceContainerHigh
    ) {
        Box(contentAlignment = Alignment.Center) {
            val still = episode.posterUrl
            if (still == null) ArtworkPlaceholder(ItemKind.EPISODE, Modifier.fillMaxSize())
            else AsyncImage(still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Tooltip(playLabel) {
                FilledTonalIconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colors.surfaceContainerHigh,
                        contentColor = colors.onSurface
                    )
                ) { Icon(Icons.Filled.PlayArrow, contentDescription = playLabel) }
            }
            val progress = episode.userData.playedPercentage.toFloat()
            if (progress > 0.01f && !episode.userData.played) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 4.dp),
                    trackColor = colors.secondaryContainer
                )
            }
        }
    }
}

@Composable
private fun EpisodeInfo(
    episode: MediaItemDto,
    basePath: String?,
    isNext: Boolean,
    isCurrent: Boolean,
    dense: Boolean,
    download: DownloadDto?
) {
    if (isNext || isCurrent) {
        Text(
            if (isCurrent) "当前剧集" else "接下来",
            style = MaterialTheme.typography.labelMediumEmphasized,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
    Text(
        listOfNotNull(episode.episodeLabel, episode.name).joinToString(" · "),
        style = if (isCurrent || isNext) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium,
        maxLines = 2, overflow = TextOverflow.Ellipsis
    )
    if (!dense) episode.overview?.let {
        Text(
            it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        listOfNotNull(
            watchedLabel(episode), formatRuntime(episode.runtimeMs), downloadLabel(download),
            formatSize(episode.sizeBytes).takeIf { !dense && it.isNotBlank() },
            episode.mediaStreams.count { it.type == StreamType.SUBTITLE }.takeIf { !dense && it > 0 }?.let { "$it 条字幕" }
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!dense) episode.path?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(2.dp))
        Text(
            pathUnder(it, basePath), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
