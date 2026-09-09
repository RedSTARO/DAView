package com.daview.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/**
 * The window icon, drawn rather than shipped.
 *
 * Without one the title bar, the task bar, Alt+Tab and the installed shortcut
 * all show the JDK's default coffee cup. A painter costs no binary asset and
 * scales to whatever size the platform asks for, which is the one thing a
 * single bitmap could not do.
 */
object DaViewIcon : Painter() {

    private val Ground = Color(0xFF4B2CD6)
    private val Mark = Color(0xFFE8DEFF)

    override val intrinsicSize: Size get() = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val side = size.minDimension
        drawRoundRect(
            color = Ground,
            cornerRadius = CornerRadius(side * 0.22f)
        )
        // A play triangle on the optical centre — nudged right, because a
        // triangle centred by its bounding box reads as sitting too far left.
        val half = side * 0.17f
        val cx = size.width / 2f + side * 0.03f
        val cy = size.height / 2f
        drawPath(
            path = Path().apply {
                moveTo(cx - half, cy - half * 1.15f)
                lineTo(cx + half * 1.15f, cy)
                lineTo(cx - half, cy + half * 1.15f)
                close()
            },
            color = Mark
        )
        // A thin bar under it, so the mark still reads as "media" at 16px where
        // a bare triangle is just a triangle.
        drawRoundRect(
            color = Mark,
            topLeft = Offset(size.width / 2f - side * 0.22f, cy + side * 0.24f),
            size = Size(side * 0.44f, side * 0.055f),
            cornerRadius = CornerRadius(side * 0.03f)
        )
    }
}
