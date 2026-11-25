@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.daview.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// Insets are based on the pane, not the window: a wide window can hold two compact panes.
internal fun pageMargin(paneWidth: androidx.compose.ui.unit.Dp) = if (paneWidth < 600.dp) 16.dp else 24.dp

@Composable
internal fun AppMark(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            val ink = LocalContentColor.current
            // Same screen-and-play silhouette as the platform launcher mark.
            Canvas(Modifier.size(24.dp)) {
                val unit = size.minDimension / 24f
                drawRoundRect(ink, Offset(2 * unit, 3 * unit), Size(20 * unit, 18 * unit),
                    CornerRadius(3 * unit), style = Stroke(1.7f * unit))
                drawPath(Path().apply {
                    moveTo(10 * unit, 8 * unit)
                    lineTo(17 * unit, 12 * unit)
                    lineTo(10 * unit, 16 * unit)
                    close()
                }, ink)
            }
        }
    }
}

@Composable
internal fun PrimaryPageAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    largeTarget: Boolean = true
) {
    val height = if (largeTarget) ButtonDefaults.MediumContainerHeight else ButtonDefaults.MinHeight
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = height),
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (largeTarget) 24.dp else 20.dp))
            Text(label, style = if (largeTarget) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.labelLargeEmphasized)
        }
    }
}
