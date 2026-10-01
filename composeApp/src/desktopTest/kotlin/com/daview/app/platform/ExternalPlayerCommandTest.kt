package com.daview.app.platform

import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.SUBTITLE_OFF
import com.daview.shared.model.StreamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tracks an external player is told to start on. DAView's own stream
 * numbers — a Matroska track number, a Blu-ray PID, 1000 and up for a file
 * beside the video — mean nothing to mpv or VLC, which number each type's
 * tracks themselves, and not the same way: VLC makes one track of a Blu-ray
 * TrueHD stream where mpv makes two.
 */
class ExternalPlayerCommandTest {

    /** A Blu-ray clip: TrueHD and the AC-3 core libavformat lists after it, a commentary, PGS, and a file beside it. */
    private val streams = listOf(
        MediaStreamDto(index = 4113, type = StreamType.VIDEO, codec = "h264"),
        MediaStreamDto(index = 4352, type = StreamType.AUDIO, codec = "truehd"),
        MediaStreamDto(index = 4352 + 0x2000, type = StreamType.AUDIO, codec = "ac3"),
        MediaStreamDto(index = 4353, type = StreamType.AUDIO, codec = "ac3"),
        MediaStreamDto(index = 4608, type = StreamType.SUBTITLE, codec = "pgs"),
        MediaStreamDto(index = 4609, type = StreamType.SUBTITLE, codec = "pgs"),
        MediaStreamDto(index = 1000, type = StreamType.SUBTITLE, codec = "ass", isExternal = true)
    )

    private fun command(player: String, audio: Int?, subtitle: Int?) = buildCommand(
        ExternalPlayRequest(
            player = ExternalPlayerInfo(id = player, label = player, executablePath = player),
            streamUrl = "http://127.0.0.1:1/p/s/i/Kaiji%20-%20S02E03.m2ts",
            title = "Kaiji",
            startPositionMs = 0,
            audioIndex = audio,
            subtitleIndex = subtitle,
            streams = streams
        ),
        player
    )

    @Test
    fun `mpv counts each type from 1, the TrueHD core included`() {
        assertTrue("--aid=3" in command("mpv", audio = 4353, subtitle = null))
        assertTrue("--aid=2" in command("mpv", audio = 4352 + 0x2000, subtitle = null))
        assertTrue("--sid=2" in command("mpv", audio = null, subtitle = 4609))
    }

    @Test
    fun `VLC counts from 0 and has no track for the TrueHD core`() {
        assertTrue("--audio-track=1" in command("vlc", audio = 4353, subtitle = null))
        assertTrue("--audio-track=0" in command("vlc", audio = 4352, subtitle = null))
        // The core is the TrueHD's own audio; in VLC that is the TrueHD track.
        assertTrue("--audio-track=0" in command("vlc", audio = 4352 + 0x2000, subtitle = null))
        assertTrue("--sub-track=1" in command("vlc", audio = null, subtitle = 4609))
    }

    @Test
    fun `VLC is left to pick a subtitle that comes after a teletext stream`() {
        // VLC makes a track of every teletext page, so the count is not known.
        val broadcast = listOf(
            MediaStreamDto(index = 0x100, type = StreamType.VIDEO, codec = "mpeg2"),
            MediaStreamDto(index = 0x102, type = StreamType.SUBTITLE, codec = "teletext"),
            MediaStreamDto(index = 0x103, type = StreamType.SUBTITLE, codec = "dvbsub")
        )
        val command = buildCommand(
            ExternalPlayRequest(
                player = ExternalPlayerInfo(id = "vlc", label = "vlc", executablePath = "vlc"),
                streamUrl = "http://127.0.0.1:1/p/s/i/capture.ts",
                title = "capture",
                startPositionMs = 0,
                subtitleIndex = 0x103,
                streams = broadcast
            ),
            "vlc"
        )

        assertTrue(command.none { it.startsWith("--sub-track") })
    }

    @Test
    fun `a file beside the video goes as a file, not as a track number`() {
        assertTrue(command("mpv", audio = null, subtitle = 1000).none { it.startsWith("--sid") })
        assertTrue(command("vlc", audio = null, subtitle = 1000).none { it.startsWith("--sub-track") })
    }

    @Test
    fun `subtitles switched off stay off in mpv and are left alone in VLC`() {
        assertTrue("--sid=no" in command("mpv", audio = null, subtitle = SUBTITLE_OFF))
        assertTrue(command("vlc", audio = null, subtitle = SUBTITLE_OFF).none { it.startsWith("--sub-track") })
    }

    @Test
    fun `an index the file does not have is not passed on`() {
        val mpv = command("mpv", audio = 99, subtitle = 98)
        assertEquals(0, mpv.count { it.startsWith("--aid") || it.startsWith("--sid") })
    }
}
