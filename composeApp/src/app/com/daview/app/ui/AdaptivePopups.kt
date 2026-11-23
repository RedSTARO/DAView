package com.daview.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.daview.app.data.ModalMarker

@Composable
internal fun PaneMenu(
    pane: WindowPane,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    offset: DpOffset,
    content: @Composable ColumnScope.() -> Unit
) {
    Popup(
        popupPositionProvider = panePosition(pane, offset = offset),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        ModalMarker()
        Surface(
            modifier = Modifier.widthIn(max = minOf(280.dp, pane.width)).heightIn(max = pane.height)
                .then(modifier).clipToBounds(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 6.dp
        ) {
            val maxWidth = minOf(280.dp, pane.width)
            Column(
                Modifier.widthIn(min = minOf(112.dp, maxWidth), max = maxWidth)
                    .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                content = content
            )
        }
    }
}

/** A regular Material dialog unless centering it in the window would put it on a hinge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdaptiveAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    val pane = LocalBrowsePane.current
    if (pane == null) {
        AlertDialog(onDismissRequest, confirmButton, dismissButton = dismissButton, title = title, text = text)
        return
    }
    if (pane.width <= 0.dp || pane.height <= 0.dp) return
    Popup(
        popupPositionProvider = panePosition(pane, centered = true),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier.widthIn(max = minOf(520.dp, pane.width)).heightIn(max = pane.height)
                .clipToBounds().semantics { paneTitle = "对话框" },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (title != null) ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                if (text != null) Box(Modifier.weight(1f, fill = false)) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                }
                FlowRow(horizontalArrangement = Arrangement.End) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
private fun panePosition(pane: WindowPane, offset: DpOffset = DpOffset.Zero, centered: Boolean = false): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(pane, density, offset, centered) {
        with(density) {
            val bounds = IntRect(pane.left.roundToPx(), pane.top.roundToPx(), pane.right.roundToPx(), pane.bottom.roundToPx())
            val dx = offset.x.roundToPx()
            val dy = offset.y.roundToPx()
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize
                ): IntOffset {
                    // The popup may receive a smaller usable window during IME or
                    // system split transitions. Clamp in the current window's coordinates.
                    val left = bounds.left.coerceIn(0, windowSize.width)
                    val right = bounds.right.coerceIn(left, windowSize.width)
                    val top = bounds.top.coerceIn(0, windowSize.height)
                    val bottom = bounds.bottom.coerceIn(top, windowSize.height)
                    val width = popupContentSize.width
                    val height = popupContentSize.height
                    val x = when {
                        centered -> left + (right - left - width) / 2
                        layoutDirection == LayoutDirection.Rtl -> anchorBounds.right - dx - width
                        else -> anchorBounds.left + dx
                    }
                    val y = when {
                        centered -> top + (bottom - top - height) / 2
                        anchorBounds.bottom + dy + height <= bottom -> anchorBounds.bottom + dy
                        else -> anchorBounds.top - dy - height
                    }
                    return IntOffset(
                        x.coerceIn(left, (right - width).coerceAtLeast(left)),
                        y.coerceIn(top, (bottom - height).coerceAtLeast(top))
                    )
                }
            }
        }
    }
}
