package com.daview.app.subtitle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders on a device and checks where the ink landed, against the same cases
 * measured through libass.
 */
@RunWith(AndroidJUnit4::class)
class AssRenderOnDeviceTest {

    private val header = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 1920
        PlayResY: 1080
        ScaledBorderAndShadow: yes

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: ZWDB,Microsoft YaHei,66,&H00FFFFFF,&H000019FF,&H00303030,&HA0000000,0,0,0,0,100,100,1,0,1,3.5,0,2,15,15,60,1
        Style: JPDB,Microsoft YaHei,54,&H00FFFFFF,&H000019FF,&H00303030,&HA0000000,-1,0,0,0,100,100,1,0,1,3.4,0,2,15,15,14,1
        Style: D,Microsoft YaHei,60,&H00FFFFFF,&H000019FF,&H00303030,&HA0000000,0,0,0,0,100,100,0,0,1,0,0,2,15,15,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent() + "\n"

    private fun render(script: String, name: String, timeMs: Long = 2_000): Bands {
        val fonts = AssFonts(null)
        val renderer = AssRenderer(fonts)
        renderer.script = AssScript.parse(script)
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        renderer.draw(canvas, RectF(0f, 0f, 1920f, 1080f), timeMs)

        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir("assrender")
        if (dir != null) {
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        return bands(bitmap)
    }

    private class Bands(val list: List<IntArray>) {
        val count: Int get() = list.size
    }

    /** Each contiguous run of rows that carries ink, as {top, bottom, left, right}. */
    private fun bands(bitmap: Bitmap): Bands {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        val out = ArrayList<IntArray>()
        var start = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var first = -1
            var last = -1
            for (x in 0 until width) {
                val pixel = row[x]
                val lit = Color.red(pixel) > 40 || Color.green(pixel) > 40 || Color.blue(pixel) > 40
                if (lit) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first >= 0) {
                if (start < 0) start = y
                left = minOf(left, first)
                right = maxOf(right, last)
            } else if (start >= 0) {
                out.add(intArrayOf(start, y - 1, left, right))
                start = -1
                left = width
                right = -1
            }
        }
        if (start >= 0) out.add(intArrayOf(start, height - 1, left, right))
        return Bands(out)
    }

    /**
     * Three 100×100 squares under three alignments. No font is involved, so
     * this has to match libass exactly: it drew ink from x 300 to 1549 and
     * y 200 to 399.
     */
    @Test
    fun positionedDrawingsLandWhereLibassPutThem() {
        val square = "m 0 0 l 100 0 l 100 100 l 0 100"
        val events = listOf(7 to 300, 5 to 900, 2 to 1500).joinToString("") { (an, x) ->
            "Dialogue: 0,0:00:00.00,0:00:10.00,D,,0,0,0,," +
                "{\\an$an\\pos($x,300)\\1c&HFFFFFF&\\bord0\\p1}$square\n"
        }
        val result = render(header + events, "drawings")
        assertEquals("one band of ink", 1, result.count)
        val band = result.list[0]
        assertEquals("top row", 200, band[0])
        assertEquals("bottom row", 399, band[1])
        assertEquals("left column", 300, band[2])
        assertEquals("right column", 1549, band[3])
    }

    /**
     * A Chinese line on layer 0 at MarginV 60 and a Japanese one on layer 1 at
     * MarginV 14, whose boxes overlap. libass moved neither, drawing ink from
     * 962 to 1016 and from 1018 to 1061.
     *
     * What is asserted is not those rows: this device has none of the fonts the
     * script names, so its glyphs are a different height and the two lines here
     * touch rather than leaving the one blank row libass had between them. What
     * has to hold whatever the font is, is that neither line was pushed off its
     * own margin — the Japanese one still reaches down to its MarginV of 14,
     * where being stacked above the Chinese would have stopped it at 1020.
     */
    @Test
    fun dualLanguageLinesBothStayPut() {
        val events =
            "Dialogue: 0,0:00:00.00,0:00:10.00,ZWDB,,0,0,0,,为什么你还活着\n" +
                "Dialogue: 1,0:00:00.00,0:00:10.00,JPDB,,0,0,0,,なぜ生きてるの\n"
        val result = render(header + events, "dual")
        assertTrue("something was drawn", result.count > 0)
        val lowest = result.list.maxOf { it[1] }
        val highest = result.list.minOf { it[0] }
        assertTrue("the Japanese line still hangs from its own margin: $lowest",
            lowest in 1040..1066)
        assertTrue("the Chinese line is above it, not stacked on it: $highest",
            highest in 930..1000)
    }

    /** Two lines of one layer are stacked instead. */
    @Test
    fun sameLayerLinesAreStacked() {
        val events =
            "Dialogue: 0,0:00:00.00,0:00:10.00,D,,0,0,0,,AAAA\n" +
                "Dialogue: 0,0:00:00.00,0:00:10.00,D,,0,0,0,,BBBB\n"
        val result = render(header + events, "stacked")
        assertEquals("two bands", 2, result.count)
        assertTrue("clear of each other", result.list[0][1] < result.list[1][0])
    }

    /**
     * The overlay has somewhere to live.
     *
     * PlayerView keeps a frame between the picture and its controls, and the
     * subtitle view goes in there. If that frame were ever absent the feature
     * would not fail — it would silently draw nothing, which is the failure
     * this catches.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun theOverlayHasAHomeInsidePlayerView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var attached = false
        instrumentation.runOnMainSync {
            val player = PlayerView(instrumentation.targetContext)
            val overlay = player.overlayFrameLayout
            assertTrue("PlayerView keeps an overlay frame", overlay != null)
            val subtitles = AssSubtitleView(instrumentation.targetContext)
            overlay!!.addView(subtitles)
            attached = subtitles.parent === overlay
        }
        assertTrue("the subtitle view goes into it", attached)
    }

    /** The reference episode's own opening, at its busiest frame. */
    @Test
    fun theReferenceEpisodeDraws() {
        val file = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir("assrender"),
            "e07.ass"
        )
        if (!file.isFile) return
        val fonts = AssFonts(null)
        val renderer = AssRenderer(fonts)
        renderer.script = AssScript.parse(AssSource.decode(file.readBytes()))
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        // Warm, then time a second of playback at 60 Hz over the busiest
        // stretch: one sample says nothing about whether it can keep up.
        renderer.draw(canvas, RectF(0f, 0f, 1920f, 1080f), 104_240)
        val started = System.nanoTime()
        for (frame in 0 until 60) {
            renderer.draw(canvas, RectF(0f, 0f, 1920f, 1080f), 104_240L + frame * 16)
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000.0 / 60.0
        File(file.parentFile, "e07-104240.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val result = bands(bitmap)
        assertTrue("something was drawn", result.count > 0)
        android.util.Log.i("AssRender", "busiest frame: ${result.count} bands in $tookMs ms")
    }
}
