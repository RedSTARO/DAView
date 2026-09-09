package com.daview.app.subtitle

import android.graphics.BlurMaskFilter
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.IdentityHashMap
import kotlin.math.sqrt

/**
 * Draws an ASS script onto a canvas over the video.
 *
 * The geometry follows what libass actually does, checked against it rather
 * than remembered: a line's box has its bottom edge at `PlayResY - MarginV`
 * with the baseline one descent above; a drawing is placed by its bounding box,
 * so `\an7\pos(300,300)` puts the box's top-left corner on the position; and
 * two lines that would overlap are moved apart only when they share a layer,
 * which is what lets the dual-language scripts in this library put the Chinese
 * on layer 0 and the Japanese on layer 1 and have both stay where they were
 * put.
 */
class AssRenderer(private val fonts: AssFonts) {

    var script: AssScript? = null
        set(value) {
            field = value
            lines.clear()
            drawings.clear()
        }

    /** Shifts every event, for a script that does not line up with the video. */
    var delayMs: Long = 0

    private val lines = IdentityHashMap<AssEvent, AssParsedLine>()
    private val drawings = HashMap<String, List<AssDrawCommand>>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val matrix = Matrix()
    private val camera = Camera()
    private val clipPath = Path()

    private val collisions = AssCollisions()

    fun hasContent(): Boolean = script?.events?.isNotEmpty() == true

    fun draw(canvas: Canvas, video: RectF, positionMs: Long) {
        val script = this.script ?: return
        if (video.width() <= 0f || video.height() <= 0f) return
        val time = positionMs - delayMs
        val events = script.eventsAt(time)
        if (events.isEmpty()) return

        val scaleX = video.width() / script.playResX
        val scaleY = video.height() / script.playResY
        collisions.reset()

        for (event in events) {
            val line = lines.getOrPut(event) { AssLineParser.parse(event, script) }
            drawLine(canvas, script, line, time - event.startMs, video, scaleX, scaleY)
        }
    }

    private fun drawLine(
        canvas: Canvas,
        script: AssScript,
        line: AssParsedLine,
        elapsedMs: Long,
        video: RectF,
        scaleX: Float,
        scaleY: Float
    ) {
        if (line.runs.isEmpty()) return
        val fade = line.fade?.opacityAt(elapsedMs) ?: 255
        if (fade <= 0) return

        val measured = line.runs.map { measure(it, line, elapsedMs, script, scaleX, scaleY) }
        val available = (script.playResX - line.marginL - line.marginR) * scaleX
        val rows = layout(measured, available, line.wrapStyle)
        if (rows.isEmpty()) return

        var blockWidth = 0f
        var blockHeight = 0f
        for (row in rows) {
            blockWidth = maxOf(blockWidth, row.width)
            blockHeight += row.ascent + row.descent
        }

        val position = line.positionAt(elapsedMs)
        val anchor = AssLayout.anchor(
            alignment = line.alignment,
            position = position,
            width = blockWidth / scaleX,
            height = blockHeight / scaleY,
            marginL = line.marginL,
            marginR = line.marginR,
            marginV = line.marginV,
            playResX = script.playResX,
            playResY = script.playResY
        )
        var top = video.top + anchor.y * scaleY
        val left = video.left + anchor.x * scaleX
        // Only unpositioned lines are moved out of each other's way.
        if (position == null) {
            top = collisions.place(line.event.layer, line.alignment, top, blockHeight)
        }

        // Rotations turn about \org when there is one, and about the line's own
        // anchor otherwise.
        val originX = line.origin?.let { video.left + it.x * scaleX } ?: (left + blockWidth / 2f)
        val originY = line.origin?.let { video.top + it.y * scaleY } ?: (top + blockHeight / 2f)

        val clip = line.clip
        if (clip != null) {
            canvas.save()
            applyClip(canvas, clip, video, scaleX, scaleY)
        }

        var rowTop = top
        for (row in rows) {
            val baseline = rowTop + row.ascent
            val rowLeft = when (AssLayout.horizontal(line.alignment)) {
                0 -> left
                1 -> left + (blockWidth - row.width) / 2f
                else -> left + (blockWidth - row.width)
            }
            drawRow(canvas, row, rowLeft, baseline, originX, originY, fade, scaleX, scaleY)
            rowTop += row.ascent + row.descent
        }

        if (clip != null) canvas.restore()
    }

    private fun drawRow(
        canvas: Canvas,
        row: Row,
        rowLeft: Float,
        baseline: Float,
        originX: Float,
        originY: Float,
        fade: Int,
        scaleX: Float,
        scaleY: Float
    ) {
        // Shadow, then outline, then fill, over the whole row: an outline drawn
        // per glyph would cut into the glyph beside it.
        for (pass in 0..2) {
            var penX = rowLeft
            for (piece in row.pieces) {
                val style = piece.style
                val transformed = applyTransform(canvas, style, originX, originY)
                val outline = maxOf(style.outlineX, style.outlineY) * scaleY
                when (pass) {
                    // The shadow is the whole shape — outline and interior —
                    // offset, not a second outline.
                    0 -> if (style.shadowX != 0f || style.shadowY != 0f) {
                        drawPiece(
                            canvas, piece, penX + style.shadowX * scaleX,
                            baseline + style.shadowY * scaleY,
                            style.backColour, fade, outline,
                            filled = true, blurred = true, scaleY
                        )
                    }

                    1 -> if (outline > 0f) {
                        drawPiece(
                            canvas, piece, penX, baseline, style.outlineColour, fade,
                            outline, filled = false, blurred = true, scaleY
                        )
                    }

                    // lur softens the border and leaves the letter itself
                    // sharp — the glow around crisp text that these songs are
                    // typeset with. Only a line with no border at all has its
                    // interior blurred, which is what libass does and the
                    // difference between a legible line and a smear.
                    else -> drawPiece(
                        canvas, piece, penX, baseline, fillColour(piece), fade,
                        stroke = 0f, filled = true, blurred = outline <= 0f, scaleY
                    )
                }
                if (transformed) canvas.restore()
                penX += piece.width
            }
        }
    }

    /** Which colour the text takes, once karaoke has had its say. */
    private fun fillColour(piece: Piece): Int {
        val karaoke = piece.run.karaoke ?: return piece.style.primaryColour
        return if (piece.karaokeElapsed >= karaoke.startMs) {
            piece.style.primaryColour
        } else {
            piece.style.secondaryColour
        }
    }

    private fun drawPiece(
        canvas: Canvas,
        piece: Piece,
        x: Float,
        baseline: Float,
        colour: Int,
        fade: Int,
        stroke: Float,
        filled: Boolean,
        blurred: Boolean,
        scaleY: Float
    ) {
        val alpha = ((colour ushr 24) and 0xFF) * fade / 255
        if (alpha == 0) return
        paint.reset()
        paint.isAntiAlias = true
        paint.color = (alpha shl 24) or (colour and 0x00FFFFFF)
        paint.maskFilter = if (blurred) blurFor(piece.style, scaleY) else null
        if (stroke > 0f) {
            paint.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            // A stroke straddles the outline, so it has to be twice as wide to
            // put the asked-for width outside the glyph.
            paint.strokeWidth = stroke * 2f
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
        } else {
            paint.style = Paint.Style.FILL
        }

        val drawing = piece.path
        if (drawing != null) {
            canvas.save()
            canvas.translate(x - piece.pathLeft, baseline - piece.pathTop)
            canvas.drawPath(drawing, paint)
            canvas.restore()
            return
        }

        paint.typeface = piece.typeface
        paint.textSize = piece.textSize
        paint.textSkewX = piece.textSkewX
        paint.textScaleX = piece.textScaleX
        paint.letterSpacing = piece.letterSpacing
        paint.isUnderlineText = piece.style.underline
        paint.isStrikeThruText = piece.style.strikeOut
        if (piece.style.borderStyle == 3 && stroke > 0f) {
            // An opaque box is a filled rectangle behind the line rather than
            // an outline around the glyphs.
            paint.style = Paint.Style.FILL
            canvas.drawRect(
                x, baseline - piece.ascent, x + piece.width, baseline + piece.descent, paint
            )
            return
        }
        canvas.drawText(piece.text, x, baseline, paint)
    }

    private fun blurFor(style: AssRunStyle, scale: Float): BlurMaskFilter? {
        // \blur is a Gaussian sigma; \be is a small box blur applied that many
        // times, whose equivalent sigma grows with the square root of the count.
        val sigma = style.blur * scale +
            if (style.blurEdges > 0) sqrt(style.blurEdges * 0.667f) * scale else 0f
        if (sigma <= 0.1f) return null
        // Skia's mask filter takes a radius, and derives sigma from it.
        val radius = (sigma - 0.5f) / 0.57735f
        if (radius <= 0f) return null
        return BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
    }

    /** Returns whether the canvas was saved and needs restoring. */
    private fun applyTransform(
        canvas: Canvas,
        style: AssRunStyle,
        originX: Float,
        originY: Float
    ): Boolean {
        val flat = style.angleZ == 0f && style.angleX == 0f && style.angleY == 0f
        if (flat) return false
        canvas.save()
        matrix.reset()
        if (style.angleX != 0f || style.angleY != 0f) {
            // ASS turns the other way about both of these than Camera does.
            camera.save()
            camera.rotateX(-style.angleX)
            camera.rotateY(style.angleY)
            camera.getMatrix(matrix)
            camera.restore()
            matrix.preTranslate(-originX, -originY)
            matrix.postTranslate(originX, originY)
        }
        if (style.angleZ != 0f) {
            val rotation = Matrix()
            rotation.setRotate(-style.angleZ, originX, originY)
            matrix.postConcat(rotation)
        }
        canvas.concat(matrix)
        return true
    }

    private fun applyClip(canvas: Canvas, clip: AssClip, video: RectF, scaleX: Float, scaleY: Float) {
        clipPath.reset()
        when (clip) {
            is AssClip.Rect -> clipPath.addRect(
                video.left + clip.x1 * scaleX, video.top + clip.y1 * scaleY,
                video.left + clip.x2 * scaleX, video.top + clip.y2 * scaleY,
                Path.Direction.CW
            )

            is AssClip.Drawing -> {
                val steps = drawings.getOrPut("${clip.scale}|${clip.commands}") {
                    AssDrawing.parse(clip.commands, clip.scale)
                }
                buildPath(clipPath, steps, scaleX, scaleY)
                clipPath.offset(video.left, video.top)
            }
        }
        if (clip.inverse) {
            val whole = Path()
            whole.addRect(video, Path.Direction.CW)
            whole.op(clipPath, Path.Op.DIFFERENCE)
            canvas.clipPath(whole)
        } else {
            canvas.clipPath(clipPath)
        }
    }

    private fun buildPath(target: Path, steps: List<AssDrawCommand>, scaleX: Float, scaleY: Float) {
        for (step in steps) {
            when (step) {
                is AssDrawCommand.MoveTo -> target.moveTo(step.x * scaleX, step.y * scaleY)
                is AssDrawCommand.LineTo -> target.lineTo(step.x * scaleX, step.y * scaleY)
                is AssDrawCommand.CubicTo -> target.cubicTo(
                    step.x1 * scaleX, step.y1 * scaleY,
                    step.x2 * scaleX, step.y2 * scaleY,
                    step.x3 * scaleX, step.y3 * scaleY
                )

                AssDrawCommand.Close -> target.close()
            }
        }
    }

    // --- measurement -------------------------------------------------------

    private class Piece(
        val run: AssRun,
        val style: AssRunStyle,
        val text: String,
        val width: Float,
        val ascent: Float,
        val descent: Float,
        val typeface: Typeface?,
        val textSize: Float,
        val textScaleX: Float,
        val textSkewX: Float,
        val letterSpacing: Float,
        val path: Path?,
        val pathLeft: Float,
        val pathTop: Float,
        val karaokeElapsed: Long
    )

    private class Row(val pieces: MutableList<Piece>) {
        var width: Float = 0f
        var ascent: Float = 0f
        var descent: Float = 0f
    }

    private fun measure(
        run: AssRun,
        line: AssParsedLine,
        elapsedMs: Long,
        script: AssScript,
        scaleX: Float,
        scaleY: Float
    ): Piece {
        val style = AssLineParser.styleAt(run, elapsedMs, line.style)
        // Border and shadow are in script units unless the script says its
        // author measured them in output pixels.
        val borderScale = if (script.scaledBorderAndShadow) 1f else 1f / scaleY

        if (run.isDrawing) {
            val steps = drawings.getOrPut("${style.drawingScale}|${run.text}") {
                AssDrawing.parse(run.text, style.drawingScale)
            }
            val bounds = AssDrawing.bounds(steps)
            val drawScaleX = scaleX * style.scaleX / 100f
            val drawScaleY = scaleY * style.scaleY / 100f
            val shape = Path()
            buildPath(shape, steps, drawScaleX, drawScaleY)
            return Piece(
                run = run,
                style = style.copy(
                    outlineX = style.outlineX * borderScale,
                    outlineY = style.outlineY * borderScale
                ),
                text = "",
                width = bounds.width * drawScaleX,
                ascent = -bounds.top * drawScaleY,
                descent = bounds.bottom * drawScaleY,
                typeface = null,
                textSize = 0f,
                textScaleX = 1f,
                textSkewX = 0f,
                letterSpacing = 0f,
                path = shape,
                pathLeft = bounds.left * drawScaleX,
                pathTop = bounds.top * drawScaleY,
                karaokeElapsed = elapsedMs
            )
        }

        val typeface = fonts.typeface(style.fontName, style.bold, style.italic)
        val textSize = style.fontSize * scaleY * style.scaleY / 100f
        // The vertical scale is in the size; this is what is left over.
        val horizontal = if (textSize > 0f) {
            (scaleX * style.scaleX) / (scaleY * style.scaleY)
        } else {
            1f
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = typeface
        paint.textSize = textSize
        paint.textScaleX = horizontal
        paint.letterSpacing = if (textSize > 0f) style.spacing * scaleX / textSize else 0f
        // \fax shears along x; a text skew is the tangent of the angle, which is
        // what ASS stores.
        paint.textSkewX = -style.shearX
        val metrics = paint.fontMetrics
        return Piece(
            run = run,
            style = style.copy(
                outlineX = style.outlineX * borderScale,
                outlineY = style.outlineY * borderScale
            ),
            text = run.text,
            width = paint.measureText(run.text),
            ascent = -metrics.ascent,
            descent = metrics.descent,
            typeface = typeface,
            textSize = textSize,
            textScaleX = horizontal,
            textSkewX = paint.textSkewX,
            letterSpacing = paint.letterSpacing,
            path = null,
            pathLeft = 0f,
            pathTop = 0f,
            karaokeElapsed = elapsedMs
        )
    }

    /** Splits the runs into rows at the explicit breaks and then at the margin. */
    private fun layout(pieces: List<Piece>, available: Float, wrapStyle: Int): List<Row> {
        val rows = ArrayList<Row>()
        var row = Row(ArrayList())
        for (piece in pieces) {
            if (piece.run.breakBefore && row.pieces.isNotEmpty()) {
                rows.add(row)
                row = Row(ArrayList())
            }
            row.pieces.add(piece)
        }
        if (row.pieces.isNotEmpty()) rows.add(row)

        val wrapped = if (wrapStyle == 2) rows else rows.flatMap { wrap(it, available, wrapStyle) }
        for (each in wrapped) {
            each.width = each.pieces.sumOf { it.width.toDouble() }.toFloat()
            each.ascent = each.pieces.maxOf { it.ascent }
            each.descent = each.pieces.maxOf { it.descent }
        }
        return wrapped
    }

    private fun wrap(row: Row, available: Float, wrapStyle: Int): List<Row> {
        if (available <= 0f) return listOf(row)
        val total = row.pieces.sumOf { it.width.toDouble() }.toFloat()
        if (total <= available) return listOf(row)

        // Words are measured with the space that precedes them, so a break
        // between two of them costs nothing and the arithmetic stays simple.
        val units = ArrayList<Piece>()
        for (piece in row.pieces) {
            if (piece.path != null || piece.text.isEmpty()) {
                units.add(piece)
            } else {
                units.addAll(split(piece))
            }
        }
        val breaks = AssLayout.wrap(units.map { it.width }, 0f, available, wrapStyle).toSet()
        if (breaks.isEmpty()) return listOf(row)

        val out = ArrayList<Row>()
        var current = Row(ArrayList())
        for ((index, unit) in units.withIndex()) {
            if (index in breaks && current.pieces.isNotEmpty()) {
                out.add(current)
                current = Row(ArrayList())
            }
            // The space that separated two words is what the break consumes.
            val text = if (current.pieces.isEmpty() && unit.path == null) {
                unit.text.trimStart(' ')
            } else {
                unit.text
            }
            current.pieces.add(if (text == unit.text) unit else retext(unit, text))
        }
        if (current.pieces.isNotEmpty()) out.add(current)
        return out
    }

    /** Break opportunities: after a space, and on either side of a CJK glyph. */
    private fun split(piece: Piece): List<Piece> {
        val text = piece.text
        val units = ArrayList<Piece>()
        var start = 0
        for (index in 1 until text.length) {
            val previous = text[index - 1]
            val current = text[index]
            val breakable = (previous == ' ' && current != ' ') ||
                (isWide(current) && previous != ' ') ||
                (isWide(previous) && current != ' ')
            if (breakable) {
                units.add(retext(piece, text.substring(start, index)))
                start = index
            }
        }
        units.add(retext(piece, text.substring(start)))
        return units
    }

    private fun isWide(ch: Char): Boolean {
        val code = ch.code
        return code in 0x2E80..0x9FFF ||    // CJK radicals through unified ideographs
            code in 0xAC00..0xD7AF ||       // Hangul
            code in 0xF900..0xFAFF ||       // compatibility ideographs
            code in 0xFF01..0xFF60          // full-width forms
    }

    private fun retext(piece: Piece, text: String): Piece {
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = piece.typeface
        paint.textSize = piece.textSize
        paint.textScaleX = piece.textScaleX
        paint.letterSpacing = piece.letterSpacing
        paint.textSkewX = piece.textSkewX
        return Piece(
            run = piece.run,
            style = piece.style,
            text = text,
            width = paint.measureText(text),
            ascent = piece.ascent,
            descent = piece.descent,
            typeface = piece.typeface,
            textSize = piece.textSize,
            textScaleX = piece.textScaleX,
            textSkewX = piece.textSkewX,
            letterSpacing = piece.letterSpacing,
            path = null,
            pathLeft = 0f,
            pathTop = 0f,
            karaokeElapsed = piece.karaokeElapsed
        )
    }

}
