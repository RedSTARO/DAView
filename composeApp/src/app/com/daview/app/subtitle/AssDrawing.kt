package com.daview.app.subtitle

/** One step of an ASS drawing, already divided down by the `\pN` scale. */
sealed interface AssDrawCommand {
    data class MoveTo(val x: Float, val y: Float) : AssDrawCommand
    data class LineTo(val x: Float, val y: Float) : AssDrawCommand
    data class CubicTo(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x3: Float, val y3: Float
    ) : AssDrawCommand

    data object Close : AssDrawCommand
}

/** The bounding box of a drawing, in the same coordinates as its commands. */
data class AssBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val EMPTY = AssBounds(0f, 0f, 0f, 0f)
    }
}

/**
 * The `\p` drawing language.
 *
 * These are the events media3 cannot represent at all — its subtitle model has
 * no shapes — and because the commands sit outside the `{...}` braces its
 * parser strips, they reach the screen as the literal text `m 12 0 b 6 4 5 5`.
 * A third of the typeset scripts in this library use them, and the ones that do
 * use them heavily: the example episode has 3440 drawings against 620 lines of
 * actual dialogue.
 */
object AssDrawing {

    /**
     * Parses [commands] into path steps.
     *
     * `\pN` divides the coordinates by `2^(N-1)`, so a drawing written at
     * `\p4` is authored eight times oversized for precision.
     */
    fun parse(commands: String, scale: Int): List<AssDrawCommand> {
        val divisor = if (scale > 1) (1 shl (scale - 1)).toFloat() else 1f
        val steps = ArrayList<AssDrawCommand>()
        val tokens = tokenize(commands)

        var index = 0
        var mode = ' '
        var currentX = 0f
        var currentY = 0f
        var started = false
        // A b-spline is written as a run of points that continues over `p`
        // commands, so it can only be emitted once its end is known.
        val spline = ArrayList<Pair<Float, Float>>()

        fun flushSpline(close: Boolean) {
            if (spline.size >= 4) {
                // A uniform b-spline does not pass through its first control
                // point, so the path joins the curve where it actually starts.
                val (sx0, sy0) = spline[0]
                val (sx1, sy1) = spline[1]
                val (sx2, sy2) = spline[2]
                steps.add(
                    AssDrawCommand.LineTo(
                        (sx0 + 4 * sx1 + sx2) / 6f,
                        (sy0 + 4 * sy1 + sy2) / 6f
                    )
                )
                for (i in 0..spline.size - 4) {
                    val (x0, y0) = spline[i]
                    val (x1, y1) = spline[i + 1]
                    val (x2, y2) = spline[i + 2]
                    val (x3, y3) = spline[i + 3]
                    // Uniform cubic b-spline segment as a Bézier.
                    steps.add(
                        AssDrawCommand.CubicTo(
                            (2 * x1 + x2) / 3f, (2 * y1 + y2) / 3f,
                            (x1 + 2 * x2) / 3f, (y1 + 2 * y2) / 3f,
                            (x1 + 4 * x2 + x3) / 6f, (y1 + 4 * y2 + y3) / 6f
                        )
                    )
                    currentX = (x1 + 4 * x2 + x3) / 6f
                    currentY = (y1 + 4 * y2 + y3) / 6f
                }
            }
            if (close && spline.isNotEmpty()) steps.add(AssDrawCommand.Close)
            spline.clear()
        }

        fun number(): Float? = tokens.getOrNull(index)?.toFloatOrNull()?.also { index++ }

        while (index < tokens.size) {
            val token = tokens[index]
            val letter = token.singleOrNull()?.lowercaseChar()
            if (letter != null && letter in "mnlbspc") {
                index++
                when (letter) {
                    'm', 'n' -> {
                        flushSpline(close = false)
                        // `m` starts a new closed figure; `n` moves without
                        // ending the one in progress.
                        if (letter == 'm' && started) steps.add(AssDrawCommand.Close)
                        val x = number() ?: break
                        val y = number() ?: break
                        currentX = x / divisor
                        currentY = y / divisor
                        steps.add(AssDrawCommand.MoveTo(currentX, currentY))
                        started = true
                        mode = letter
                    }

                    'c' -> flushSpline(close = true)

                    'p' -> mode = 'p'
                    else -> {
                        flushSpline(close = false)
                        mode = letter
                        if (letter == 's') spline.add(currentX to currentY)
                    }
                }
                continue
            }

            when (mode) {
                'l', 'm', 'n' -> {
                    // Coordinates that follow an `m` without another letter are
                    // more points of the same kind in some scripts; ASS says
                    // they continue the last command.
                    val x = number() ?: break
                    val y = number() ?: break
                    currentX = x / divisor
                    currentY = y / divisor
                    if (mode == 'l') {
                        steps.add(AssDrawCommand.LineTo(currentX, currentY))
                    } else {
                        steps.add(AssDrawCommand.MoveTo(currentX, currentY))
                    }
                }

                'b' -> {
                    val x1 = number() ?: break
                    val y1 = number() ?: break
                    val x2 = number() ?: break
                    val y2 = number() ?: break
                    val x3 = number() ?: break
                    val y3 = number() ?: break
                    currentX = x3 / divisor
                    currentY = y3 / divisor
                    steps.add(
                        AssDrawCommand.CubicTo(
                            x1 / divisor, y1 / divisor,
                            x2 / divisor, y2 / divisor,
                            currentX, currentY
                        )
                    )
                }

                's', 'p' -> {
                    val x = number() ?: break
                    val y = number() ?: break
                    spline.add(x / divisor to y / divisor)
                }

                else -> index++   // a stray number before any command
            }
        }
        flushSpline(close = false)
        if (started) steps.add(AssDrawCommand.Close)
        return steps
    }

    /**
     * The box the steps occupy.
     *
     * Bézier control points are included rather than solved for, so the box can
     * be a little larger than the ink. It decides where a drawing sits inside
     * its line, and an over-estimate moves a shape by a pixel; solving the
     * cubics exactly is not worth that.
     */
    fun bounds(steps: List<AssDrawCommand>): AssBounds {
        if (steps.isEmpty()) return AssBounds.EMPTY
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        fun include(x: Float, y: Float) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        for (step in steps) {
            when (step) {
                is AssDrawCommand.MoveTo -> include(step.x, step.y)
                is AssDrawCommand.LineTo -> include(step.x, step.y)
                is AssDrawCommand.CubicTo -> {
                    include(step.x1, step.y1)
                    include(step.x2, step.y2)
                    include(step.x3, step.y3)
                }

                AssDrawCommand.Close -> Unit
            }
        }
        return if (left > right) AssBounds.EMPTY else AssBounds(left, top, right, bottom)
    }

    private fun tokenize(commands: String): List<String> {
        val tokens = ArrayList<String>()
        val token = StringBuilder()
        for (ch in commands) {
            when {
                ch.isWhitespace() -> {
                    if (token.isNotEmpty()) {
                        tokens.add(token.toString())
                        token.setLength(0)
                    }
                }

                ch.isLetter() -> {
                    if (token.isNotEmpty()) {
                        tokens.add(token.toString())
                        token.setLength(0)
                    }
                    tokens.add(ch.toString())
                }

                else -> token.append(ch)
            }
        }
        if (token.isNotEmpty()) tokens.add(token.toString())
        return tokens
    }
}
