package com.daview.server.library

import com.daview.shared.model.StreamType
import com.daview.server.library.TransportStreams.Es
import com.daview.server.library.TransportStreams.MPEG2_SEQUENCE
import com.daview.server.library.TransportStreams.annexB
import com.daview.server.library.TransportStreams.clip
import com.daview.server.library.TransportStreams.descriptor
import com.daview.server.library.TransportStreams.kaiji
import com.daview.server.library.TransportStreams.language
import com.daview.server.library.TransportStreams.reader
import com.daview.server.library.TransportStreams.sps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transport stream probe, over files put together packet by packet here:
 * the tables, the timestamps at both ends and the picture size, in the packet
 * sizes Blu-ray and broadcast use. The track list has to come out the way
 * libavformat makes it, since the desktop player matches the two by position.
 */
class TsProbeTest {

    @Test
    fun `a Blu-ray clip lists its tracks by PID with the runtime between its first and last frame`() {
        val file = kaiji(packetSize = 192)

        val info = TsProbe.probe(reader(file), file.size.toLong())!!

        assertEquals(
            listOf(
                Triple(4113, StreamType.VIDEO, "h264"),
                Triple(4352, StreamType.AUDIO, "dts"),
                Triple(4608, StreamType.SUBTITLE, "pgs")
            ),
            info.streams.map { Triple(it.index, it.type, it.codec) }
        )
        assertEquals(1_357_354L, info.durationMs)
        val video = info.streams.first()
        assertEquals(1920 to 1080, video.width to video.height)
        assertTrue(info.streams.none { it.isDefault || it.isExternal || it.language != null })
    }

    @Test
    fun `plain 188-byte packets read the same`() {
        val file = kaiji(packetSize = 188)

        val info = TsProbe.probe(reader(file), file.size.toLong())!!

        assertEquals(listOf(4113, 4352, 4608), info.streams.map { it.index })
        assertEquals(1_357_354L, info.durationMs)
    }

    @Test
    fun `a TrueHD stream is followed by the AC-3 track libavformat makes of its core`() {
        val file = clip(
            hdmv = true,
            streams = listOf(
                Es(0x1B, 0x1011),
                Es(0x83, 0x1100),
                Es(0x81, 0x1101),
                Es(0x91, 0x1400),
                Es(0x92, 0x1800),
                Es(0xEA, 0x1012)
            )
        )

        val info = TsProbe.probe(reader(file), file.size.toLong())!!

        assertEquals(
            listOf(
                Triple(0x1011, StreamType.VIDEO, "h264"),
                Triple(0x1100, StreamType.AUDIO, "truehd"),
                Triple(0x1100 + TsProbe.TRUEHD_CORE_OFFSET, StreamType.AUDIO, "ac3"),
                Triple(0x1101, StreamType.AUDIO, "ac3"),
                // The menu's buttons (0x91) are not a track anyone plays.
                Triple(0x1800, StreamType.SUBTITLE, "textst"),
                Triple(0x1012, StreamType.VIDEO, "vc1")
            ),
            info.streams.map { Triple(it.index, it.type, it.codec) }
        )
    }

    @Test
    fun `without the HDMV registration the Blu-ray stream types mean nothing`() {
        val file = clip(
            hdmv = false,
            streams = listOf(Es(0x1B, 0x200), Es(0x86, 0x201), Es(0x90, 0x202), Es(0x81, 0x203), Es(0x87, 0x204))
        )

        val info = TsProbe.probe(reader(file), file.size.toLong())!!

        assertEquals(listOf("h264", "ac3", "eac3"), info.streams.map { it.codec })
    }

    @Test
    fun `a stream the tables leave unnamed is listed by the audio it carries, as libavformat probes it`() {
        val dts = byteArrayOf(0x7F, 0xFE.toByte(), 0x80.toByte(), 0x01, 0, 0) + ByteArray(300)
        val file = clip(hdmv = false, streams = listOf(Es(0x1B, 0x1011), Es(0x86, 0x1100)), audioPayload = dts)

        assertEquals(listOf("h264", "dts"), TsProbe.probe(reader(file), file.size.toLong())!!.streams.map { it.codec })
    }

    @Test
    fun `a stream that carries nothing recognisable stays unlisted`() {
        val file = clip(hdmv = false, streams = listOf(Es(0x1B, 0x1011), Es(0x86, 0x1100)))

        assertEquals(listOf("h264"), TsProbe.probe(reader(file), file.size.toLong())!!.streams.map { it.codec })
    }

    @Test
    fun `HDPR counts as a Blu-ray registration too`() {
        val file = clip(hdmv = true, registration = "HDPR", streams = listOf(Es(0x1B, 0x1011), Es(0x90, 0x1200)))

        assertEquals(listOf("h264", "pgs"), TsProbe.probe(reader(file), file.size.toLong())!!.streams.map { it.codec })
    }

    @Test
    fun `private data streams are known by their descriptors, and languages by ISO 639`() {
        val file = clip(
            hdmv = false,
            streams = listOf(
                Es(0x02, 0x200),
                Es(0x06, 0x201, descriptor(0x6A, 0) + language("jpn")),
                Es(0x06, 0x202, descriptor(0x59, 0x7A, 0x68, 0x6F, 0x10, 0, 1, 0, 1) + language("zho")),
                Es(0x06, 0x203, descriptor(0x05, 'E'.code, 'A'.code, 'C'.code, '3'.code)),
                Es(0x0F, 0x204, language("und")),
                Es(0x06, 0x205)
            )
        )

        val streams = TsProbe.probe(reader(file), file.size.toLong())!!.streams

        assertEquals(
            listOf(
                Triple("mpeg2", StreamType.VIDEO, null),
                Triple("ac3", StreamType.AUDIO, "jpn"),
                Triple("dvbsub", StreamType.SUBTITLE, "zho"),
                Triple("eac3", StreamType.AUDIO, null),
                Triple("aac", StreamType.AUDIO, null)
            ),
            streams.map { Triple(it.codec, it.type, it.language) }
        )
    }

    @Test
    fun `an MPEG-2 sequence header gives the picture size`() {
        val file = clip(hdmv = false, streams = listOf(Es(0x02, 0x200)), videoPayload = MPEG2_SEQUENCE)

        val video = TsProbe.probe(reader(file), file.size.toLong())!!.streams.single()

        assertEquals(1920 to 1080, video.width to video.height)
    }

    @Test
    fun `a progressive stream is not doubled in height`() {
        val sps = sps(widthInMbs = 80, heightInMapUnits = 45, frameMbsOnly = true, cropBottom = 0)
        val file = clip(hdmv = true, streams = listOf(Es(0x1B, 0x1011)), videoPayload = annexB(sps))

        val video = TsProbe.probe(reader(file), file.size.toLong())!!.streams.single()

        assertEquals(1280 to 720, video.width to video.height)
    }

    @Test
    fun `the runtime carries on past the 33-bit timestamp rollover`() {
        val start = (1L shl 33) - 90_000L * 10
        val file = clip(hdmv = true, streams = listOf(Es(0x1B, 0x1011), Es(0x86, 0x1100)), firstPts = start, lastPts = (start + 90_000L * 25) % (1L shl 33))

        assertEquals(25_000L, TsProbe.probe(reader(file), file.size.toLong())!!.durationMs)
    }

    @Test
    fun `a program map longer than one packet is put back together`() {
        val many = (0 until 12).map { Es(0x81, 0x1100 + it, language("jpn") + descriptor(0x7F, *IntArray(10) { 0 })) }
        val file = clip(hdmv = true, streams = listOf(Es(0x1B, 0x1011)) + many)

        val streams = TsProbe.probe(reader(file), file.size.toLong())!!.streams

        assertEquals(13, streams.size)
        assertEquals(0x1100 + 11, streams.last().index)
    }

    @Test
    fun `a file cut in the middle of a packet is found from the next one`() {
        val file = byteArrayOf(0x47, 1, 2, 0x47, 0x47, 5, 6) + kaiji(packetSize = 192)

        val info = TsProbe.probe(reader(file), file.size.toLong())!!

        assertEquals(listOf(4113, 4352, 4608), info.streams.map { it.index })
        assertEquals(1_357_354L, info.durationMs)
    }

    @Test
    fun `no timestamp near the end leaves the runtime unknown`() {
        val file = clip(hdmv = true, streams = listOf(Es(0x1B, 0x1011)), lastPts = null, filler = 6 * 1024 * 1024)

        val info = TsProbe.probe(reader(file), file.size.toLong())!!

        assertEquals(1, info.streams.size)
        assertNull(info.durationMs)
    }

    @Test
    fun `an unknown size leaves the runtime unknown but still lists the tracks`() {
        val file = kaiji(packetSize = 192)

        val info = TsProbe.probe(reader(file), null)!!

        assertEquals(3, info.streams.size)
        assertNull(info.durationMs)
    }

    @Test
    fun `PIDs in the range of subtitle files are numbered in order instead`() {
        val file = clip(hdmv = false, streams = listOf(Es(0x1B, 1000), Es(0x81, 1001), Es(0x81, 1002)))

        assertEquals(listOf(1, 2, 3), TsProbe.probe(reader(file), file.size.toLong())!!.streams.map { it.index })
    }

    @Test
    fun `something that is not a transport stream is not probed`() {
        val matroska = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(4096) { (it * 31).toByte() }

        assertNull(TsProbe.probe(reader(matroska), matroska.size.toLong()))
    }

    @Test
    fun `a damaged program map is not trusted`() {
        val file = kaiji(packetSize = 192, breakPmtCrc = true)

        assertNull(TsProbe.probe(reader(file), file.size.toLong()))
    }

    @Test
    fun `arrival stamps that hold 0x47 are not taken for the sync bytes`() {
        // The stamp's first two bytes change slowly; here they are 0x47 for
        // every packet, and the file starts part-way into a packet, so a run of
        // stamp bytes comes before the first run of sync bytes.
        listOf(0x47000000, 0x00470000, 0x47470000).forEach { high ->
            val file = ByteArray(100) + kaiji(packetSize = 192, stamp = { high or (it and 0xFFFF) })

            val framing = TsProbe.framing(file, file.size, 8)!!

            assertEquals(192, framing.packetSize)
            assertEquals(100, framing.start)
            assertEquals(listOf(4113, 4352, 4608), TsProbe.probe(reader(file), file.size.toLong())!!.streams.map { it.index })
        }
    }

    @Test
    fun `transport stream names`() {
        assertTrue(listOf("a.ts", "B.M2TS", "c.mts", "d.m2t").all(TsProbe::isTransportStream))
        assertTrue(listOf("a.mkv", "b.mp4", "ts", "c.tsx").none(TsProbe::isTransportStream))
    }
}
