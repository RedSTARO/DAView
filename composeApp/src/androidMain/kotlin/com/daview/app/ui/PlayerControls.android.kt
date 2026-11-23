package com.daview.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

internal enum class PlayerMenu(val title: String) {
    Audio("音轨"), Subtitle("字幕"), Tuning("字幕延迟、大小与播放速度"), Chapter("章节"), More("更多播放选项")
}

@Composable
internal fun PlayerMenuButton(
    menu: PlayerMenu,
    icon: ImageVector,
    usePopup: Boolean,
    openMenu: PlayerMenu?,
    onMenu: (PlayerMenu?) -> Unit,
    content: @Composable ColumnScope.(PlayerMenu) -> Unit
) {
    Box {
        IconButton(onClick = { onMenu(menu) }) { Icon(icon, contentDescription = menu.title, tint = Color.White) }
        if (usePopup) {
            DropdownMenu(openMenu == menu, onDismissRequest = { onMenu(null) }) { content(menu) }
        }
    }
}

/** Unlike a window-level Popup, both the menu and its scroll area stay inside the pane. */
@Composable
internal fun BoxScope.PanePlayerMenu(
    menu: PlayerMenu,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(PlayerMenu) -> Unit
) {
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)).clickable(
        onClickLabel = "关闭菜单", onClick = onDismiss
    ))
    Surface(
        modifier = Modifier.align(Alignment.Center).padding(8.dp).widthIn(max = 400.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        // Keep the dismiss button above the scrollable options, including with a small bottom pane.
        Column {
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content(menu) }
        }
    }
}

@Composable
internal fun PlayerToolbar(
    title: String,
    onBack: (() -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        if (maxWidth >= 600.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) PlayerBack(onBack)
                PlayerTitle(title, Modifier.weight(1f).padding(horizontal = 8.dp))
                buttons()
            }
        } else {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) PlayerBack(onBack)
                    PlayerTitle(title, Modifier.weight(1f).padding(horizontal = 8.dp))
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons
                )
            }
        }
    }
}

@Composable
private fun PlayerTitle(title: String, modifier: Modifier) {
    Text(title, modifier, color = Color.White, style = MaterialTheme.typography.titleMedium,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

internal fun WindowPane.fitsPlayerHud(): Boolean = width >= 320.dp && height >= 300.dp

@Composable
internal fun PlayerBack(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
    }
}

@Composable
private fun PlayerPlayPause(player: Player, showPause: Boolean, onInteraction: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = {
        if (showPause) player.pause() else player.play()
        onInteraction()
    }, modifier = modifier) {
        Icon(if (showPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (showPause) "暂停" else "播放", tint = Color.White)
    }
}

/** Prioritize the two actions that must never be clipped by an optional title, seek strip or menu. */
@Composable
internal fun CompactPlayerActions(
    player: Player,
    showPause: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    additional: @Composable RowScope.() -> Unit
) {
    BoxWithConstraints(modifier) {
        val roomForMenu = maxWidth >= 144.dp
        when {
            maxWidth >= 96.dp && maxHeight >= 48.dp -> Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PlayerBack(onBack)
                if (roomForMenu) additional()
                PlayerPlayPause(player, showPause, onInteraction = {})
            }
            maxWidth >= 48.dp && maxHeight >= 96.dp -> Column(Modifier.align(Alignment.TopStart)) {
                PlayerBack(onBack)
                PlayerPlayPause(player, showPause, onInteraction = {})
            }
            maxWidth >= maxHeight -> {
                // Below the size of two standard targets, retain both with bounds that do not overlap.
                val side = minOf(maxWidth / 2, maxHeight)
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        PlayerBack(onBack, Modifier.size(side))
                        PlayerPlayPause(player, showPause, onInteraction = {}, modifier = Modifier.size(side))
                    }
                }
            }
            else -> {
                val side = minOf(maxWidth, maxHeight / 2)
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Column(Modifier.align(Alignment.TopStart)) {
                        PlayerBack(onBack, Modifier.size(side))
                        PlayerPlayPause(player, showPause, onInteraction = {}, modifier = Modifier.size(side))
                    }
                }
            }
        }
    }
}

/** Ordinary accessible controls: no picture gesture detector covers this pane. */
@Composable
internal fun FoldTransport(
    player: Player,
    showPause: Boolean,
    onInteraction: () -> Unit,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    var duration by remember(player) { mutableLongStateOf(player.duration) }
    var position by remember(player) { mutableLongStateOf(player.currentPosition) }
    var canSeek by remember(player) { mutableStateOf(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val latestOnScrubbing by rememberUpdatedState(onScrubbing)
    DisposableEffect(player) { onDispose { latestOnScrubbing(false) } }
    LaunchedEffect(player) {
        while (true) {
            duration = player.duration
            position = player.currentPosition
            canSeek = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            delay(250)
        }
    }
    val seekable = canSeek && duration > 0
    val elapsed = scrubFraction?.let { (it * duration.toDouble()).roundToLong() } ?: position
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) PlayerBack(onBack)
            if (seekable) {
                IconButton(onClick = {
                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    position = player.currentPosition
                    onInteraction()
                }) { Icon(Icons.Filled.Replay10, contentDescription = "快退 10 秒", tint = Color.White) }
            }
            PlayerPlayPause(player, showPause, onInteraction)
            if (seekable) {
                IconButton(onClick = {
                    player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration))
                    position = player.currentPosition
                    onInteraction()
                }) { Icon(Icons.Filled.Forward10, contentDescription = "快进 10 秒", tint = Color.White) }
            }
        }
        if (!seekable) return@Column
        Slider(
            value = scrubFraction ?: if (duration > 0) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f,
            onValueChange = {
                scrubFraction = it
                onScrubbing(true)
            },
            onValueChangeFinished = {
                // A tap or an accessibility adjustment can set the fraction and finish
                // before a recomposition updates the formatted label above.
                val target = scrubFraction
                if (seekable && target != null) {
                    player.seekTo((target * duration.toDouble()).roundToLong().coerceIn(0L, duration))
                    position = player.currentPosition
                }
                scrubFraction = null
                onScrubbing(false)
                onInteraction()
            },
            enabled = seekable,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.26f)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "播放进度" }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (elapsed > 0) formatDuration(elapsed) else "0:00", color = Color.White,
                style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(formatDuration(duration), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Insets extend only from window edges, not from the hinge-side edge of every pane. */
internal fun WindowPane.inset(insets: WindowInsets, density: Density, direction: LayoutDirection): WindowPane = with(density) {
    val l = (left + insets.getLeft(this, direction).toDp()).coerceAtMost(right)
    val t = (top + insets.getTop(this).toDp()).coerceAtMost(bottom)
    WindowPane(
        left = l,
        top = t,
        right = (right - insets.getRight(this, direction).toDp()).coerceAtLeast(l),
        bottom = (bottom - insets.getBottom(this).toDp()).coerceAtLeast(t)
    )
}

internal fun panePadding(pane: WindowPane, bounds: WindowPane, direction: LayoutDirection): PaddingValues {
    val safe = pane.clampTo(bounds)
    val left = safe.left - pane.left
    val right = pane.right - safe.right
    val rtl = direction == LayoutDirection.Rtl
    return PaddingValues(
        start = if (rtl) right else left, top = safe.top - pane.top,
        end = if (rtl) left else right, bottom = pane.bottom - safe.bottom
    )
}
