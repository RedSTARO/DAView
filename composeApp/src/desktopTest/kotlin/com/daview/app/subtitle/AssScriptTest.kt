package com.daview.app.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssScriptTest {

    private val script = AssScript.parse(
        """
        [Script Info]
        ; a comment
        Title: Default Aegisub file
        ScriptType: v4.00+
        WrapStyle: 0
        ScaledBorderAndShadow: yes
        PlayResX: 1920
        PlayResY: 1080

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: ZWDB,方正仿宋_GBK,50,&H00FFFFFF,&H000000FF,&H00584939,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1
        Style: ZWDB,方正兰亭圆_GBK_中粗,66,&H00FFFFFF,&H000019FF,&H00303030,&HA0000000,0,0,0,0,100,100,1,0,1,3.5,0,2,15,15,60,1
        Style: OPCN,華康明體 Std W12,56,&H00DCA312,&H00FFFFFF,&H00FFFFFF,&H00FFFFFF,0,0,0,0,100,100,0,0,1,3,1.3,8,10,10,34,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 1,0:00:03.32,0:00:05.20,JPDB,,0,0,0,,なぜ　生きてるの
        Dialogue: 0,0:00:03.32,0:00:05.20,ZWDB,,0,0,0,,为什么你还活着
        Comment: 0,0:00:03.32,0:00:05.20,ZWDB,,0,0,0,,not shown
        Dialogue: 0,0:00:05.62,0:00:06.16,ZWDB,,0,0,0,,赎罪？
        """.trimIndent()
    )

    @Test
    fun `reads the script header`() {
        assertEquals(1920, script.playResX)
        assertEquals(1080, script.playResY)
        assertEquals(0, script.wrapStyle)
        assertTrue(script.scaledBorderAndShadow)
    }

    @Test
    fun `a script without a resolution gets the same default as libass`() {
        val bare = AssScript.parse("[Events]\nDialogue: 0,0:00:00.00,0:00:01.00,X,,0,0,0,,hi")
        assertEquals(AssScript.DEFAULT_PLAY_RES_X, bare.playResX)
        assertEquals(AssScript.DEFAULT_PLAY_RES_Y, bare.playResY)
    }

    @Test
    fun `a duplicated style name resolves to the last definition`() {
        // Merged scripts — one release's dialogue plus another's songs — declare
        // the same name twice, and the dialogue is the one that came last.
        val style = script.styleFor("ZWDB")
        assertEquals("方正兰亭圆_GBK_中粗", style.fontName)
        assertEquals(66f, style.fontSize)
        assertEquals(60, style.marginV)
    }

    @Test
    fun `an unknown style name falls back to Default and then to the built-in`() {
        assertEquals(AssStyle.DEFAULT.fontName, script.styleFor("JPDB").fontName)
    }

    @Test
    fun `colours are read backwards from ABGR with inverted alpha`() {
        assertEquals(0xFFFFFFFF.toInt(), parseColour("&H00FFFFFF"))
        // &H00DCA312 is opaque, blue DC, green A3, red 12.
        assertEquals(0xFF12A3DC.toInt(), parseColour("&H00DCA312"))
        assertEquals(0x5F000000.toInt(), parseColour("&HA0000000"))
        assertEquals(0xFF0000FF.toInt(), parseColour("&HFF0000&"))
        assertNull(parseColour(""))
    }

    @Test
    fun `alpha overrides are opacities, not transparencies`() {
        assertEquals(255, parseAlpha("&H00&"))
        assertEquals(0, parseAlpha("&HFF&"))
        assertEquals(47, parseAlpha("&HD0&"))
    }

    @Test
    fun `timestamps are centisecond precision`() {
        assertEquals(3_320L, parseTime("0:00:03.32"))
        assertEquals(5_200L, parseTime("0:00:05.20"))
        assertEquals(3_723_000L + 450, parseTime("1:02:03.45"))
        // A single digit after the point is tenths.
        assertEquals(1_500L, parseTime("0:00:01.5"))
        assertNull(parseTime("nonsense"))
    }

    @Test
    fun `bold is minus one, and zero is not truthy`() {
        assertTrue(script.styleFor("ZWDB").bold.not())
        val legacy = AssScript.parse(
            """
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: B,Arial,50,&H00FFFFFF,&H000000FF,&H00584939,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1
            """.trimIndent()
        )
        assertTrue(legacy.styleFor("B").bold)
    }

    @Test
    fun `comment lines are not events`() {
        assertEquals(3, script.events.size)
    }

    @Test
    fun `events on screen come back in layer order`() {
        val showing = script.eventsAt(4_000)
        assertEquals(2, showing.size)
        assertEquals("ZWDB", showing[0].styleName)
        assertEquals("JPDB", showing[1].styleName)
        assertEquals(1, showing[1].layer)
    }

    @Test
    fun `an event is gone the moment it ends`() {
        assertEquals(2, script.eventsAt(3_320).size)
        assertEquals(0, script.eventsAt(5_200).size)
        assertEquals(1, script.eventsAt(5_620).size)
    }

    @Test
    fun `an event that ends before it starts never shows`() {
        // The particle effects in the reference episode carry a few of these.
        val inverted = AssScript.parse(
            """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:25.71,0:00:25.64,OPJP,,0,0,0,,x
            """.trimIndent()
        )
        assertEquals(0, inverted.eventsAt(25_680).size)
    }

    @Test
    fun `SSA v4 styles keep their own colour and alignment conventions`() {
        val legacy = AssScript.parse(
            """
            [Script Info]
            ScriptType: v4.00
            [V4 Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
            Style: Top,Arial,24,&H00FFFFFF,&H0000FFFF,&H000000FF,&H00000000,0,0,1,2,2,6,10,10,10,0,1
            """.trimIndent()
        )
        val style = legacy.styleFor("Top")
        // "6" in SSA is centre of the top row, which is 8 on the keypad.
        assertEquals(8, style.alignment)
        // The third colour is the outline in v4, where v4+ puts the outline.
        assertEquals(0xFFFF0000.toInt(), style.outlineColour)
    }

    @Test
    fun `a text field may hold commas`() {
        val commas = AssScript.parse(
            """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:00:01.00,X,,0,0,0,,one, two, three
            """.trimIndent()
        )
        assertEquals("one, two, three", commas.events.single().text)
    }
}
