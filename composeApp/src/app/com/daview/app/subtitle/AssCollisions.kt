package com.daview.app.subtitle

/**
 * Keeps lines that were not given a position from landing on one another.
 *
 * The rule is libass's, established by rendering the cases through it rather
 * than from the documentation, which does not say: two lines are moved apart
 * only when they are on the same layer. Two bottom-aligned lines of one layer
 * that would occupy the same space are stacked, the later one moving away from
 * the edge it is aligned to; the same two lines on different layers are drawn
 * over each other exactly where they were put.
 *
 * That distinction is what the dual-language scripts in this library depend on.
 * They put the translation on layer 0 and the original on layer 1, both aligned
 * to the bottom with margins that overlap, and expect both to stay put.
 */
class AssCollisions {

    private val byLayer = HashMap<Int, MutableList<FloatArray>>()

    fun reset() = byLayer.clear()

    /**
     * The top edge [top] should actually use for a block of [height], having
     * moved it clear of what is already on [layer].
     *
     * Bottom-aligned lines move up and everything else moves down, so a line
     * displaced by another still grows away from the edge it was aligned to.
     */
    fun place(layer: Int, alignment: Int, top: Float, height: Float): Float {
        val spans = byLayer.getOrPut(layer) { ArrayList() }
        val upwards = AssLayout.vertical(alignment) == 0
        var y = top
        var moved = true
        var guard = 0
        // One pass can push the line into a span it had already cleared, so it
        // repeats; the guard is for a script that would otherwise never settle.
        while (moved && guard++ < 64) {
            moved = false
            for (span in spans) {
                if (y < span[1] && y + height > span[0]) {
                    y = if (upwards) span[0] - height else span[1]
                    moved = true
                }
            }
        }
        spans.add(floatArrayOf(y, y + height))
        return y
    }
}
