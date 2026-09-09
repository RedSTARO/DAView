package com.daview.app.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssDrawingTest {

    @Test
    fun `a move and two lines become a closed figure`() {
        val steps = AssDrawing.parse("m 0 0 l 100 0 l 100 50", scale = 1)
        assertEquals(
            listOf(
                AssDrawCommand.MoveTo(0f, 0f),
                AssDrawCommand.LineTo(100f, 0f),
                AssDrawCommand.LineTo(100f, 50f),
                AssDrawCommand.Close
            ),
            steps
        )
    }

    @Test
    fun `the drawing scale divides the coordinates`() {
        // \p4 means the author worked at eight times the size.
        val steps = AssDrawing.parse("m 0 0 l 80 40", scale = 4)
        assertEquals(AssDrawCommand.LineTo(10f, 5f), steps[1])
    }

    @Test
    fun `a real particle from the reference episode parses`() {
        val steps = AssDrawing.parse(
            "m 0 0 m 0 9 b 3 6 13 1 19 0 b 26 5 27 7 33 14 " +
                "b 30 22 28 26 23 33 b 15 32 10 32 3 29 b 1 22 0 14 0 9",
            scale = 1
        )
        // Two figures: the stray first move is closed off before the second.
        assertEquals(2, steps.count { it is AssDrawCommand.MoveTo })
        assertEquals(2, steps.count { it == AssDrawCommand.Close })
        assertEquals(5, steps.count { it is AssDrawCommand.CubicTo })
        val bounds = AssDrawing.bounds(steps)
        assertEquals(0f, bounds.left)
        assertEquals(33f, bounds.right)
        assertEquals(33f, bounds.bottom)
    }

    @Test
    fun `an n move does not close the figure in progress`() {
        val steps = AssDrawing.parse("m 0 0 l 10 0 n 20 0 l 30 0", scale = 1)
        assertEquals(1, steps.count { it == AssDrawCommand.Close })
    }

    @Test
    fun `repeated coordinates continue the last command`() {
        val steps = AssDrawing.parse("m 0 0 l 10 0 20 0 30 0", scale = 1)
        assertEquals(3, steps.count { it is AssDrawCommand.LineTo })
    }

    @Test
    fun `a b-spline is joined where the curve really starts`() {
        val steps = AssDrawing.parse("m 0 0 s 0 0 100 0 100 100 0 100 c", scale = 1)
        // The path steps onto the spline before curving along it.
        assertTrue(steps.any { it is AssDrawCommand.LineTo })
        assertTrue(steps.any { it is AssDrawCommand.CubicTo })
        assertTrue(steps.contains(AssDrawCommand.Close))
    }

    @Test
    fun `a truncated command does not throw`() {
        assertEquals(
            listOf(AssDrawCommand.MoveTo(0f, 0f), AssDrawCommand.Close),
            AssDrawing.parse("m 0 0 b 1 2 3", scale = 1)
        )
        assertEquals(emptyList(), AssDrawing.parse("", scale = 1))
        assertEquals(AssBounds.EMPTY, AssDrawing.bounds(emptyList()))
    }

    @Test
    fun `negative coordinates widen the box on the correct side`() {
        val bounds = AssDrawing.bounds(AssDrawing.parse("m -20 -10 l 30 40", scale = 1))
        assertEquals(-20f, bounds.left)
        assertEquals(-10f, bounds.top)
        assertEquals(50f, bounds.width)
        assertEquals(50f, bounds.height)
    }
}
