@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.daview.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.shared.model.DownloadDto
import com.daview.shared.model.MediaItemDto

private val EpisodeTarget = 48.dp
private const val EpisodeMenuLabel = "剧集操作"

/** A single card surface; artwork, caption and overflow are separate, non-overlapping targets. */
@Composable
internal fun DetailEpisodeCard(
    episode: MediaItemDto,
    isCurrent: Boolean,
    download: DownloadDto?,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val actions = remember(episode.id) { EpisodeActions() }
    val current = "当前剧集".takeIf { isCurrent }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrent) colors.secondaryContainer else colors.surfaceContainerHigh,
        contentColor = if (isCurrent) colors.onSecondaryContainer else colors.onSurface
    ) {
        BoxWithConstraints {
            val captionPadding = if (maxWidth >= 144.dp) 12.dp else 4.dp
            val sideBySide = maxWidth >= EpisodeTarget * 2 + captionPadding * 2
            val stillHeight = (maxWidth * 9f / 16f).coerceAtLeast(EpisodeTarget)
            Column(Modifier.fillMaxWidth().episodeContextClick(actions)) {
                EpisodePlayTarget(
                    episode, actions, onPlay, highlighted = isCurrent, showStill = true,
                    modifier = Modifier.fillMaxWidth().height(stillHeight)
                )
                // Keep the caption compact; the play control occupies no extra row.
                Column(Modifier.padding(start = captionPadding, end = if (sideBySide) 0.dp else captionPadding)) {
                    if (sideBySide) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EpisodeCaption(
                                episode, current, download, actions, onOpen,
                                modifier = Modifier.weight(1f), card = true
                            )
                            EpisodeMore(actions, menu)
                        }
                    } else {
                        EpisodeCaption(episode, current, download, actions, onOpen, card = true)
                        Box(Modifier.align(Alignment.End)) { EpisodeMore(actions, menu) }
                    }
                }
            }
        }
    }
}

/** An expressive segmented list surface without a whole-row click stealing child actions. */
@Composable
internal fun DetailEpisodeRow(
    episode: MediaItemDto,
    isCurrent: Boolean,
    isNext: Boolean,
    roomy: Boolean,
    download: DownloadDto?,
    index: Int,
    count: Int,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val actions = remember(episode.id) { EpisodeActions() }
    val marker = when {
        isCurrent -> "当前剧集"
        isNext -> "接下来"
        else -> null
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val extraText = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
        val showStill = roomy && maxWidth >= (400f + 120f * extraText).dp
        val across = maxWidth >= EpisodeTarget * 3
        val horizontal = when {
            showStill -> 12.dp
            maxWidth >= 200.dp -> 8.dp
            maxWidth >= 160.dp -> 4.dp
            else -> 0.dp
        }
        val gap = if (maxWidth >= 168.dp) 8.dp else 0.dp
        Surface(
            modifier = Modifier.fillMaxWidth().episodeContextClick(actions),
            shape = ListItemDefaults.segmentedShapes(index, count).shape,
            color = if (marker != null) colors.secondaryContainer else colors.surfaceContainerLow,
            contentColor = if (marker != null) colors.onSecondaryContainer else colors.onSurface
        ) {
            if (across) {
                Row(
                    Modifier.padding(start = horizontal, end = horizontal, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    EpisodePlayTarget(
                        episode, actions, onPlay, highlighted = marker != null, showStill = showStill,
                        modifier = if (showStill) {
                            Modifier.size(width = 114.dp, height = 64.dp).clip(MaterialTheme.shapes.small)
                        } else Modifier.size(EpisodeTarget)
                    )
                    EpisodeCaption(episode, marker, download, actions, onOpen, modifier = Modifier.weight(1f))
                    EpisodeMore(actions, menu)
                }
            } else {
                // Three independent 48dp targets cannot share a genuinely tiny pane.
                Column(Modifier.padding(horizontal = horizontal, vertical = 4.dp)) {
                    EpisodeCaption(episode, marker, download, actions, onOpen)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        EpisodePlayTarget(
                            episode, actions, onPlay, highlighted = marker != null, showStill = false,
                            modifier = Modifier.size(EpisodeTarget)
                        )
                        EpisodeMore(actions, menu)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodePlayTarget(
    episode: MediaItemDto,
    actions: EpisodeActions,
    onPlay: () -> Unit,
    highlighted: Boolean,
    showStill: Boolean,
    modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    val playLabel = "播放 ${episode.episodeLabel ?: episode.name}"
    val image = episode.posterUrl?.takeIf { it.isNotBlank() } ?: episode.backdropUrl?.takeIf { it.isNotBlank() }
    val progress = episode.userData.playedPercentage.toFloat().takeIf { it.isFinite() && it > 0f }
    Tooltip(playLabel) {
        Box(
            modifier
                .then(if (showStill) Modifier.background(colors.surfaceContainerHighest) else Modifier.clip(CircleShape))
                .episodeAction(actions, onClick = onPlay)
                .semantics { contentDescription = playLabel },
            contentAlignment = Alignment.Center
        ) {
            if (showStill && image != null) {
                AsyncImage(
                    model = image, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            val fill = when {
                highlighted -> colors.primary
                showStill -> colors.inverseSurface
                else -> colors.secondaryContainer
            }
            val ink = when {
                highlighted -> colors.onPrimary
                showStill -> colors.inverseOnSurface
                else -> colors.onSecondaryContainer
            }
            // This glyph and the whole still share one action and one focus stop.
            Box(Modifier.size(40.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp), tint = ink)
            }
            if (showStill && progress != null && !episode.userData.played) {
                LinearProgressIndicator(
                    progress = { progress.coerceAtMost(1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest
                )
            }
        }
    }
}

@Composable
private fun EpisodeCaption(
    episode: MediaItemDto,
    marker: String?,
    download: DownloadDto?,
    actions: EpisodeActions,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    card: Boolean = false
) {
    val title = listOfNotNull(episode.episodeLabel, episode.name).joinToString(" · ")
    val supporting = listOfNotNull(marker, watchedLabel(episode), downloadLabel(download), formatRuntime(episode.runtimeMs))
        .joinToString(" · ")
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Tooltip("查看分集详情：$title") {
        Column(
            modifier.fillMaxWidth().heightIn(min = EpisodeTarget)
                .clip(MaterialTheme.shapes.extraSmall)
                .episodeAction(actions, clickLabel = "查看分集详情", onClick = onOpen)
                .semantics { if (marker != null) stateDescription = marker }
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
        ) {
            Text(
                title,
                style = when {
                    card && marker != null -> typography.titleSmallEmphasized
                    card -> typography.titleSmall
                    marker != null -> typography.titleMediumEmphasized
                    else -> typography.titleMedium
                },
                minLines = if (card) 2 else 1, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                supporting,
                style = typography.labelMedium,
                color = if (marker != null) colors.onSecondaryContainer else colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EpisodeMore(actions: EpisodeActions, menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
    Box {
        TipIconButton(EpisodeMenuLabel, onClick = actions::show, modifier = Modifier.size(EpisodeTarget)) {
            Icon(Icons.Filled.MoreVert, contentDescription = EpisodeMenuLabel)
        }
        AppMenu(expanded = actions.open, onDismissRequest = actions::dismiss) { menu(actions::dismiss) }
    }
}

private class EpisodeActions {
    var open by mutableStateOf(false)
        private set
    var pointer = PointerType.Unknown

    fun show() { open = true }
    fun dismiss() { open = false }
    fun longPress() { if (pointer != PointerType.Mouse) show() }
}

private fun Modifier.episodeContextClick(actions: EpisodeActions) = secondaryClick(
    onPointerType = { actions.pointer = it }, onOpen = { actions.show() }
)

private fun Modifier.episodeAction(actions: EpisodeActions, clickLabel: String? = null, onClick: () -> Unit): Modifier =
    pointerHoverIcon(PointerIcon.Hand).combinedClickable(
        role = Role.Button,
        onClickLabel = clickLabel,
        onClick = onClick,
        onLongClickLabel = "打开菜单",
        onLongClick = actions::longPress
    )
