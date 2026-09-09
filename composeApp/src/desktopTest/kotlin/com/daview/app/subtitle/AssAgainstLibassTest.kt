package com.daview.app.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The numbers here were measured, not remembered.
 *
 * Each case was rendered through libmpv — that is, through libass — at
 * 1920×1080 against a `PlayResX/Y` of the same size, and the pixels that came
 * back were scanned for where the ink landed. They are the reference the
 * Android renderer's geometry has to agree with, and they cover the two
 * questions whose answer changes what the code does: where a drawing sits when
 * it is positioned, and when one line moves out of another's way.
 */
class AssAgainstLibassTest {

    /**
     * Three 100×100 squares written as `m 0 0 l 100 0 l 100 100 l 0 100`, each
     * with `\p1` and a `\pos` at y=300, under three alignments. libass drew ink
     * from x 300 to 1549 and y 200 to 399, which pins all three boxes.
     */
    @Test
    fun `a positioned drawing is placed by its bounding box`() {
        fun box(alignment: Int, x: Float): AssPoint = AssLayout.anchor(
            alignment = alignment,
            position = AssPoint(x, 300f),
            width = 100f,
            height = 100f,
            marginL = 15, marginR = 15, marginV = 10,
            playResX = 1920, playResY = 1080
        )

        // \an7 puts the top-left corner on the position: ink 300..399 both ways.
        assertEquals(AssPoint(300f, 300f), box(7, 300f))
        // \an5 centres it: ink 850..949 across, 250..349 down.
        assertEquals(AssPoint(850f, 250f), box(5, 900f))
        // \an2 puts the bottom centre there: ink 1450..1549, 200..299.
        assertEquals(AssPoint(1450f, 200f), box(2, 1500f))

        // Which together span exactly what libass drew.
        val left = box(7, 300f).x
        val right = box(2, 1500f).x + 100f
        val top = box(2, 1500f).y
        val bottom = box(7, 300f).y + 100f
        assertEquals(300f, left)
        assertEquals(1550f, right)     // 1549 is the last lit column
        assertEquals(200f, top)
        assertEquals(400f, bottom)     // 399 is the last lit row
    }

    /** A drawing's own coordinates map onto that box by its bounds. */
    @Test
    fun `the drawing bounds are what the box is made of`() {
        val steps = AssDrawing.parse("m 0 0 l 100 0 l 100 100 l 0 100", scale = 1)
        val bounds = AssDrawing.bounds(steps)
        assertEquals(100f, bounds.width)
        assertEquals(100f, bounds.height)
        assertEquals(0f, bounds.left)
        assertEquals(0f, bounds.top)
    }

    /**
     * A line's box has its bottom edge at `PlayResY - MarginV`.
     *
     * Measured with a 60px face at MarginV 60: libass put the baseline at
     * y≈1007, which is the box bottom of 1020 less that font's descent.
     */
    @Test
    fun `an unpositioned line hangs from its bottom margin`() {
        val anchor = AssLayout.anchor(
            alignment = 2, position = null, width = 400f, height = 60f,
            marginL = 15, marginR = 15, marginV = 60, playResX = 1920, playResY = 1080
        )
        assertEquals(1080f - 60f - 60f, anchor.y)
        assertEquals(1020f, anchor.y + 60f)
    }

    /**
     * Two identical bottom-aligned lines on one layer: libass left the first
     * where it was, at ink 973..1007, and moved the second up by exactly the
     * line's height, to 913..947.
     */
    @Test
    fun `two lines of one layer are stacked`() {
        val collisions = AssCollisions()
        val first = collisions.place(layer = 0, alignment = 2, top = 960f, height = 60f)
        val second = collisions.place(layer = 0, alignment = 2, top = 960f, height = 60f)
        assertEquals(960f, first)
        assertEquals(900f, second)
        assertEquals(60f, first - second)
    }

    /**
     * The same two lines on different layers: libass drew them on top of each
     * other, one band of ink at 973..1007 rather than two.
     */
    @Test
    fun `two lines of different layers are left alone`() {
        val collisions = AssCollisions()
        assertEquals(960f, collisions.place(layer = 0, alignment = 2, top = 960f, height = 60f))
        assertEquals(960f, collisions.place(layer = 1, alignment = 2, top = 960f, height = 60f))
    }

    /**
     * And the case that made the rule worth establishing: a Chinese line on
     * layer 0 at MarginV 60 and a Japanese one on layer 1 at MarginV 14, whose
     * boxes overlap. libass rendered them at 962..1016 and 1018..1061 whether
     * or not the other was present — neither moved.
     */
    @Test
    fun `a dual-language script keeps both lines where they were put`() {
        val collisions = AssCollisions()
        // 1080 - 60 - 66 and 1080 - 14 - 54.
        val chinese = collisions.place(layer = 0, alignment = 2, top = 954f, height = 66f)
        val japanese = collisions.place(layer = 1, alignment = 2, top = 1012f, height = 54f)
        assertEquals(954f, chinese)
        assertEquals(1012f, japanese)
        assertTrue(chinese + 66f > japanese, "the boxes really do overlap")
    }

    /** A top-aligned line displaced by another grows downwards, not off-screen. */
    @Test
    fun `stacking follows the alignment`() {
        val collisions = AssCollisions()
        assertEquals(10f, collisions.place(layer = 0, alignment = 8, top = 10f, height = 40f))
        assertEquals(50f, collisions.place(layer = 0, alignment = 8, top = 10f, height = 40f))
    }

    /** Resetting between frames, or every line would climb the screen. */
    @Test
    fun `each frame starts empty`() {
        val collisions = AssCollisions()
        collisions.place(layer = 0, alignment = 2, top = 960f, height = 60f)
        collisions.reset()
        assertEquals(960f, collisions.place(layer = 0, alignment = 2, top = 960f, height = 60f))
    }
}
