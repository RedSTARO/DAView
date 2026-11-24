package com.daview.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Space above a pane's safe content that is available for drawing, never for input. */
internal val LocalBrowseTopOverlap = compositionLocalOf { 0.dp }

internal fun Modifier.browseSafeTop(overlap: Dp): Modifier = if (overlap > 0.dp) {
    padding(top = overlap).consumeWindowInsets(PaddingValues(top = overlap))
} else this

/**
 * Render just the live hero's artwork behind its list. Only the first row can bleed into the
 * space above the viewport; text, scroll and touch still belong to the clipped, safe list.
 * The measured row is the source of truth for both size and position, even as it scrolls away.
 */
@Composable
internal fun BrowseHeroBackdrop(
    list: LazyListState,
    heroKey: String,
    topOverlap: Dp,
    modifier: Modifier,
    artwork: @Composable BoxScope.() -> Unit
) {
    Layout(
        content = { Box(content = artwork) },
        modifier = modifier.drawWithContent {
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull()
            val bleed = if (first?.index == 0 && first.key == heroKey) topOverlap.toPx() else 0f
            clipRect(top = -bleed) { this@drawWithContent.drawContent() }
        }
    ) { measurables, constraints ->
        val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == heroKey }
        val bleed = if (row?.index == 0) topOverlap.roundToPx() else 0
        val image = row?.takeIf { it.size > 0 && constraints.maxWidth > 0 }?.let {
            measurables.single().measure(Constraints.fixed(constraints.maxWidth, it.size + bleed))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (image != null && row != null) image.place(0, row.offset - bleed)
        }
    }
}
