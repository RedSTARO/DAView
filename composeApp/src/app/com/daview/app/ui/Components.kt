package com.daview.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import kotlinx.coroutines.delay

/** `1:23:45` for anything over an hour, `12:34` otherwise. */
fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return "--:--"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "$hours:${pad(minutes)}:${pad(seconds)}" else "${minutes}:${pad(seconds)}"
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
            fontWeight = FontWeight.SemiBold
        )
        trailing?.invoke()
    }
}

/**
 * Poster tile. Hovering lifts the card on desktop and web, which is the main
 * expressive motion cue in the browse grids.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: MediaItemDto,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 152.dp,
    showProgress: Boolean = true,
    /** Overrides the two lines under the tile. Both default to what the item says. */
    title: String? = null,
    subtitle: String? = null,
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
    val aspect = if (item.kind == ItemKind.EPISODE) 16f / 9f else 2f / 3f

    // Where the menu was asked for, in pixels from the tile's top-left corner.
    // Null means closed; a right-click carries the cursor, a long press has no
    // position to speak of and falls back to the corner.
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var lastPointer by remember { mutableStateOf(PointerType.Unknown) }
    val density = LocalDensity.current

    val progress = item.userData.playedPercentage.toFloat()
    val watchState = when {
        item.userData.played -> "已观看"
        progress > 0.01f -> "已看 ${(progress * 100).toInt()}%"
        else -> "未观看"
    }

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
            .semantics(mergeDescendants = true) { stateDescription = watchState }
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect).scale(scale),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = if (lifted) 6.dp else 0.dp,
            border = if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
        ) {
            Box {
                if (item.posterUrl != null) {
                    AsyncImage(
                        model = item.posterUrl,
                        // Decorative: the title is spelled out directly below,
                        // and reading it twice is what a screen reader did.
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ArtworkPlaceholder(item.kind, Modifier.fillMaxSize())
                }

                if (item.userData.played) {
                    // On its own circle rather than bare on the artwork: over a
                    // pale poster a tinted icon was down at 1.7:1.
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(3.dp).size(16.dp)
                        )
                    }
                }

                if (lifted && onPlay != null) {
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
                            onClick = onPlay,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "播放",
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
                        color = MaterialTheme.colorScheme.secondary,
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
                    DropdownMenu(
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
        Text(
            text = title ?: item.seriesName?.takeIf { item.kind == ItemKind.EPISODE } ?: item.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle ?: subtitleFor(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun subtitleFor(item: MediaItemDto): String = when (item.kind) {
    ItemKind.EPISODE -> listOfNotNull(item.episodeLabel, item.name).joinToString(" · ")
    ItemKind.SERIES -> listOfNotNull(item.year?.toString(), item.childCount?.let { "$it 季" })
        .joinToString(" · ")
    else -> item.year?.toString().orEmpty()
}

@Composable
fun MediaRow(
    title: String,
    items: List<MediaItemDto>,
    itemWidth: androidx.compose.ui.unit.Dp = 152.dp,
    trailing: @Composable (() -> Unit)? = null,
    /** Per-item menu entries, handed straight to each [PosterCard]. */
    menu: (@Composable ColumnScope.(item: MediaItemDto, dismiss: () -> Unit) -> Unit)? = null,
    /** What the tiles' play button does, if this row's cards should have one. */
    onItemPlay: ((MediaItemDto) -> Unit)? = null,
    onItemClick: (MediaItemDto) -> Unit
) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title, trailing)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Cards in one row are the same width but not the same shape — a
            // 2:3 poster beside a 16:9 still — so they are hung from their
            // bottom edge and the titles under them share a baseline.
            verticalAlignment = Alignment.Bottom
        ) {
            items(items, key = { it.id }) { item ->
                PosterCard(
                    item,
                    width = itemWidth,
                    menu = menu?.let { entries -> { dismiss -> entries(item, dismiss) } },
                    onPlay = onItemPlay?.let { play -> { play(item) } }
                ) { onItemClick(item) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun EmptyState(title: String, description: String, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
fun Chip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
