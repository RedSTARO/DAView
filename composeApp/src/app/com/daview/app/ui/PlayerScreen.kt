package com.daview.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daview.app.data.AppState
import com.daview.app.data.PlaybackController
import com.daview.app.data.UpNext
import com.daview.app.platform.PlatformInfo
import com.daview.app.platform.copyToClipboard
import com.daview.app.theme.DaViewTheme
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(state: AppState, playback: PlaybackController) {
    val info = playback.info
    val upNext = playback.upNext
    val external = playback.externalPlayerLabel != null

    // What the viewer set on the player — the rotation lock, the subtitle delay
    // — lives here, one level above the player, because the player itself is
    // built anew for every episode.
    val screen = remember { PlayerScreenState() }

    // Presentation belongs to the screen, not to one episode's player: the
    // hand-over to the next episode used to drop out of full screen and back.
    if (!external) PlaybackPresentation(screen.orientation, fullscreen = state.autoFullscreen)

    // A light library still opens into a dark projection room. Keep the actual picture matte black.
    DaViewTheme(darkTheme = true) {
        val matte = if (external) MaterialTheme.colorScheme.surface else Color.Black
        Box(Modifier.fillMaxSize().background(matte)) {
            val inPip = LocalAppWindowInfo.current.isInPictureInPicture
            when {
                // The countdown still runs in PiP, but never draws a minuscule card in the tile.
                upNext != null && !playback.starting -> UpNextCard(playback, upNext, showCard = !inPip)
                info == null -> if (!inPip) PlayerAuxiliaryPane {
                    if (playback.starting) Switching(playback.startingName) else PlayerIdle { state.back() }
                } else Unit
                external || !PlatformInfo.hasInternalPlayer -> PlayerAuxiliaryPane(foldOwner = info.sessionId) {
                    ExternalPlaybackPanel(state, playback)
                }
                playback.starting -> if (!inPip) PlayerAuxiliaryPane { Switching(playback.startingName) } else Unit
                // One engine per session, not per window size, layout or posture.
                else -> key(info.sessionId) {
                    InternalPlayer(
                        info = info,
                        screen = screen,
                        subtitleScale = state.subtitleScale,
                        onProgress = { position, paused, audio, subtitle ->
                            playback.reportProgress(position, paused, audio, subtitle)
                        },
                        onClose = { position -> playback.closePlayer(info.sessionId, position) },
                        // Closing the player and reaching the end of the file are different events.
                        onEnded = { position -> playback.onEnded(info.sessionId, position) },
                        onSkip = { itemId, position ->
                            // Another episode starts at its beginning; retrying this one carries on.
                            playback.skipTo(itemId, fromStart = itemId != info.item.id, positionMs = position)
                        }
                    )
                }
            }
        }
    }
}

internal data class PlayerPanes(val picture: WindowPane, val tabletopControls: WindowPane? = null) {
    val controls: WindowPane get() = tabletopControls ?: picture
}

/**
 * All rectangles are in window coordinates. Partition at the real divider; evaluate candidates
 * after insets, without applying a window-edge inset again along the hinge edge. A screen with
 * no known video (for example Up Next) only needs a single contiguous pane.
 */
internal fun playerPanes(
    viewport: WindowPane,
    fold: AppFold?,
    rtl: Boolean = false,
    pictureBounds: WindowPane = viewport,
    controlBounds: WindowPane = viewport,
    videoAspect: Float? = null
): PlayerPanes {
    if (fold == null || !fold.dividesWindow || fold.left >= viewport.right || fold.right <= viewport.left ||
        fold.top >= viewport.bottom || fold.bottom <= viewport.top
    ) return PlayerPanes(viewport)

    val vertical = fold.axis == FoldAxis.Vertical
    val before = if (vertical) viewport.copy(right = fold.left.coerceIn(viewport.left, viewport.right))
    else viewport.copy(bottom = fold.top.coerceIn(viewport.top, viewport.bottom))
    val after = if (vertical) viewport.copy(left = fold.right.coerceIn(viewport.left, viewport.right))
    else viewport.copy(top = fold.bottom.coerceIn(viewport.top, viewport.bottom))
    val aspect = videoAspect?.takeIf { it.isFinite() && it > 0f }
    val beforePicture = before.clampTo(pictureBounds).fitVideo(aspect)
    val afterPicture = after.clampTo(pictureBounds).fitVideo(aspect)
    val beforeControls = before.clampTo(controlBounds)
    val afterControls = after.clampTo(controlBounds)

    if (!vertical && fold.posture == FoldPosture.HalfOpened && aspect != null &&
        minOf(beforePicture.first, beforePicture.second) >= 96.dp &&
        maxOf(beforePicture.first, beforePicture.second) >= 160.dp &&
        // Four 48dp targets including Back, plus 16dp side padding; the seek strip
        // must also fit before any optional toolbar content in the scrollable deck.
        afterControls.width >= 224.dp && afterControls.height >= 144.dp
    ) return PlayerPanes(before, tabletopControls = after)

    val beforeActions = beforeControls.hasRoomForPlayerActions()
    val afterActions = afterControls.hasRoomForPlayerActions()
    val beforeArea = beforePicture.first.value * beforePicture.second.value
    val afterArea = afterPicture.first.value * afterPicture.second.value
    val beforeControlArea = beforeControls.width.value * beforeControls.height.value
    val afterControlArea = afterControls.width.value * afterControls.height.value
    val useBefore = when {
        beforeActions != afterActions -> beforeActions
        beforeArea != afterArea -> beforeArea > afterArea
        beforeControlArea != afterControlArea -> beforeControlArea > afterControlArea
        else -> !vertical || !rtl // Prefer reading-start on a vertical tie; never mirror the hinge.
    }
    return PlayerPanes(if (useBefore) before else after)
}

internal fun WindowPane.clampTo(bounds: WindowPane): WindowPane = WindowPane(
    left = bounds.left.coerceIn(left, right),
    top = bounds.top.coerceIn(top, bottom),
    right = bounds.right.coerceIn(left, right),
    bottom = bounds.bottom.coerceIn(top, bottom)
)

private fun WindowPane.fitVideo(aspect: Float?): Pair<Dp, Dp> =
    if (aspect == null) width to height else minOf(width, height * aspect) to minOf(height, width / aspect)

private fun WindowPane.hasRoomForPlayerActions(): Boolean =
    width >= 96.dp && height >= 48.dp || width >= 48.dp && height >= 96.dp

/** Position absolutely in window coordinates so RTL cannot mirror the hinge. */
internal fun Modifier.playerPane(pane: WindowPane, viewport: WindowPane, arrival: FoldArrival? = null): Modifier {
    val bounds = absoluteOffset(x = pane.left - viewport.left, y = pane.top - viewport.top)
        .width(pane.width).height(pane.height)
    // The default path also serves the native picture; keep its original clip modifier.
    return if (arrival == null) bounds.clipToBounds() else bounds.clipWithFoldArrival(arrival)
}

/** A paint-only cue on the safe controls edge, even when the HUD is hidden. */
internal fun Modifier.playerArrivalEdge(arrival: FoldArrival?): Modifier {
    if (arrival == null) return this
    return drawWithContent {
        drawContent()
        val remaining = 1f - arrival.progress.value
        if (remaining > 0f) {
            drawRect(
                color = Color.White,
                size = Size(size.width, minOf(1.5.dp.toPx(), size.height)),
                alpha = 0.7f * remaining
            )
        }
    }
}

@Composable
private fun PlayerAuxiliaryPane(foldOwner: Any? = null, content: @Composable () -> Unit) {
    val appWindow = LocalAppWindowInfo.current
    val fold = appWindow.fold.takeUnless { appWindow.isInPictureInPicture }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Keep the same content slot when only the pane changes (e.g. Up Next retains focus).
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .onGloballyPositioned { origin = it.positionInWindow() },
        contentAlignment = AbsoluteAlignment.TopLeft
    ) {
        val viewport = with(density) {
            val left = origin.x.toDp()
            val top = origin.y.toDp()
            WindowPane(left, top, left + maxWidth, top + maxHeight)
        }
        val pane = playerPanes(viewport, fold, rtl).controls
        // This viewport already excludes safeDrawing (including the IME), so only
        // animate a reported fold here, not a size-only reflow.
        val arrival = foldOwner?.let { rememberFoldArrival(owner = it, plan = pane) }
        Box(Modifier.playerPane(pane, viewport, arrival), contentAlignment = Alignment.Center) { content() }
    }
}

/** For non-picture states only. Scroll the deck, not the pane that positions it at the fold. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerCard(maxWidth: Dp, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val margin = if (this.maxWidth >= 600.dp) 24.dp else if (this.maxWidth >= 280.dp) 16.dp else 8.dp
        val inset = if (this.maxWidth >= 420.dp) 24.dp else 16.dp
        Surface(
            modifier = Modifier.padding(margin).widthIn(max = maxWidth).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLargeIncreased,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(inset),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerIdle(onBack: () -> Unit) {
    PlayerCard(440.dp) {
        Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("没有正在播放的内容", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.semantics { heading() })
        Text("回到媒体库选择要播放的影片。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onBack, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("返回") }
    }
}

/** Between two episodes, while the next one is being opened. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Switching(name: String?) {
    Column(
        Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LoadingIndicator(modifier = Modifier.semantics { contentDescription = "正在打开视频" })
        Text(
            "正在打开",
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        name?.takeIf { it.isNotBlank() }?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
        }
    }
}

/** Colour and a round-to-compact spatial morph make play/pause the one obvious transport target. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackMainButton(
    showPause: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = if (showPause) "暂停" else "播放"
) {
    val corner by animateDpAsState(
        targetValue = if (showPause) 12.dp else 28.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "transport corner"
    )
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(width = 56.dp, height = 48.dp).pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(corner)
    ) {
        Icon(if (showPause) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = label)
    }
}

/** A surface-coloured control over video, or a selected/menu action on a transport surface. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    contained: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp).then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
        enabled = enabled,
        shape = if (active) MaterialTheme.shapes.medium else CircleShape,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = when {
                active -> scheme.secondaryContainer
                contained -> scheme.surfaceContainerHigh
                else -> Color.Transparent
            },
            contentColor = if (active) scheme.onSecondaryContainer else scheme.onSurfaceVariant
        )
    ) {
        Icon(icon, contentDescription = label)
    }
}

/** Playback controls and time always run left to right, regardless of the surrounding language. */
@Composable
internal fun PlaybackTimeDirection(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

/** Chapter actions are the one warm spot next to the violet transport. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SkipIntroButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "跳过片头") {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLargeEmphasized)
    }
}

/** Keep retry and exit reachable on small or folded panes as well as a desktop window. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackFailure(message: String, detail: String? = null, onRetry: () -> Unit, onClose: () -> Unit) {
    PlayerCard(520.dp) {
        val colors = MaterialTheme.colorScheme
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = colors.error)
        Text("无法播放", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.semantics { heading() })
        Text(message, style = MaterialTheme.typography.bodyMedium)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
        }
    }
}

/**
 * What comes next, with a count-down, instead of a cut straight into it: the
 * next episode can be started now or declined. After three episodes nobody
 * touched it waits to be asked.
 */
@Composable
private fun UpNextCard(playback: PlaybackController, upNext: UpNext, showCard: Boolean) {
    var remaining by remember(upNext) { mutableStateOf(upNext.startsAt?.let { ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(0) }) }
    LaunchedEffect(upNext) {
        val deadline = upNext.startsAt ?: return@LaunchedEffect
        while (System.currentTimeMillis() < deadline) {
            remaining = ((deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            delay(200)
        }
        playback.playUpNext(auto = true)
    }
    if (!showCard) return
    PlayerAuxiliaryPane(foldOwner = upNext.itemId) { UpNextControls(playback, upNext, remaining) }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpNextControls(playback: PlaybackController, upNext: UpNext, remaining: Long?) {
    val focus = remember { FocusRequester() }
    var shortcutFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        // When a button is focused it handles Space/Enter itself, including on key-up.
                        if (shortcutFocus) playback.playUpNext(auto = false)
                        shortcutFocus
                    }
                    Key.Escape, Key.Backspace -> { playback.cancelUpNext(); true }
                    else -> false
                }
            }
            .focusRequester(focus)
            .onFocusChanged { shortcutFocus = it.isFocused }
            .focusable()
    ) {
        PlayerCard(480.dp) {
            val scheme = MaterialTheme.colorScheme
            Surface(color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer, shape = CircleShape) {
                Text(
                    if (upNext.stillWatching) "还在看吗？" else "下一集",
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            Text(
                upNext.name,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                when {
                    upNext.stillWatching -> "已经连续自动播放了好几集，按下面的按钮接着看。"
                    else -> "${remaining ?: 0} 秒后自动播放"
                },
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            FlowRow(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = { playback.cancelUpNext() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (upNext.stillWatching) "不看了" else "取消")
                }
                Button(onClick = { playback.playUpNext(auto = false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (upNext.stillWatching) "继续看" else "立即播放", style = MaterialTheme.typography.labelLargeEmphasized)
                }
            }
        }
    }
}

/**
 * Shown while an external player has the file. Position comes from the byte
 * ranges the player asks for — close, not exact, and the panel says so.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExternalPlaybackPanel(state: AppState, playback: PlaybackController) {
    val info = playback.info ?: return
    val session = playback.externalSession
    val label = playback.externalPlayerLabel ?: "外部播放器"

    PlayerCard(560.dp) {
        val colors = MaterialTheme.colorScheme
        val item = info.item
        val artwork = item.posterUrl ?: item.backdropUrl
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // An identifying thumbnail, not a second hero. Omit it where it crowds the title.
            val showArt = maxWidth >= 300.dp && artwork != null
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (showArt) {
                    Box(Modifier.size(width = 72.dp, height = 104.dp).clip(MaterialTheme.shapes.medium)) {
                        ArtworkPlaceholder(item.kind, Modifier.matchParentSize())
                        AsyncImage(
                            model = artwork, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("正在通过 $label 播放", style = MaterialTheme.typography.labelLarge, color = colors.tertiary)
                    Text(
                        item.name, style = MaterialTheme.typography.headlineSmallEmphasized,
                        modifier = Modifier.semantics { heading() }
                    )
                    item.seriesName?.let { series ->
                        Text(
                            listOfNotNull(series, item.episodeLabel).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }

        val runtime = session?.runtimeMs ?: info.runtimeMs
        val position = session?.positionMs ?: info.startPositionMs
        Surface(
            color = colors.surfaceContainerHigh, contentColor = colors.onSurface,
            shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("播放进度", style = MaterialTheme.typography.labelLargeEmphasized)
                PlaybackTimeDirection {
                    LinearProgressIndicator(
                        progress = {
                            if (runtime != null && runtime > 0) (position.toDouble() / runtime).toFloat().coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "外部播放器进度" }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (position > 0) formatDuration(position) else "0:00", style = MaterialTheme.typography.labelMediumEmphasized)
                        Text(formatDuration(runtime), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
                Text(
                    when (session?.positionSource) {
                        "client" -> "进度由播放器上报"
                        "cue+clock", "clock" -> "进度是估算的，可能差几秒；暂停时这里仍会走动"
                        else -> "等待 $label 开始播放…"
                    },
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Only offer returning to browse where the app can see when the external player exits.
            if (playback.canBrowseDuringExternal) {
                Button(onClick = { playback.leaveExternalPanel() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("返回浏览", style = MaterialTheme.typography.labelLargeEmphasized)
                }
            }
            OutlinedButton(
                onClick = { playback.stopExternal(); state.back() },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (playback.canBrowseDuringExternal) "结束并关闭播放器" else "结束播放")
            }
        }
        Text(
            "关掉 $label 时进度会自动记下，并同步到其它设备。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
        )
        TextButton(
            onClick = {
                copyToClipboard(info.streamUrl)
                state.notify("已复制。这个地址只在本机、本次播放期间有效")
            },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "复制播放地址，给别的播放器用" },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("复制播放地址")
            }
        }
    }
}
