package com.daview.app.player

import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The list mpv draws when a track key is pressed, and the step from one entry
 * to the next. Both are pure, which is the whole reason they live outside the
 * composable: nothing else about the desktop player can be exercised without
 * libmpv and a window.
 */
class TrackMenuTest {

    private fun audio(index: Int, language: String) =
        MediaStreamDto(index = index, type = StreamType.AUDIO, language = language)

    private fun embeddedSub(index: Int, language: String) =
        MediaStreamDto(index = index, type = StreamType.SUBTITLE, language = language)

    private fun externalSub(index: Int, language: String) = MediaStreamDto(
        index = index, type = StreamType.SUBTITLE, language = language, isExternal = true,
        externalPath = "/Ani/Show/Show - S01E01.$language.ass"
    )

    private val streams = listOf(
        MediaStreamDto(index = 1, type = StreamType.VIDEO),
        audio(2, "jpn"),
        audio(5, "zho"),
        embeddedSub(3, "eng"),
        externalSub(1000, "zh-Hans")
    )

    @Test
    fun `audio lists every audio track and nothing else`() {
        assertEquals(listOf(2, 5), TrackMenu.audio(streams).map { it.index })
    }

    @Test
    fun `subtitles put off last, after every track that could be chosen`() {
        assertEquals(listOf(3, 1000, null), TrackMenu.subtitles(streams).map { it.index })
    }

    @Test
    fun `stepping wraps round the end of the list`() {
        val entries = TrackMenu.audio(streams)
        assertEquals(5, TrackMenu.next(entries, 2)?.index)
        assertEquals(2, TrackMenu.next(entries, 5)?.index)
    }

    @Test
    fun `stepping from off comes back to the first subtitle`() {
        val entries = TrackMenu.subtitles(streams)
        assertEquals(3, TrackMenu.next(entries, null)?.index)
    }

    /**
     * A container this app could not probe has no streams to match, and mpv may
     * still be playing something. Stepping has to go somewhere rather than
     * nowhere, so an unknown selection starts from the top.
     */
    @Test
    fun `an index that is not in the list steps to the first entry`() {
        assertEquals(2, TrackMenu.next(TrackMenu.audio(streams), 99)?.index)
    }

    @Test
    fun `stepping an empty list selects nothing`() {
        assertNull(TrackMenu.next(emptyList(), null))
    }

    @Test
    fun `the osd message marks the chosen entry and only that one`() {
        val entries = TrackMenu.subtitles(streams)
        val lines = TrackMenu.osd("字幕", entries, 1000).lines()
        assertEquals("字幕", lines.first())
        assertEquals(1, lines.count { it.startsWith("▸") })
        assertTrue(lines.single { it.startsWith("▸") }.contains("外挂"))
    }

    @Test
    fun `off is a line of the message like any other`() {
        val entries = TrackMenu.subtitles(streams)
        val lines = TrackMenu.osd("字幕", entries, null).lines()
        assertTrue(lines.last().startsWith("▸"))
        assertTrue(lines.last().endsWith(TrackMenu.OFF))
    }

    /**
     * A season pack can carry a dozen subtitle files. Drawn whole the message
     * would fill the screen from top to bottom on every key press, so it is
     * trimmed around the mark — which has to stay on it.
     */
    @Test
    fun `a long list is trimmed around the chosen entry`() {
        val many = (0 until 20).map { embeddedSub(it, "eng") }
        val entries = TrackMenu.subtitles(many)
        val lines = TrackMenu.osd("字幕", entries, 15).lines()
        assertEquals(9, lines.size)
        assertEquals(1, lines.count { it.startsWith("▸") })
        assertTrue(lines.first().contains("(8/21)"))
    }

    @Test
    fun `a long list trimmed at the top still shows the first entry`() {
        val many = (0 until 20).map { embeddedSub(it, "eng") }
        val lines = TrackMenu.osd("字幕", TrackMenu.subtitles(many), 0).lines()
        assertEquals(1, lines.count { it.startsWith("▸") })
        assertTrue(lines[1].startsWith("▸"))
    }
}
