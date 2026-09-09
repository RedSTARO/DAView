package com.daview.app.subtitle

/** Where a block of text sits, in script coordinates. */
object AssLayout {

    /** 0 left, 1 centre, 2 right. */
    fun horizontal(alignment: Int): Int = (alignment.coerceIn(1, 9) - 1) % 3

    /** 0 bottom, 1 middle, 2 top. */
    fun vertical(alignment: Int): Int = (alignment.coerceIn(1, 9) - 1) / 3

    /**
     * The top-left corner of a block [width] × [height].
     *
     * With a `\pos`, the alignment says which point of the block lands on the
     * position. Without one, the block sits against its margins instead.
     */
    fun anchor(
        alignment: Int,
        position: AssPoint?,
        width: Float,
        height: Float,
        marginL: Int,
        marginR: Int,
        marginV: Int,
        playResX: Int,
        playResY: Int
    ): AssPoint {
        val x = if (position != null) {
            when (horizontal(alignment)) {
                0 -> position.x
                1 -> position.x - width / 2f
                else -> position.x - width
            }
        } else {
            when (horizontal(alignment)) {
                0 -> marginL.toFloat()
                1 -> marginL + (playResX - marginL - marginR - width) / 2f
                else -> playResX - marginR - width
            }
        }
        val y = if (position != null) {
            when (vertical(alignment)) {
                0 -> position.y - height
                1 -> position.y - height / 2f
                else -> position.y
            }
        } else {
            when (vertical(alignment)) {
                0 -> playResY - marginV - height
                1 -> (playResY - height) / 2f
                else -> marginV.toFloat()
            }
        }
        return AssPoint(x, y)
    }

    /**
     * Where to break a run of words that has to fit [maxWidth].
     *
     * Returns the index of the first word on each line after the first. Wrap
     * style 2 never breaks, style 1 fills each line before starting the next,
     * and styles 0 and 3 even the lines out — which is the default, and is why
     * a two-line subtitle normally reads as two halves rather than a full line
     * and a word.
     *
     * The evening-out minimises the squared slack left on each line, so it does
     * not reproduce libass's tie-breaks between styles 0 and 3 (which line ends
     * up the wider one when they cannot be equal). The break count and the
     * balance are the same.
     */
    fun wrap(widths: List<Float>, spaceWidth: Float, maxWidth: Float, wrapStyle: Int): List<Int> {
        if (widths.size <= 1 || maxWidth <= 0f) return emptyList()
        if (wrapStyle == 2) return emptyList()

        val greedy = greedyBreaks(widths, spaceWidth, maxWidth)
        if (wrapStyle == 1 || greedy.isEmpty()) return greedy

        // The greedy pass fixes how many lines are needed; the balanced pass
        // only decides where within them to break.
        return balancedBreaks(widths, spaceWidth, maxWidth, greedy.size + 1) ?: greedy
    }

    private fun greedyBreaks(widths: List<Float>, spaceWidth: Float, maxWidth: Float): List<Int> {
        val breaks = ArrayList<Int>()
        var lineWidth = 0f
        for (index in widths.indices) {
            val advance = if (lineWidth == 0f) widths[index] else spaceWidth + widths[index]
            if (lineWidth > 0f && lineWidth + advance > maxWidth) {
                breaks.add(index)
                lineWidth = widths[index]
            } else {
                lineWidth += advance
            }
        }
        return breaks
    }

    private fun balancedBreaks(
        widths: List<Float>,
        spaceWidth: Float,
        maxWidth: Float,
        lines: Int
    ): List<Int>? {
        val count = widths.size
        if (lines <= 1 || lines > count) return null

        fun lineWidth(from: Int, to: Int): Float {
            var total = 0f
            for (i in from until to) {
                total += widths[i]
                if (i > from) total += spaceWidth
            }
            return total
        }

        // cost[line][start] is the best achievable cost for laying out the words
        // from `start` onwards over `line` lines.
        val infinity = Float.MAX_VALUE / 4f
        val cost = Array(lines + 1) { FloatArray(count + 1) { infinity } }
        val choice = Array(lines + 1) { IntArray(count + 1) { -1 } }
        cost[0][count] = 0f
        for (line in 1..lines) {
            for (start in 0 until count) {
                for (end in start + 1..count) {
                    val width = lineWidth(start, end)
                    if (width > maxWidth && end > start + 1) break
                    val rest = cost[line - 1][end]
                    if (rest >= infinity) continue
                    val slack = maxWidth - width
                    val here = slack * slack + rest
                    if (here < cost[line][start]) {
                        cost[line][start] = here
                        choice[line][start] = end
                    }
                }
            }
        }
        if (cost[lines][0] >= infinity) return null

        val breaks = ArrayList<Int>()
        var start = 0
        for (line in lines downTo 1) {
            val end = choice[line][start]
            if (end <= 0) return null
            if (line > 1) breaks.add(end)
            start = end
        }
        return breaks
    }
}
