package com.daview.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** `1:23:45` for anything over an hour, `12:34` otherwise — a clock, for playback positions. */
fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return "--:--"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "$hours:${pad(minutes)}:${pad(seconds)}" else "${minutes}:${pad(seconds)}"
}

/**
 * How long something runs, the way a catalogue says it: `1 小时 52 分`, `24 分钟`.
 * A clock face (`1:52:30`) read as a position in the film rather than a length.
 */
fun formatRuntime(ms: Long?): String? {
    if (ms == null || ms <= 0) return null
    val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(1)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$minutes 分钟"
        rest == 0L -> "$hours 小时"
        else -> "$hours 小时 $rest 分"
    }
}

fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "${((value * 10).toLong() / 10.0)} ${units[index]}"
}

private fun pad(value: Long) = value.toString().padStart(2, '0')

/**
 * An ISO date the way Chinese writes one: `2024 年 1 月 15 日`. Anything that
 * does not parse is shown as it came.
 */
fun formatDate(iso: String?): String? {
    val value = iso?.trim().orEmpty()
    if (value.isBlank()) return null
    val match = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(value) ?: return value
    val (year, month, day) = match.destructured
    return "$year 年 ${month.toInt()} 月 ${day.toInt()} 日"
}

/** "刚刚", "5 分钟前", "3 小时前", "2 天前" — for when something last happened. */
fun formatAgo(epochMs: Long?, now: Long = currentTimeMillis()): String? {
    if (epochMs == null || epochMs <= 0) return null
    val seconds = ((now - epochMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "刚刚"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86_400 -> "${seconds / 3600} 小时前"
        else -> "${seconds / 86_400} 天前"
    }
}

internal fun currentTimeMillis(): Long = System.currentTimeMillis()

/**
 * Holds the space while something is on its way, and only draws the indicator
 * if the wait outlasts a couple of frames.
 *
 * A library page comes back in about twenty milliseconds. A spinner shown and
 * withdrawn inside that reads as the content blinking out rather than as
 * feedback, and it is why moving between libraries felt slower than it was.
 * Anything genuinely slow still gets one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingPane(content: @Composable (() -> Unit)? = null) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(150)
        settled = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            content != null -> content()
            settled -> ContainedLoadingIndicator()
        }
    }
}

/**
 * What stands in for artwork that is missing or has not arrived.
 *
 * One shape for the whole app: the four places that draw a missing image each
 * had their own answer — a film reel for everything including television, a
 * bare grey rectangle for an episode still, an empty circle for a face — so
 * "there is no picture here" looked like three different things, one of which
 * looked like loading.
 */
@Composable
fun ArtworkPlaceholder(
    kind: ItemKind?,
    modifier: Modifier = Modifier,
    person: Boolean = false
) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when {
                person -> Icons.Filled.Person
                kind == ItemKind.SERIES || kind == ItemKind.SEASON -> Icons.Filled.Tv
                kind == ItemKind.EPISODE -> Icons.Filled.Slideshow
                else -> Icons.Filled.Movie
            },
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.32f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The bar at the top of a page: the way back when there is one, what the page
 * is, and whatever the page offers to do. Each page draws its own now; one bar
 * for the whole app held nothing but the back arrow, and on the pages you
 * cannot go back from it was an empty band across the top of the window.
 */
@Composable
fun ScreenTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(64.dp)
            .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Tooltip("返回（Alt + ←）") {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

/** An icon button that says what it does when the pointer rests on it. */
@Composable
fun TipIconButton(
    tip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Tooltip(tip) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    }
}

@Composable
fun SectionHeader(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f, fill = false)
        )
        trailing?.invoke()
    }
}

/** How a card is cut: posters stand, stills and backdrops lie down. */
enum class CardShape { AUTO, PORTRAIT, LANDSCAPE }

/**
 * Poster tile. Hovering lifts the card on desktop and web, which is the main
 * expressive motion cue in the browse grids.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: MediaItemDto,
    modifier: Modifier = Modifier,
    width: Dp = 152.dp,
    showProgress: Boolean = true,
    /**
     * The shape every card in a row shares. Deciding it per item put 2:3
     * posters and 16:9 stills side by side on one shelf, the same width and
     * half or twice the height.
     */
    shape: CardShape = CardShape.AUTO,
    /** Overrides the two lines under the tile. Both default to what the item says. */
    title: String? = null,
    subtitle: String? = null,
    /** Whether a copy of this item is kept on the device. */
    downloaded: Boolean = false,
    /**
     * In a multi-select, whether this card is picked; null when nothing is
     * being selected. Picking replaces opening, and the play button steps aside.
     */
    selected: Boolean? = null,
    /**
     * Entries for the menu a right-click or a long press opens, if this card has
     * one. The tile stays a pure presentation piece: it knows where the menu goes
     * and when to close it, not what is in it.
     */
    menu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
    /**
     * What the play button on the artwork does. Without it no play button is
     * drawn — a filled play triangle that opens a detail page is a promise the
     * tile cannot keep.
     */
    onPlay: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Keyboard and D-pad focus reuse the hover treatment. Without it the grid
    // scrolls itself as focus moves and nothing on screen says which tile it
    // landed on, which is WCAG 2.4.7 and the whole of TV navigation.
    val focused by interaction.collectIsFocusedAsState()
    val lifted = hovered || focused
    val scale by animateFloatAsState(if (lifted) 1.04f else 1f, label = "poster-scale")
    val landscape = when (shape) {
        CardShape.LANDSCAPE -> true
        CardShape.PORTRAIT -> false
        CardShape.AUTO -> item.kind == ItemKind.EPISODE
    }
    val aspect = if (landscape) 16f / 9f else 2f / 3f
    // A lying-down card of a film or a series is its backdrop: the poster
    // cropped to 16:9 is a strip across somebody's face.
    val art = if (landscape && item.kind != ItemKind.EPISODE) item.backdropUrl ?: item.posterUrl else item.posterUrl

    // Where the menu was asked for, in pixels from the tile's top-left corner.
    // Null means closed; a right-click carries the cursor, a long press has no
    // position to speak of and falls back to the corner.
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var lastPointer by remember { mutableStateOf(PointerType.Unknown) }
    val density = LocalDensity.current

    val progress = item.userData.playedPercentage.toFloat()
    val remaining = if (item.kind == ItemKind.SERIES || item.kind == ItemKind.SEASON) {
        val total = item.episodeCount ?: 0
        (total - (item.playedEpisodeCount ?: 0)).takeIf { total > 0 && it in 1 until total + 1 }
    } else null
    val watchState = when {
        item.playedState == com.daview.shared.model.PlayedState.PLAYED -> "已观看"
        remaining != null && remaining < (item.episodeCount ?: 0) -> "还剩 $remaining 集未看"
        progress > 0.01f -> "已看 ${(progress * 100).toInt()}%"
        else -> "未观看"
    }
    val primaryLine = title ?: item.seriesName?.takeIf { item.kind == ItemKind.EPISODE } ?: item.name

    Column(
        modifier = modifier
            .width(width)
            .hoverable(interaction)
            .then(
                if (menu == null) Modifier
                else Modifier.secondaryClick(
                    onPointerType = { lastPointer = it },
                    onOpen = { at -> menuAt = at }
                )
            )
            .combinedClickable(
                interactionSource = interaction,
                // The default ripple, not null. Suppressing it left the app's
                // main control with no press feedback at all on a touch screen,
                // where the hover treatment above can never fire.
                indication = ripple(),
                onClick = onClick,
                onLongClickLabel = if (menu == null) null else "打开菜单",
                // A held mouse button is not a request for the menu — it already
                // has the right button. Anything else, including the synthetic
                // long press a screen reader dispatches, is.
                onLongClick = if (menu == null) null else {
                    { if (lastPointer != PointerType.Mouse) menuAt = Offset.Zero }
                }
            )
            // One node for the whole tile: the name is already below it, so the
            // artwork is decorative, and the watch state is spoken here rather
            // than left to a progress bar that reads as a bare percentage.
            .semantics(mergeDescendants = true) {
                stateDescription = when (selected) {
                    true -> "已选中，$watchState"
                    false -> "未选中，$watchState"
                    null -> watchState
                }
            }
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect).scale(scale),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = if (lifted) 6.dp else 0.dp,
            border = when {
                selected == true || focused -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                else -> null
            }
        ) {
            Box {
                if (art != null) {
                    AsyncImage(
                        model = art,
                        // Decorative: the title is spelled out directly below,
                        // and reading it twice is what a screen reader did.
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ArtworkPlaceholder(item.kind, Modifier.fillMaxSize())
                }

                Row(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (downloaded) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Icon(
                                Icons.Filled.DownloadDone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(3.dp).size(16.dp)
                            )
                        }
                    }
                    when {
                        item.playedState == com.daview.shared.model.PlayedState.PLAYED -> {
                            // On its own circle rather than bare on the artwork: over a
                            // pale poster a tinted icon was down at 1.7:1.
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(3.dp).size(16.dp)
                                )
                            }
                        }
                        // How many episodes are left, the way a series poster
                        // says it in every other media library.
                        remaining != null -> Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                remaining.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                if (selected != null) {
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White),
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                            modifier = Modifier.padding(3.dp).size(18.dp)
                        )
                    }
                }

                PlayOverlay(visible = lifted && onPlay != null && selected == null) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                            )
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Its own click target, so the button that looks like
                        // play actually plays.
                        Surface(
                            onClick = { onPlay?.invoke() },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = if (item.isPlayable) "播放" else "播放下一集",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(8.dp).size(26.dp)
                            )
                        }
                    }
                }

                if (showProgress && progress > 0.01f && !item.userData.played) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Inset and rounded: the tile's own 26dp corners
                            // used to eat both ends of the bar, and a progress
                            // under about 8% was clipped away entirely.
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clip(CircleShape)
                            .fillMaxWidth()
                            .height(4.dp)
                            // Spoken by the tile as a whole; on its own this was
                            // a second, nameless stop that read out a number.
                            .clearAndSetSemantics { },
                        // The accent colour, as every other "you are here" mark
                        // in the app; the amber secondary flipped between light
                        // and dark roles in the two themes.
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                }

                // The anchor is the artwork, and a dropdown's offset is measured
                // from the anchor's bottom edge, so the artwork's own height has
                // to come back out for the menu to open under the cursor.
                if (menu != null) {
                    val artworkHeight = width / aspect
                    AppMenu(
                        expanded = menuAt != null,
                        onDismissRequest = { menuAt = null },
                        offset = menuAt.toDpOffset(density).let {
                            DpOffset(it.x, it.y - artworkHeight)
                        }
                    ) {
                        menu { menuAt = null }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // The whole title on hover: one line at poster width is four or five
        // characters of a Japanese title, and there was no way to read the rest.
        Tooltip(primaryLine) {
            Text(
                text = primaryLine,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = subtitle ?: subtitleFor(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The fade the play button arrives with. Its own function, outside the card's
 * column, so the plain AnimatedVisibility is the one that resolves.
 */
@Composable
private fun PlayOverlay(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        content()
    }
}

internal fun subtitleFor(item: MediaItemDto): String = when (item.kind) {
    ItemKind.EPISODE -> listOfNotNull(item.episodeLabel, item.name).joinToString(" · ")
    ItemKind.SERIES -> listOfNotNull(
        item.year?.toString(),
        item.episodeCount?.takeIf { it > 0 }?.let { "$it 集" } ?: item.childCount?.let { "$it 季" }
    ).joinToString(" · ")
    else -> listOfNotNull(item.year?.toString(), formatRuntime(item.runtimeMs)).joinToString(" · ")
}

/**
 * A shelf: a title, the cards, a way to see everything on it, and — where
 * there is a pointer to hover with — arrows at either end, since a vertical
 * wheel scrolls the page and not every mouse has a sideways one.
 */
@Composable
fun MediaRow(
    title: String,
    items: List<MediaItemDto>,
    itemWidth: Dp = 152.dp,
    shape: CardShape = CardShape.PORTRAIT,
    trailing: @Composable (() -> Unit)? = null,
    onSeeAll: (() -> Unit)? = null,
    downloaded: (MediaItemDto) -> Boolean = { false },
    /** Per-item menu entries, handed straight to each [PosterCard]. */
    menu: (@Composable ColumnScope.(item: MediaItemDto, dismiss: () -> Unit) -> Unit)? = null,
    /** What the tiles' play button does, if this row's cards should have one. */
    onItemPlay: ((MediaItemDto) -> Unit)? = null,
    onItemClick: (MediaItemDto) -> Unit
) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing?.invoke()
                onSeeAll?.let { TextButton(onClick = it) { Text("查看全部") } }
            }
        }
        val rowState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val hover = remember { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        Box(Modifier.fillMaxWidth().hoverable(hover)) {
            LazyRow(
                state = rowState,
                modifier = Modifier.wheelScrollsHorizontally(rowState),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                items(items, key = { it.id }) { item ->
                    PosterCard(
                        item,
                        width = itemWidth,
                        shape = shape,
                        downloaded = downloaded(item),
                        menu = menu?.let { entries -> { dismiss -> entries(item, dismiss) } },
                        onPlay = onItemPlay?.let { play -> { play(item) } }
                    ) { onItemClick(item) }
                }
            }
            if (hasHoverPointer) {
                val step = with(LocalDensity.current) { (itemWidth * 3).toPx() }
                val cardHeight = if (shape == CardShape.LANDSCAPE) itemWidth * 9f / 16f else itemWidth * 1.5f
                RowArrow(
                    visible = hovered && rowState.canScrollBackward,
                    left = true,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = cardHeight / 2 - 20.dp)
                ) { scope.launch { rowState.animateScrollBy(-step) } }
                RowArrow(
                    visible = hovered && rowState.canScrollForward,
                    left = false,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = cardHeight / 2 - 20.dp)
                ) { scope.launch { rowState.animateScrollBy(step) } }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RowArrow(visible: Boolean, left: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        FilledTonalIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f)
            )
        ) {
            Icon(
                if (left) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (left) "向左滚动" else "向右滚动"
            )
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Centred like the title above it; it wrapped flush left under a
            // centred heading.
            textAlign = TextAlign.Center
        )
        action?.let {
            Spacer(Modifier.height(20.dp))
            it()
        }
    }
}

/**
 * A name that stands for something with a page of its own — the series above an
 * episode, the library behind a card. Colour carries the affordance, because
 * nothing else in the app is underlined at rest; the underline and the hand
 * cursor only show on hover, which is all desktop and web need. A null
 * [onClick] renders plain text, so an item that never learned its parent's id
 * reads as a label instead of a link that goes nowhere.
 */
@Composable
fun LinkText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null
) {
    if (onClick == null) {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
        return
    }

    // The ripple's state layer already draws hover, focus and press, so nothing
    // here has to watch those flags to give the link a resting appearance.
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        modifier = modifier
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            // Padding before clickable, so it counts towards the hit area: the
            // link used to be exactly one line of text tall — 20dp against the
            // 48dp a finger needs.
            .padding(vertical = 12.dp)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick
            )
            .semantics { role = Role.Button },
        // The caller's colour is honoured rather than overwritten with primary:
        // three of the four call sites passed one and were quietly ignored, so
        // the same series name changed colour with the data behind it.
        style = style,
        color = color,
        fontWeight = fontWeight,
        // Underlined at rest, not only under a cursor. Colour alone cannot
        // carry "this is a link" (WCAG 1.4.1), and a touch screen has no hover
        // to reveal it with.
        textDecoration = TextDecoration.Underline,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** A small label; given [onClick], a way to that value elsewhere — a genre's other titles. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    val textColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val content: @Composable () -> Unit = {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
    if (onClick == null) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            content = content
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            content = content
        )
    }
}

/**
 * Whether the side rail is on screen. The rail follows the window's width;
 * a page measuring only its own share of it came to a different answer
 * between 600 and about 680dp, and showed the libraries twice.
 */
val LocalRailShown = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A dropdown menu that holds the keyboard while it is open, so Esc and
 * Backspace close it instead of taking the page behind it back a step.
 */
@Composable
fun AppMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset) {
        com.daview.app.data.ModalMarker()
        content()
    }
}
