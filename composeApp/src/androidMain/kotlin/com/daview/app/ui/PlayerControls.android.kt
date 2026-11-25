package com.daview.app.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal enum class PlayerMenu(val title: String) {
    Audio("音轨"), Subtitle("字幕"), Tuning("字幕延迟、大小与播放速度"), Chapter("章节"), More("更多播放选项")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerMenuButton(
    menu: PlayerMenu,
    icon: ImageVector,
    usePopup: Boolean,
    openMenu: PlayerMenu?,
    onMenu: (PlayerMenu?) -> Unit,
    content: @Composable ColumnScope.(PlayerMenu) -> Unit,
    contained: Boolean = false,
    focus: FocusRequester? = null
) {
    Box {
        PlaybackToolButton(
            icon, menu.title, onClick = { onMenu(menu) }, active = openMenu == menu, contained = contained,
            modifier = (if (focus == null) Modifier else Modifier.focusRequester(focus)).semantics {
                if (openMenu == menu) stateDescription = "已展开"
            }
        )
        if (usePopup) {
            DropdownMenu(
                expanded = openMenu == menu,
                onDismissRequest = { onMenu(null) },
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                key(menu) { content(menu) }
            }
        }
    }
}

/** Unlike a window-level Popup, both the menu and its scroll area stay inside the pane. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BoxScope.PanePlayerMenu(
    menu: PlayerMenu,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(PlayerMenu) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(menu) { runCatching { focus.requestFocus() } }
    Box(Modifier.matchParentSize().background(colors.scrim.copy(alpha = 0.56f)).clickable(
        onClickLabel = "关闭菜单", onClick = onDismiss
    ))
    Surface(
        modifier = Modifier.align(Alignment.Center).padding(8.dp).widthIn(max = 400.dp).fillMaxWidth().focusGroup(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface
    ) {
        // Keep the dismiss button above the scrollable options, including with a small bottom pane.
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(menu.title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMediumEmphasized)
                PlaybackToolButton(Icons.Filled.Close, "关闭菜单", onDismiss, modifier = Modifier.focusRequester(focus))
            }
            key(menu) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 4.dp)) {
                    content(menu)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerToolbar(
    title: String,
    onBack: (() -> Unit)? = null,
    overflow: @Composable RowScope.() -> Unit,
    buttons: @Composable RowScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val showFullTools = maxWidth >= PlayerDirectToolsWidth
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 1120.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLargeIncreased,
            color = colors.surfaceContainer,
            contentColor = colors.onSurface
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) PlayerBack(onBack, contained = false)
                Text(
                    title, Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (showFullTools) buttons() else overflow()
            }
        }
    }
}

internal val PlayerDirectToolsWidth = 840.dp

internal fun WindowPane.fitsPlayerHud(): Boolean = width >= 320.dp && height >= 300.dp

@Composable
private fun PlayerBack(onBack: () -> Unit, modifier: Modifier = Modifier, contained: Boolean = true) {
    PlaybackToolButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack, modifier, contained = contained)
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
        val toggle = { if (showPause) player.pause() else player.play() }
        val roomForMenu = maxWidth >= 144.dp
        when {
            maxWidth >= 96.dp && maxHeight >= 48.dp -> Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PlayerBack(onBack)
                if (roomForMenu) additional()
                PlaybackMainButton(showPause, toggle, Modifier.size(48.dp))
            }
            maxWidth >= 48.dp && maxHeight >= 96.dp -> Column(Modifier.align(Alignment.TopStart)) {
                PlayerBack(onBack)
                PlaybackMainButton(showPause, toggle, Modifier.size(48.dp))
            }
            maxWidth >= maxHeight -> {
                // Below the size of two standard targets, retain both with bounds that do not overlap.
                val side = minOf(maxWidth / 2, maxHeight)
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        PlayerBack(onBack, Modifier.size(side))
                        PlaybackMainButton(showPause, toggle, Modifier.size(side))
                    }
                }
            }
            else -> {
                val side = minOf(maxWidth, maxHeight / 2)
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Column(Modifier.align(Alignment.TopStart)) {
                        PlayerBack(onBack, Modifier.size(side))
                        PlaybackMainButton(showPause, toggle, Modifier.size(side))
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
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.padding(horizontal = 8.dp).widthIn(max = 720.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
        contentColor = colors.onSurface
    ) {
        PlaybackTimeDirection {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) PlayerBack(onBack, contained = false)
                    if (seekable) {
                        PlaybackToolButton(Icons.Filled.Replay10, "快退 10 秒", onClick = {
                            player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                            position = player.currentPosition
                            onInteraction()
                        })
                    }
                    PlaybackMainButton(showPause, onClick = {
                        if (showPause) player.pause() else player.play()
                        onInteraction()
                    })
                    if (seekable) {
                        PlaybackToolButton(Icons.Filled.Forward10, "快进 10 秒", onClick = {
                            player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration))
                            position = player.currentPosition
                            onInteraction()
                        })
                    }
                }
                if (seekable) {
                    Slider(
                        value = scrubFraction ?: (position.toDouble() / duration).toFloat().coerceIn(0f, 1f),
                        onValueChange = {
                            scrubFraction = it
                            onScrubbing(true)
                        },
                        onValueChangeFinished = {
                            // A tap or an accessibility adjustment can set the fraction and finish
                            // before a recomposition updates the formatted label above.
                            val target = scrubFraction
                            if (target != null) {
                                player.seekTo((target * duration.toDouble()).roundToLong().coerceIn(0L, duration))
                                position = player.currentPosition
                            }
                            scrubFraction = null
                            onScrubbing(false)
                            onInteraction()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                            contentDescription = "播放进度"
                            stateDescription = "${if (elapsed > 0) formatDuration(elapsed) else "0:00"} / ${formatDuration(duration)}"
                        }
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (elapsed > 0) formatDuration(elapsed) else "0:00",
                            color = colors.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1
                        )
                        Text(
                            formatDuration(duration), color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium, maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** Style Media3's own HUD without replacing its transport, submenu, key or timeout handling. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerView.styleProjectionHud(colors: ColorScheme) {
    val controller = findViewById<PlayerControlView>(Media3R.id.exo_controller) ?: return
    fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()
    fun roundFill(color: Color, radiusDp: Int) = GradientDrawable().apply {
        setColor(color.toArgb())
        cornerRadius = dpToPx(radiusDp).toFloat()
    }

    controller.findViewById<View>(Media3R.id.exo_controls_background)?.background = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.Transparent.toArgb(), colors.scrim.copy(alpha = 0.32f).toArgb(), colors.scrim.copy(alpha = 0.80f).toArgb())
    )
    controller.findViewById<View>(Media3R.id.exo_bottom_bar)?.background = roundFill(colors.surfaceContainer, 16)
    controller.findViewById<View>(Media3R.id.exo_center_controls)?.apply {
        background = roundFill(colors.surfaceContainer, 28)
        // Media3's own symmetrical center controls and hit targets remain in place.
        val pad = dpToPx(8)
        setPadding(pad, pad, pad, pad)
    }
    controller.findViewById<ImageView>(Media3R.id.exo_play_pause)?.apply {
        background = RippleDrawable(
            ColorStateList.valueOf(colors.onPrimary.copy(alpha = 0.16f).toArgb()),
            roundFill(colors.primary, 28), null
        )
        imageTintList = ColorStateList.valueOf(colors.onPrimary.toArgb())
        val pad = dpToPx(12)
        setPadding(pad, pad, pad, pad)
    }
    controller.findViewById<DefaultTimeBar>(Media3R.id.exo_progress)?.apply {
        setPlayedColor(colors.primary.toArgb())
        setScrubberColor(colors.primary.toArgb())
        setBufferedColor(colors.secondary.toArgb())
        setUnplayedColor(colors.outline.toArgb())
    }
    controller.findViewById<TextView>(Media3R.id.exo_position)?.setTextColor(colors.primary.toArgb())
    controller.findViewById<TextView>(Media3R.id.exo_duration)?.setTextColor(colors.onSurfaceVariant.toArgb())
    findViewById<ProgressBar>(Media3R.id.exo_buffering)?.apply {
        indeterminateTintList = ColorStateList.valueOf(colors.primary.toArgb())
        background = roundFill(colors.surfaceContainer, 28)
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
