package com.daview.server.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameParserTest {

    @Test
    fun `parses title and year from folder names`() {
        val info = NameParser.parseTitle("Bocchi the Rock! (2022)")
        assertEquals("Bocchi the Rock!", info.title)
        assertEquals(2022, info.year)

        val nested = NameParser.parseTitle("iPartment The Movie (2018)")
        assertEquals("iPartment The Movie", nested.title)
        assertEquals(2018, nested.year)
    }

    @Test
    fun `extracts pinned provider ids`() {
        val info = NameParser.parseTitle("Spirited Away (2001) [tmdbid-129]")
        assertEquals("Spirited Away", info.title)
        assertEquals(2001, info.year)
        assertEquals("129", info.providerIds["tmdb"])
    }

    @Test
    fun `recognises season folders`() {
        assertEquals(1, NameParser.parseSeasonFolder("Season 01"))
        assertEquals(12, NameParser.parseSeasonFolder("Season 12"))
        assertEquals(2, NameParser.parseSeasonFolder("S2"))
        assertEquals(3, NameParser.parseSeasonFolder("第三季"))
        assertEquals(0, NameParser.parseSeasonFolder("Specials"))
        assertNull(NameParser.parseSeasonFolder("iPartment The Movie (2018)"))
    }

    @Test
    fun `parses episode numbering in the shapes that appear in real libraries`() {
        assertEquals(1 to 1, NameParser.parseEpisode("Bocchi the Rock! - S01E01.mkv", null).let { it!!.season to it.episode })
        assertEquals(2 to 5, NameParser.parseEpisode("iPartment - S02E05.mp4", null).let { it!!.season to it.episode })
        assertEquals(3, NameParser.parseEpisode("[LoliHouse] Oshi no Ko - 03 [WebRip 1080p].mkv", 1)?.episode)
        assertEquals(7, NameParser.parseEpisode("某番 第07话.mkv", 1)?.episode)
        assertEquals(4, NameParser.parseEpisode("Show 1x04.mkv", null)?.episode)
    }

    @Test
    fun `multi episode files keep their range`() {
        val info = NameParser.parseEpisode("Show - S01E01-E02.mkv", null)
        assertEquals(1, info?.season)
        assertEquals(1, info?.episode)
        assertEquals(2, info?.endEpisode)
    }

    @Test
    fun `subtitle names yield language and flags`() {
        val default = NameParser.parseSubtitle("Bocchi the Rock! - S01E01.zh-Hans.default.ass")!!
        assertEquals("Bocchi the Rock! - S01E01", default.videoBaseName)
        assertEquals(listOf("zh-Hans"), default.languages)
        assertTrue(default.isDefault)
        assertFalse(default.isForced)
        assertEquals("ass", default.extension)

        val traditional = NameParser.parseSubtitle("Bocchi the Rock! - S01E01.zh-Hant.ass")!!
        assertEquals(listOf("zh-Hant"), traditional.languages)
        assertFalse(traditional.isDefault)

        val bilingual = NameParser.parseSubtitle("Spirited Away (2001).zh-Hans.ja.ass")!!
        assertEquals("Spirited Away (2001)", bilingual.videoBaseName)
        assertEquals(listOf("zh-Hans", "ja"), bilingual.languages)
        assertEquals("zh-Hans/ja", bilingual.title)
    }

    @Test
    fun `subtitle without language tags still matches its video`() {
        val plain = NameParser.parseSubtitle("Some Movie (2020).srt")!!
        assertEquals("Some Movie (2020)", plain.videoBaseName)
        assertTrue(plain.languages.isEmpty())
    }

    @Test
    fun `junk and extras are filtered out`() {
        assertTrue(NameParser.isJunkFile("搬运工.url"))
        assertTrue(NameParser.isJunkFile("Thumbs.db"))
        assertTrue(NameParser.isJunkFile("Movie-sample.mkv"))
        assertFalse(NameParser.isJunkFile("Bocchi the Rock! - S01E01.mkv"))
        assertTrue(NameParser.isExtrasFolder("Extras"))
        assertTrue(NameParser.isExtrasFolder("花絮"))
        assertFalse(NameParser.isExtrasFolder("Season 01"))
    }

    @Test
    fun `sort names drop leading articles and pad numbers`() {
        assertTrue(NameParser.sortName("The Matrix") < NameParser.sortName("Matrix Reloaded"))
        assertTrue(NameParser.sortName("Episode 2") < NameParser.sortName("Episode 10"))
    }
}
