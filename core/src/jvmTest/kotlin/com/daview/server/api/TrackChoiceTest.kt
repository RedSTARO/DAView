package com.daview.server.api

import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.SUBTITLE_OFF
import com.daview.shared.model.SUBTITLE_PREF_OFF
import com.daview.shared.model.StreamType
import com.daview.shared.model.UserDataDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A choice made on one episode has to reach the next one even though the two
 * files number their tracks differently, and a standing preference has to fill
 * in only where nothing was chosen.
 */
class TrackChoiceTest {

    private fun audio(index: Int, language: String?, default: Boolean = false) =
        MediaStreamDto(index = index, type = StreamType.AUDIO, language = language, isDefault = default)

    private fun subtitle(index: Int, language: String?, external: Boolean = false, default: Boolean = false) =
        MediaStreamDto(
            index = index, type = StreamType.SUBTITLE, language = language,
            isExternal = external, isDefault = default
        )

    private fun episode(
        id: String,
        streams: List<MediaStreamDto>,
        audioChoice: Int? = null,
        subtitleChoice: Int? = null
    ) = MediaItemDto(
        id = id,
        libraryId = "lib",
        kind = ItemKind.EPISODE,
        seriesId = "series",
        name = id,
        mediaStreams = streams,
        userData = UserDataDto(audioStreamIndex = audioChoice, subtitleStreamIndex = subtitleChoice)
    )

    @Test
    fun `a choice stored on the item itself wins`() {
        val item = episode("e2", listOf(audio(1, "chi", default = true), audio(2, "jpn")), audioChoice = 1)
        val previous = episode("e1", listOf(audio(1, "chi"), audio(2, "jpn")), audioChoice = 2)

        assertEquals(1, TrackChoice.audio(item, previous, preferred = "ja"))
    }

    @Test
    fun `the previous episode's language carries over even when the index differs`() {
        // Episode one had the Japanese track second; episode two has it third.
        val previous = episode("e1", listOf(audio(1, "chi", default = true), audio(2, "jpn")), audioChoice = 2)
        val item = episode(
            "e2",
            listOf(audio(1, "chi", default = true), audio(2, "eng"), audio(3, "ja"))
        )

        assertEquals(3, TrackChoice.audio(item, previous, preferred = ""))
    }

    @Test
    fun `switching subtitles off carries over as off`() {
        val previous = episode("e1", listOf(subtitle(3, "chs")), subtitleChoice = SUBTITLE_OFF)
        val item = episode("e2", listOf(subtitle(3, "chs", default = true)))

        assertEquals(SUBTITLE_OFF, TrackChoice.subtitle(item, previous, preferred = "zh-Hans"))
    }

    @Test
    fun `an external subtitle chosen before is matched to this episode's external one`() {
        val previous = episode(
            "e1",
            listOf(subtitle(3, "chi"), subtitle(1000, "zh-Hans", external = true)),
            subtitleChoice = 1000
        )
        val item = episode(
            "e2",
            listOf(subtitle(3, "chi", default = true), subtitle(1000, "ja", external = true), subtitle(1001, "zh-Hans", external = true))
        )

        assertEquals(1001, TrackChoice.subtitle(item, previous, preferred = ""))
    }

    @Test
    fun `the preference fills in where nothing was chosen`() {
        val item = episode("e1", listOf(audio(1, "chi", default = true), audio(2, "jpn")))

        assertEquals(2, TrackChoice.audio(item, previous = null, preferred = "ja"))
        assertEquals(1, TrackChoice.audio(item, previous = null, preferred = ""))
    }

    @Test
    fun `a simplified Chinese preference settles for a track that only says Chinese`() {
        val item = episode("e1", listOf(subtitle(3, "eng", default = true), subtitle(4, "chi"), subtitle(5, "cht")))

        assertEquals(4, TrackChoice.subtitle(item, previous = null, preferred = "zh-Hans"))
    }

    @Test
    fun `a preference for no subtitles starts them off`() {
        val item = episode("e1", listOf(subtitle(3, "chs", default = true)))

        assertEquals(SUBTITLE_OFF, TrackChoice.subtitle(item, previous = null, preferred = SUBTITLE_PREF_OFF))
    }

    @Test
    fun `with no language to go on the same position is used`() {
        val previous = episode("e1", listOf(audio(1, null), audio(2, null)), audioChoice = 2)
        val item = episode("e2", listOf(audio(5, null, default = true), audio(6, null)))

        assertEquals(6, TrackChoice.audio(item, previous, preferred = ""))
    }
}
