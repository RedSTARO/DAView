package com.daview.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto

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
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.04f else 1f, label = "poster-scale")
    val aspect = if (item.kind == ItemKind.EPISODE) 16f / 9f else 2f / 3f

    // Where the menu was asked for, in pixels from the tile's top-left corner.
    // Null means closed; a right-click carries the cursor, a long press has no
    // position to speak of and falls back to the corner.
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var lastPointer by remember { mutableStateOf(PointerType.Unknown) }
    val density = LocalDensity.current

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
                indication = null,
                onClick = onClick,
                // A held mouse button is not a request for the menu — it already
                // has the right button. Touch is the only input with nothing else.
                onLongClick = if (menu == null) null else {
                    { if (lastPointer == PointerType.Touch) menuAt = Offset.Zero }
                }
            )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect).scale(scale),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = if (hovered) 6.dp else 0.dp
        ) {
            Box {
                if (item.posterUrl != null) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (item.userData.played) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "已观看",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp)
                    )
                }

                if (hovered) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                            )
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(8.dp).size(26.dp)
                            )
                        }
                    }
                }

                val progress = item.userData.playedPercentage.toFloat()
                if (showProgress && progress > 0.01f && !item.userData.played) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                }

                // Anchored to the artwork, which shares its top-left corner with
                // the tile, so the offset the gesture reported lands under the
                // cursor.
                if (menu != null) {
                    DropdownMenu(
                        expanded = menuAt != null,
                        onDismissRequest = { menuAt = null },
                        offset = menuAt.toDpOffset(density)
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
    onItemClick: (MediaItemDto) -> Unit
) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title, trailing)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items, key = { it.id }) { item ->
                PosterCard(
                    item,
                    width = itemWidth,
                    menu = menu?.let { entries -> { dismiss -> entries(item, dismiss) } }
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

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = text,
        modifier = modifier
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        style = style,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = fontWeight,
        textDecoration = if (hovered) TextDecoration.Underline else null,
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
