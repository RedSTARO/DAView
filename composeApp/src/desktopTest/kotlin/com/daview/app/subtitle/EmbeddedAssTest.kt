package com.daview.app.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbeddedAssTest {

    // The codec data of a Matroska ASS track: the original header, events section
    // and all, with no events in it.
    private val header = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 1920
        PlayResY: 1080
        ScaledBorderAndShadow: yes

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,方正兰亭圆_GBK_中粗,66,&H00FFFFFF,&H000019FF,&H00303030,&HA0000000,0,0,0,0,100,100,1,0,1,3.5,0,2,15,15,60,1
        Style: Sign,思源黑体,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,8,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent()

    @Test
    fun `the header gives the resolution and styles`() {
        val script = EmbeddedAss(header).snapshot()
        assertEquals(1920, script.playResX)
        assertEquals(1080, script.playResY)
        assertEquals("思源黑体", script.styleFor("Sign").fontName)
        assertTrue(script.events.isEmpty())
    }

    @Test
    fun `a block starts at its timestamp and lasts its duration`() {
        val track = EmbeddedAss(header)
        assertTrue(track.add("Dialogue: 0:00:00:00,0:00:02:15,7,0,Default,,0,0,0,,为什么你还活着", 63_320))
        val event = track.snapshot().events.single()
        assertEquals(63_320, event.startMs)
        assertEquals(65_470, event.endMs)
        assertEquals("Default", event.styleName)
        assertEquals("为什么你还活着", event.text)
        assertEquals(event, track.snapshot().eventsAt(64_000).single())
    }

    @Test
    fun `layer, margins, effect and override tags come through`() {
        val track = EmbeddedAss(header)
        track.add("Dialogue: 0:00:00:00,0:00:01:00,3,2,Sign,Actor,5,6,7,Banner;20,{\\pos(960,80)\\fad(200,0)}标题", 1_000)
        val event = track.snapshot().events.single()
        assertEquals(2, event.layer)
        assertEquals(5, event.marginL)
        assertEquals(6, event.marginR)
        assertEquals(7, event.marginV)
        assertEquals("Banner;20", event.effect)
        assertEquals("{\\pos(960,80)\\fad(200,0)}标题", event.text)
    }

    @Test
    fun `commas in the text stay in the text`() {
        val track = EmbeddedAss(header)
        track.add("Dialogue: 0:00:00:00,0:00:01:00,1,0,Default,,0,0,0,,one, two, three", 0)
        assertEquals("one, two, three", track.snapshot().events.single().text)
    }

    @Test
    fun `a block read twice after a seek is kept once`() {
        val track = EmbeddedAss(header)
        assertTrue(track.add("Dialogue: 0:00:00:00,0:00:01:00,4,0,Default,,0,0,0,,again", 5_000))
        val before = track.version
        assertFalse(track.add("Dialogue: 0:00:00:00,0:00:01:00,4,0,Default,,0,0,0,,again", 5_000))
        assertEquals(before, track.version)
        assertEquals(1, track.snapshot().events.size)
    }

    @Test
    fun `a muxer that numbers every line the same still keeps them all`() {
        val track = EmbeddedAss(header)
        track.add("Dialogue: 0:00:00:00,0:00:01:00,0,0,Default,,0,0,0,,one", 1_000)
        track.add("Dialogue: 0:00:00:00,0:00:01:00,0,0,Default,,0,0,0,,two", 1_000)
        track.add("Dialogue: 0:00:00:00,0:00:01:00,0,0,Default,,0,0,0,,one", 3_000)
        assertEquals(3, track.snapshot().events.size)
        assertFalse(track.add("Dialogue: 0:00:00:00,0:00:01:00,0,0,Default,,0,0,0,,two", 1_000))
    }

    @Test
    fun `lines that start together keep the order they were written in`() {
        val track = EmbeddedAss(header)
        // Read out of order, as the blocks of one moment can be.
        track.add("Dialogue: 0:00:00:00,0:00:01:00,9,0,Default,,0,0,0,,second", 2_000)
        track.add("Dialogue: 0:00:00:00,0:00:01:00,8,0,Default,,0,0,0,,first", 2_000)
        track.add("Dialogue: 0:00:00:00,0:00:01:00,1,0,Default,,0,0,0,,earlier", 1_000)
        assertEquals(listOf("earlier", "first", "second"), track.snapshot().events.map { it.text })
    }

    @Test
    fun `anything that is not a dialogue line is ignored`() {
        val track = EmbeddedAss(header)
        assertFalse(track.add("Comment: 0:00:00:00,0:00:01:00,1,0,Default,,0,0,0,,note", 0))
        assertFalse(track.add("Dialogue: 0:00:00:00,0:00:01:00,notanumber,0,Default,,0,0,0,,x", 0))
        assertFalse(track.add("", 0))
        assertEquals(0, track.version)
    }
}
