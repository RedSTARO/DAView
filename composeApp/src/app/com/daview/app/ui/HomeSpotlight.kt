@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.daview.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.shared.model.MediaItemDto
import kotlin.math.roundToInt

/** A cinematic crop on wide panes, with a fully opaque reading area at the leading edge. */
@Composable
internal fun HomeSpotlight(
    item: MediaItemDto,
    label: String,
    shortPane: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onSeries: (() -> Unit)?,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val surface = colors.surfaceContainer
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val rtl = direction == LayoutDirection.Rtl
    val measurer = rememberTextMeasurer()
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    var lastPointer by remember(item.id) { mutableStateOf(PointerType.Unknown) }
    val dismiss: () -> Unit = { menuOpen = false }

    // Prefer the scene. If it fails, try the poster before reclaiming the artwork area.
    val artwork = listOfNotNull(item.backdropUrl, item.posterUrl).filter { it.isNotBlank() }.distinct()
    var failedArtwork by remember(item.id, artwork) { mutableStateOf(emptySet<String>()) }
    val image = artwork.firstOrNull { it !in failedArtwork }
    val headline = item.seriesName?.takeIf { it.isNotBlank() } ?: item.name
    val episode = item.seriesName?.takeIf { it.isNotBlank() }?.let {
        listOfNotNull(item.episodeLabel, item.name.takeIf(String::isNotBlank)).joinToString(" · ")
    }.orEmpty()
    val metadata = listOfNotNull(
        item.year?.toString(), formatRuntime(item.runtimeMs),
        item.communityRating?.takeIf { it.isFinite() && it > 0 }?.let { "评分 ${(it * 10).roundToInt() / 10.0}" }
    ).joinToString(" · ")
    val resume = item.userData.positionMs > 0 && !item.userData.played
    val playLabel = if (resume) "继续播放" else "播放"
    val smallButton = ButtonDefaults.MinHeight
    val buttonPadding = ButtonDefaults.contentPaddingFor(smallButton)
    val gap = 8.dp

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val inset = if (maxWidth < 336.dp || shortPane) 16.dp else 24.dp
        fun labelWidth(text: String, style: TextStyle) = with(density) {
            measurer.measure(AnnotatedString(text), style = style, maxLines = 1, softWrap = false).size.width.toDp()
        }
        // Reserve the real label widths (including the user's font scale), not a device breakpoint.
        val buttonInsets = buttonPadding.calculateLeftPadding(direction) + buttonPadding.calculateRightPadding(direction)
        val primaryWidth = (labelWidth(playLabel, type.labelLargeEmphasized) + buttonInsets + 20.dp + gap)
            .coerceAtLeast(ButtonDefaults.MinWidth)
        val detailWidth = (labelWidth("详情", type.labelLarge) + buttonInsets).coerceAtLeast(ButtonDefaults.MinWidth)
        val seriesWidth = (labelWidth("整部剧", type.labelLarge) + buttonInsets).coerceAtLeast(ButtonDefaults.MinWidth)
        val mainActions = primaryWidth + gap + detailWidth
        val allActions = mainActions + if (onSeries != null) gap + seriesWidth else 0.dp
        val desiredCopy = maxOf(maxWidth * 0.45f, allActions + inset * 2 + 4.dp)
        val sideArtwork = image != null && maxWidth >= 640.dp && maxWidth - desiredCopy >= 256.dp
        val stackedArtwork = image != null && !sideArtwork && !shortPane
        val showArtwork = sideArtwork || stackedArtwork
        val copyWidth = if (sideArtwork) desiredCopy else maxWidth
        val showSeries = onSeries != null && copyWidth - inset * 2 >= allActions + 4.dp
        val artworkHeight = (maxWidth * 0.32f).coerceIn(104.dp, 188.dp)
        val poster = image != null && image != item.backdropUrl

        Surface(shape = MaterialTheme.shapes.extraLargeIncreased, color = surface) {
            Box {
                if (image != null && sideArtwork) {
                    // Artwork matches the measured card, so it never adds empty height. Its fade
                    // begins at the end of the opaque reading area and stays within the card.
                    Box(Modifier.matchParentSize().padding(start = copyWidth - 16.dp)) {
                        SpotlightImage(
                            image = image,
                            poster = poster,
                            onError = { failedArtwork = failedArtwork + image },
                            modifier = Modifier.fillMaxSize().drawWithContent {
                                drawContent()
                                val fadeWidth = 88.dp.toPx().coerceAtMost(size.width)
                                if (fadeWidth > 0f) {
                                    val clear = surface.copy(alpha = 0f)
                                    drawRect(Brush.horizontalGradient(
                                        colors = if (rtl) listOf(clear, surface) else listOf(surface, clear),
                                        startX = if (rtl) size.width - fadeWidth else 0f,
                                        endX = if (rtl) size.width else fadeWidth
                                    ))
                                }
                            }
                        )
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    if (image != null && stackedArtwork) {
                        SpotlightImage(
                            image = image,
                            poster = poster,
                            onError = { failedArtwork = failedArtwork + image },
                            modifier = Modifier.fillMaxWidth().height(artworkHeight)
                        )
                    }
                    Column(
                        Modifier.width(copyWidth).padding(horizontal = inset, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = if (showArtwork) 28.dp else 44.dp)
                                .padding(end = if (showArtwork) 0.dp else 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, style = type.labelLargeEmphasized, color = colors.tertiary)
                        }
                        Tooltip(headline) {
                            Box(
                                Modifier.clip(MaterialTheme.shapes.small)
                                    .secondaryClick(onPointerType = { lastPointer = it }, onOpen = { menuOpen = true })
                                    .combinedClickable(
                                        role = Role.Button, onClickLabel = "查看详情", onClick = onOpen,
                                        onLongClickLabel = "打开菜单",
                                        onLongClick = { if (lastPointer != PointerType.Mouse) menuOpen = true }
                                    ).heightIn(min = 48.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    headline,
                                    style = if (sideArtwork) type.displaySmallEmphasized else type.headlineSmallEmphasized,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { heading() }
                                )
                            }
                        }
                        if (episode.isNotEmpty() || metadata.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (episode.isNotEmpty()) {
                                    Text(episode, style = type.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (metadata.isNotEmpty()) {
                                    Text(metadata, style = type.bodyMedium, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                        if (resume) SpotlightProgress(item, Modifier.padding(top = 4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            PrimaryPageAction(playLabel, Icons.Filled.PlayArrow, onPlay, largeTarget = false)
                            OutlinedButton(
                                onClick = onOpen,
                                shapes = ButtonDefaults.shapesFor(smallButton),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant),
                                border = BorderStroke(1.dp, colors.outline),
                                contentPadding = buttonPadding
                            ) { Text("详情", maxLines = 1) }
                            if (onSeries != null && showSeries) {
                                TextButton(onClick = onSeries, contentPadding = buttonPadding) { Text("整部剧", maxLines = 1) }
                            }
                        }
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp)) {
                    Tooltip("更多操作") {
                        FilledTonalIconButton(
                            onClick = { menuOpen = true },
                            colors = if (sideArtwork || stackedArtwork) {
                                IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = colors.surface,
                                    contentColor = colors.onSurface
                                )
                            } else IconButtonDefaults.filledTonalIconButtonColors()
                        ) { Icon(Icons.Filled.MoreHoriz, contentDescription = "更多操作") }
                    }
                    AppMenu(expanded = menuOpen, onDismissRequest = dismiss) {
                        if (onSeries != null && !showSeries) {
                            DropdownMenuItem(
                                text = { Text("整部剧") },
                                leadingIcon = { Icon(Icons.Outlined.Tv, contentDescription = null) },
                                onClick = { dismiss(); onSeries() }
                            )
                        }
                        menu(dismiss)
                    }
                }
            }
        }
    }
}

/** A portrait poster is shown intact, on a tonal matte, when there's no landscape backdrop. */
@Composable
private fun SpotlightImage(image: String, poster: Boolean, onError: () -> Unit, modifier: Modifier) {
    key(image) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = if (poster) ContentScale.Fit else ContentScale.Crop,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            onError = { onError() }
        )
    }
}

@Composable
private fun SpotlightProgress(item: MediaItemDto, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "已看 ${formatDuration(item.userData.positionMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant
        )
        item.runtimeMs?.takeIf { it > 0 }?.let { duration ->
            LinearProgressIndicator(
                progress = { (item.userData.positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "观看进度" },
                trackColor = colors.secondaryContainer
            )
        }
    }
}
