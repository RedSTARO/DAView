package com.daview.app.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lines here are taken verbatim from
 * `/Ani/Beyond the Boundary (2013)/Season 01/Beyond the Boundary - S01E07.zh-Hans.ass`,
 * which is the script this work started from.
 */
class AssLineParserTest {

    private val script = AssScript.parse(
        """
        [Script Info]
        PlayResX: 1920
        PlayResY: 1080

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: OPJP,DFPMincho-UB,56,&H00DCA312,&H00FFFFFF,&H00FFFFFF,&H00FFFFFF,0,0,0,0,100,100,0,0,1,1.5,1.3,2,10,10,10,1
        Style: ZWDB,方正兰亭圆_GBK_中粗,66,&H00FFFFFF,&H000019FF,&H00303030,&HA0000000,0,0,0,0,100,100,1,0,1,3.5,0,2,15,15,60,1
        Style: Eng,Arial,40,&H0000FF00,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,8,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent()
    )

    private fun parse(text: String, style: String = "OPJP", from: Long = 0, to: Long = 5_000) =
        AssLineParser.parse(
            AssEvent(0, from, to, style, 0, 0, 0, "", text),
            script
        )

    @Test
    fun `plain text keeps the style's own values`() {
        val line = parse("为什么你还活着", style = "ZWDB")
        assertEquals(1, line.runs.size)
        assertEquals("为什么你还活着", line.runs[0].text)
        assertEquals(66f, line.runs[0].style.fontSize)
        assertEquals(2, line.alignment)
        assertEquals(60, line.marginV)
        assertNull(line.position)
    }

    @Test
    fun `a drawing keeps its commands out of the text`() {
        val line = parse(
            """{\pos(628,1058)\fscx120\fscy120\alpha&HD0&\1c&HFFFFFF&\3c&HFFFFFF&\bord1\blur2\p1}""" +
                "m 0 0 m 0 9 b 3 6 13 1 19 0 b 26 5 27 7 33 14"
        )
        val run = line.runs.single()
        assertTrue(run.isDrawing)
        assertEquals(1, run.style.drawingScale)
        assertEquals(AssPoint(628f, 1058f), line.position)
        assertEquals(120f, run.style.scaleX)
        assertEquals(120f, run.style.scaleY)
        assertEquals(1f, run.style.outlineX)
        assertEquals(2f, run.style.blur)
        // A colour override carries no alpha of its own, so the \alpha that
        // came before it in the same block still stands: white at 47/255.
        assertEquals(0xFFFFFF, run.style.outlineColour and 0x00FFFFFF)
        assertEquals(0xFFFFFF, run.style.primaryColour and 0x00FFFFFF)
        assertEquals(47, (run.style.primaryColour ushr 24) and 0xFF)
        assertEquals(47, (run.style.outlineColour ushr 24) and 0xFF)
    }

    @Test
    fun `nested transitions survive the split`() {
        val line = parse("""{\an5\alpha&HFF&\bord0\blur6\t(0,600,1\alpha&H00&)\t(300,600,1,\bord4)\pos(568,1017)}x""")
        val run = line.runs.single()
        assertEquals(5, line.alignment)
        assertEquals(2, run.transitions.size)
        assertEquals(0, run.transitions[0].startMs)
        assertEquals(600, run.transitions[0].endMs)
        assertEquals(1f, run.transitions[0].acceleration)
        assertEquals(listOf("alpha" to "&H00&"), run.transitions[0].tags)
        assertEquals(300, run.transitions[1].startMs)
        assertEquals(listOf("bord" to "4"), run.transitions[1].tags)
    }

    @Test
    fun `a transition moves the value it names and holds it afterwards`() {
        val line = parse("""{\alpha&HFF&\bord0\t(0,600,1\alpha&H00&)\t(300,600,1,\bord4)}x""")
        val run = line.runs.single()
        val style = line.style

        // Fully transparent to start with.
        assertEquals(0, (AssLineParser.styleAt(run, 0, style).primaryColour ushr 24) and 0xFF)
        // Halfway through the first transition.
        assertEquals(127, (AssLineParser.styleAt(run, 300, style).primaryColour ushr 24) and 0xFF)
        // Past the end it holds.
        assertEquals(255, (AssLineParser.styleAt(run, 5_000, style).primaryColour ushr 24) and 0xFF)

        assertEquals(0f, AssLineParser.styleAt(run, 300, style).outlineX)
        assertEquals(2f, AssLineParser.styleAt(run, 450, style).outlineX)
        assertEquals(4f, AssLineParser.styleAt(run, 600, style).outlineX)
    }

    @Test
    fun `acceleration bends the transition`() {
        val line = parse("""{\fscx100\t(0,1000,2,\fscx200)}x""")
        val run = line.runs.single()
        // With an exponent of 2 the halfway point is a quarter of the way.
        assertEquals(125f, AssLineParser.styleAt(run, 500, line.style).scaleX)
    }

    @Test
    fun `a transition without times runs over the whole event`() {
        val line = parse("""{\fscx100\t(\fscx200)}x""", from = 0, to = 4_000)
        val transition = line.runs.single().transitions.single()
        assertEquals(0, transition.startMs)
        assertEquals(4_000, transition.endMs)
    }

    @Test
    fun `an override splits the line into runs`() {
        val line = parse("""before{\b1}bold{\b0}after""")
        assertEquals(listOf("before", "bold", "after"), line.runs.map { it.text })
        assertTrue(line.runs[1].style.bold)
        assertTrue(!line.runs[2].style.bold)
    }

    @Test
    fun `escapes become breaks and spaces`() {
        val line = parse("""first\Nsecond\hthird\nfourth""")
        // \h is a hard space: it must not be a wrapping opportunity, so it
        // becomes U+00A0 rather than an ordinary space. \n is one, under every
        // wrap style but 2.
        assertEquals(listOf("first", "second\u00A0third fourth"), line.runs.map { it.text })
        assertTrue(line.runs[1].breakBefore)
        assertTrue(!line.runs[0].breakBefore)
    }

    @Test
    fun `wrap style two makes the soft break a real one`() {
        val line = parse("""{\q2}first\nsecond""")
        assertEquals(listOf("first", "second"), line.runs.map { it.text })
        assertTrue(line.runs[1].breakBefore)
    }

    @Test
    fun `a reset goes back to a named style`() {
        val line = parse("""{\fs20\b1}small{\rEng}reset""")
        assertEquals(20f, line.runs[0].style.fontSize)
        assertTrue(line.runs[0].style.bold)
        assertEquals(40f, line.runs[1].style.fontSize)
        assertEquals("Arial", line.runs[1].style.fontName)
        // \r takes the alignment of the style it resets to, as libass does.
        assertEquals(8, line.alignment)
    }

    @Test
    fun `a bare reset goes back to the event's own style`() {
        val line = parse("""{\fs20}small{\r}reset""", style = "ZWDB")
        assertEquals(66f, line.runs[1].style.fontSize)
    }

    @Test
    fun `move interpolates and outranks a position`() {
        val line = parse("""{\move(100,200,300,400)}x""", from = 0, to = 1_000)
        val move = assertNotNull(line.move)
        assertEquals(AssPoint(100f, 200f), move.at(0, 1_000))
        assertEquals(AssPoint(200f, 300f), move.at(500, 1_000))
        assertEquals(AssPoint(300f, 400f), move.at(2_000, 1_000))
        assertEquals(AssPoint(200f, 300f), line.positionAt(500))
    }

    @Test
    fun `move with its own window holds at each end`() {
        val line = parse("""{\move(0,0,100,0,200,400)}x""", from = 0, to = 1_000)
        val move = assertNotNull(line.move)
        assertEquals(0f, move.at(100, 1_000).x)
        assertEquals(50f, move.at(300, 1_000).x)
        assertEquals(100f, move.at(900, 1_000).x)
    }

    @Test
    fun `fad becomes a fade over the event`() {
        val line = parse("""{\fad(300,300)}x""", from = 0, to = 2_000)
        val fade = assertNotNull(line.fade)
        assertEquals(0, fade.opacityAt(0))
        assertEquals(255, fade.opacityAt(300))
        assertEquals(255, fade.opacityAt(1_000))
        assertEquals(0, fade.opacityAt(2_000))
        assertEquals(127, fade.opacityAt(150))
    }

    @Test
    fun `fade takes transparencies and gives opacities`() {
        val line = parse("""{\fade(255,0,255,0,500,1000,1500)}x""")
        val fade = assertNotNull(line.fade)
        assertEquals(0, fade.opacityAt(0))
        assertEquals(255, fade.opacityAt(700))
        assertEquals(0, fade.opacityAt(2_000))
    }

    @Test
    fun `a rectangular clip is normalised`() {
        val line = parse("""{\clip(300,100,100,300)}x""")
        val clip = assertNotNull(line.clip) as AssClip.Rect
        assertEquals(100f, clip.x1)
        assertEquals(100f, clip.y1)
        assertEquals(300f, clip.x2)
        assertEquals(300f, clip.y2)
        assertTrue(!clip.inverse)
    }

    @Test
    fun `a vector clip keeps its drawing and its scale`() {
        val line = parse("""{\iclip(2,m 0 0 l 100 0 l 100 100)}x""")
        val clip = assertNotNull(line.clip) as AssClip.Drawing
        assertEquals(2, clip.scale)
        assertEquals("m 0 0 l 100 0 l 100 100", clip.commands)
        assertTrue(clip.inverse)
    }

    @Test
    fun `tag names are matched longest first`() {
        assertEquals(
            listOf("be" to "2", "b" to "1", "bord" to "3", "blur" to "4"),
            AssLineParser.splitTags("""\be2\b1\bord3\blur4""")
        )
        assertEquals(
            listOf("fscx" to "120", "fsp" to "2", "fs" to "30", "fn" to "Arial"),
            AssLineParser.splitTags("""\fscx120\fsp2\fs30\fnArial""")
        )
        assertEquals(
            listOf("clip" to "1,2,3,4", "c" to "&HFF&", "an" to "5", "alpha" to "&H80&"),
            AssLineParser.splitTags("""\clip(1,2,3,4)\c&HFF&\an5\alpha&H80&""")
        )
    }

    @Test
    fun `an unbalanced brace is text, not an override`() {
        val line = parse("a {b")
        assertEquals("a {b", line.runs.single().text)
    }

    @Test
    fun `an unknown tag is dropped rather than shown`() {
        val line = parse("""{\zzz9\fs20}x""")
        assertEquals("x", line.runs.single().text)
        assertEquals(20f, line.runs.single().style.fontSize)
    }

    @Test
    fun `karaoke syllables follow one another`() {
        val line = parse("""{\k50}one{\k100}two""")
        assertEquals(0, line.runs[0].karaoke?.startMs)
        assertEquals(500, line.runs[0].karaoke?.durationMs)
        assertEquals(500, line.runs[1].karaoke?.startMs)
        assertEquals(1_000, line.runs[1].karaoke?.durationMs)
        assertEquals(AssKaraokeKind.FILL, line.runs[0].karaoke?.kind)
        assertEquals(AssKaraokeKind.FILL, line.runs[1].karaoke?.kind)
    }

    @Test
    fun `an event margin overrides the style's and a zero does not`() {
        val withMargin = AssLineParser.parse(
            AssEvent(0, 0, 1_000, "ZWDB", 0, 0, 200, "", "x"), script
        )
        assertEquals(200, withMargin.marginV)
        assertEquals(15, withMargin.marginL)
    }
}
