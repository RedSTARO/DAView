package com.daview.app.player

import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shape this has to survive is the one in the reference share: a Matroska
 * with an embedded PGS subtitle and two external `.ass` files beside it, whose
 * DAView indices start at 1000 and therefore say nothing about mpv's numbering.
 */
class TrackMappingTest {

    private fun audio(index: Int) = MediaStreamDto(index = index, type = StreamType.AUDIO)

    private fun embeddedSub(index: Int) = MediaStreamDto(index = index, type = StreamType.SUBTITLE)

    private fun externalSub(index: Int) = MediaStreamDto(
        index = index, type = StreamType.SUBTITLE, isExternal = true,
        externalPath = "/Ani/Show/Show - S01E01.zh-Hans.ass"
    )

    private val streams = listOf(
        MediaStreamDto(index = 1, type = StreamType.VIDEO),
        audio(2),
        audio(5),
        embeddedSub(3),
        externalSub(1001),
        externalSub(1000)
    )

    @Test
    fun `audio maps by position among the embedded audio tracks`() {
        assertEquals(1, TrackMapping.audioId(streams, 2))
        assertEquals(2, TrackMapping.audioId(streams, 5))
    }

    @Test
    fun `embedded subtitles come before external ones`() {
        assertEquals(1, TrackMapping.subtitleId(streams, 3))
    }

    /**
     * External files are attached with `sub-add` after the container's own
     * tracks, so their ids continue from there — and they are ordered by index
     * because that is the order the player screen adds them in.
     */
    @Test
    fun `external subtitles continue after the embedded ones, in index order`() {
        assertEquals(listOf(1000, 1001), TrackMapping.externalSubtitles(streams).map { it.index })
        assertEquals(2, TrackMapping.subtitleId(streams, 1000))
        assertEquals(3, TrackMapping.subtitleId(streams, 1001))
    }

    @Test
    fun `an index that is not in the list has no track`() {
        assertNull(TrackMapping.audioId(streams, 99))
        assertNull(TrackMapping.subtitleId(streams, 99))
        assertNull(TrackMapping.subtitleId(streams, null))
    }

    /** The video stream must not be counted as an audio or subtitle track. */
    @Test
    fun `the video track is not part of either mapping`() {
        assertNull(TrackMapping.audioId(streams, 1))
        assertNull(TrackMapping.subtitleId(streams, 1))
    }
}
