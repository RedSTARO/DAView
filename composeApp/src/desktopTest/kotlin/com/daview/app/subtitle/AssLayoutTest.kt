package com.daview.app.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssLayoutTest {

    private fun anchor(alignment: Int, position: AssPoint? = null) = AssLayout.anchor(
        alignment = alignment,
        position = position,
        width = 200f,
        height = 100f,
        marginL = 15,
        marginR = 15,
        marginV = 60,
        playResX = 1920,
        playResY = 1080
    )

    @Test
    fun `the keypad splits into rows and columns`() {
        assertEquals(listOf(0, 1, 2), listOf(1, 2, 3).map { AssLayout.horizontal(it) })
        assertEquals(listOf(0, 1, 2), listOf(7, 8, 9).map { AssLayout.horizontal(it) })
        assertEquals(listOf(0, 0, 0), listOf(1, 2, 3).map { AssLayout.vertical(it) })
        assertEquals(listOf(1, 1, 1), listOf(4, 5, 6).map { AssLayout.vertical(it) })
        assertEquals(listOf(2, 2, 2), listOf(7, 8, 9).map { AssLayout.vertical(it) })
    }

    @Test
    fun `without a position the block sits against its margins`() {
        assertEquals(AssPoint(15f, 920f), anchor(1))
        assertEquals(AssPoint(860f, 920f), anchor(2))
        assertEquals(AssPoint(1705f, 920f), anchor(3))
        assertEquals(AssPoint(15f, 60f), anchor(7))
        assertEquals(AssPoint(1705f, 60f), anchor(9))
        assertEquals(AssPoint(860f, 490f), anchor(5))
    }

    @Test
    fun `with a position the alignment says which corner lands on it`() {
        val at = AssPoint(1000f, 500f)
        assertEquals(AssPoint(1000f, 400f), anchor(1, at))
        assertEquals(AssPoint(900f, 400f), anchor(2, at))
        assertEquals(AssPoint(800f, 400f), anchor(3, at))
        assertEquals(AssPoint(1000f, 500f), anchor(7, at))
        assertEquals(AssPoint(900f, 450f), anchor(5, at))
        assertEquals(AssPoint(800f, 500f), anchor(9, at))
    }

    @Test
    fun `centring happens between the margins, not across the frame`() {
        val offCentre = AssLayout.anchor(
            alignment = 2, position = null, width = 200f, height = 100f,
            marginL = 400, marginR = 0, marginV = 10, playResX = 1000, playResY = 500
        )
        assertEquals(400f + 200f, offCentre.x)
    }

    @Test
    fun `wrap style two never breaks`() {
        val widths = List(10) { 100f }
        assertEquals(emptyList(), AssLayout.wrap(widths, 10f, 150f, wrapStyle = 2))
    }

    @Test
    fun `wrap style one fills each line before the next`() {
        // Four words of 100 with 10-wide spaces, in 340: 100+10+100+10+100 = 320
        // fits, the fourth does not.
        val breaks = AssLayout.wrap(List(4) { 100f }, 10f, 340f, wrapStyle = 1)
        assertEquals(listOf(3), breaks)
    }

    @Test
    fun `the default wrap style evens the lines out`() {
        // Greedy would put three words on the first line and one on the second.
        val breaks = AssLayout.wrap(List(4) { 100f }, 10f, 340f, wrapStyle = 0)
        assertEquals(listOf(2), breaks)
    }

    @Test
    fun `balancing keeps the line count greedy found`() {
        val widths = listOf(80f, 120f, 60f, 200f, 90f, 130f)
        for (style in listOf(0, 3)) {
            val greedy = AssLayout.wrap(widths, 8f, 300f, wrapStyle = 1)
            val balanced = AssLayout.wrap(widths, 8f, 300f, wrapStyle = style)
            assertEquals(greedy.size, balanced.size, "wrap style $style")
        }
    }

    @Test
    fun `a word wider than the line still gets a line of its own`() {
        val breaks = AssLayout.wrap(listOf(50f, 500f, 50f), 10f, 100f, wrapStyle = 1)
        assertEquals(listOf(1, 2), breaks)
    }

    @Test
    fun `nothing to break gives nothing back`() {
        assertEquals(emptyList(), AssLayout.wrap(listOf(50f), 10f, 100f, wrapStyle = 0))
        assertEquals(emptyList(), AssLayout.wrap(emptyList(), 10f, 100f, wrapStyle = 0))
        assertTrue(AssLayout.wrap(List(3) { 10f }, 2f, 0f, wrapStyle = 0).isEmpty())
    }
}
