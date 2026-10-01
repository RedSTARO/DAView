package com.daview.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daview.server.library.TsProbe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Runs the extractor over a small Blu-ray transport stream built here packet
 * by packet: the program tables of a disc, a clock reference at each end, an
 * LPCM track and a PGS track. The stream has to be recognised by its 192-byte
 * packets alone, its audio has to come out as media3 can play it, its
 * subtitles one display set at a time, and every position it gives out has to
 * be a byte of the file as it is, timestamps included. Damaged and unusual
 * copies of the same stream — bytes missing in the middle, tables far in, a
 * subtitle segment with a wrong length — must cost only what was damaged. A
 * second stream holds a TrueHD track with a plain AC-3 one beside it; of the
 * TrueHD only its AC-3 core may come out, as a track of its own.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class BluRayExtractorsOnDeviceTest {

    @Test
    fun onlyTheStreamWithArrivalTimestampsIsTakenAsABluRay() {
        assertTrue(extractor().sniff(input(stream(bdav = true))))
        // The same packets without the timestamps are an ordinary transport
        // stream, which media3's own extractor reads.
        assertFalse(extractor().sniff(input(stream(bdav = false))))
        assertFalse(extractor().sniff(input(MATROSKA_HEAD)))
        assertFalse(extractor().sniff(input(ByteArray(100))))
    }

    @Test
    fun surroundLpcmComesOutInTheOrderMedia3PlaysIt() {
        val output = extract(stream(bdav = true, assignment = 9, bitsCode = 3))

        val audio = output.tracks.getValue(LPCM_PID)
        val format = checkNotNull(audio.format)
        assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
        assertEquals(C.ENCODING_PCM_24BIT_BIG_ENDIAN, format.pcmEncoding)
        assertEquals(6, format.channelCount)
        assertEquals(48_000, format.sampleRate)
        val (timeUs, bytes) = audio.samples.first()
        assertEquals(0L, timeUs)
        assertEquals(FRAMES_PER_PES * 6 * 3, bytes.size)
        // Coded L R C LS RS LFE; played FL FR FC LFE BL BR.
        val firstFrame = listOf(0, 1, 2, 5, 3, 4).flatMap { sample(0, 0, it, 3).toList() }.toByteArray()
        assertArrayEquals(firstFrame, bytes.copyOfRange(0, 18))
        assertEquals(PES_COUNT, audio.samples.size)
        // One sample per PES packet, each at its own packet's time: 16 frames later.
        assertEquals(FRAMES_PER_PES * 1_000_000.0 / 48_000, audio.samples[1].first.toDouble(), 1.0)
    }

    @Test
    fun monoLpcmLosesItsPaddingChannel() {
        val output = extract(stream(bdav = true, assignment = 1, bitsCode = 1))

        val audio = output.tracks.getValue(LPCM_PID)
        val format = checkNotNull(audio.format)
        assertEquals(C.ENCODING_PCM_16BIT_BIG_ENDIAN, format.pcmEncoding)
        assertEquals(1, format.channelCount)
        val bytes = audio.samples.first().second
        assertEquals(FRAMES_PER_PES * 2, bytes.size)
        val expected = (0 until FRAMES_PER_PES).flatMap { sample(0, it, 0, 2).toList() }.toByteArray()
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun eachDisplaySetIsOneSubtitleSampleAtItsCompositionTime() {
        val extractors = BluRayExtractors(ExtractorsFactory.EMPTY).experimentalSetTextTrackTranscodingEnabled(false)
        val output = extract(stream(bdav = true), extractors.createExtractors().single())

        val pgs = output.tracks.getValue(PGS_PID)
        assertEquals(MimeTypes.APPLICATION_PGS, pgs.format?.sampleMimeType)
        assertEquals(listOf(1_000_000L, 2_000_000L), pgs.samples.map { it.first })
        assertArrayEquals(DISPLAY_SETS[0].reduce { all, next -> all + next }, pgs.samples[0].second)
        assertArrayEquals(DISPLAY_SETS[1].reduce { all, next -> all + next }, pgs.samples[1].second)
    }

    @Test
    fun parsedDisplaySetsKeepTheirTimes() {
        val output = extract(stream(bdav = true))

        val pgs = output.tracks.getValue(PGS_PID)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, pgs.format?.sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_PGS, pgs.format?.codecs)
        assertEquals(listOf(1_000_000L, 2_000_000L), pgs.samples.map { it.first })
    }

    @Test
    fun seekPointsAreBytesOfTheFileItself() {
        val bytes = stream(bdav = true)
        val output = extract(bytes)

        val seekMap = checkNotNull(output.seekMap)
        assertTrue(seekMap.isSeekable)
        assertEquals(3_000_000L, seekMap.durationUs)
        // The first packet byte of the file is after the first timestamp.
        assertEquals(4L, seekMap.getSeekPoints(0).first.position)
        for (timeUs in longArrayOf(500_000, 1_500_000, 2_900_000)) {
            val position = seekMap.getSeekPoints(timeUs).first.position
            assertTrue("$position is inside a timestamp", position % 192 >= 4)
            assertTrue(position < bytes.size)
        }
        // Every position the extractor asked to move to was a packet byte too.
        assertTrue(output.seeks.isNotEmpty())
        output.seeks.forEach { assertTrue("$it is inside a timestamp", it % 192 >= 4) }
    }

    @Test
    fun bytesLostPartWayThroughCostOnlyThePacketsAroundTheGap() {
        val whole = stream(bdav = true)
        // 100 bytes gone from the middle of the fourth LPCM packet (the
        // twelfth packet of the file), as when a copy leaves out what it
        // could not read. Every packet after that is 100 bytes early.
        val at = 11 * 192 + 100
        val damaged = whole.copyOfRange(0, at) + whole.copyOfRange(at + 100, whole.size)

        val clean = extract(whole).tracks.getValue(LPCM_PID).samples
        val output = extract(damaged)

        // The packet the gap fell in and the one cut in half by it are lost;
        // the rest come out exactly as from the whole file.
        val audio = output.tracks.getValue(LPCM_PID).samples
        assertEquals(clean.map { it.first } - clean[3].first - clean[4].first, audio.map { it.first })
        for ((timeUs, bytes) in audio) assertArrayEquals(clean.single { it.first == timeUs }.second, bytes)
        assertEquals(listOf(1_000_000L, 2_000_000L), output.tracks.getValue(PGS_PID).samples.map { it.first })
        // Positions still count in the file as it is.
        output.seeks.forEach { assertTrue("$it is inside a timestamp", it % 192 >= 4) }
    }

    @Test
    fun tablesFarIntoTheFileStillSayWhatKindOfProgramItIs() {
        // A clip cut out of a longer one: its tables come round after 2000
        // packets, well past what the first look at the file takes in.
        val disc = extract(stream(bdav = true, fillerPackets = 2000))
        assertEquals(C.TRACK_TYPE_AUDIO, disc.tracks.getValue(LPCM_PID).type)
        assertEquals(PES_COUNT, disc.tracks.getValue(LPCM_PID).samples.size)
        assertEquals(2, disc.tracks.getValue(PGS_PID).samples.size)

        // The same packets in a program that is not a Blu-ray's, as in a
        // broadcast recording with timestamps: there 0x80 is MPEG-2 video and
        // 0x90 nothing media3 reads.
        val capture = extract(stream(bdav = true, fillerPackets = 2000, hdmv = false))
        assertEquals(C.TRACK_TYPE_VIDEO, capture.tracks.getValue(LPCM_PID).type)
        assertFalse(PGS_PID in capture.tracks)
    }

    @Test
    fun aSubtitleSegmentWithAWrongLengthCostsOnlyItself() {
        val extractors = BluRayExtractors(ExtractorsFactory.EMPTY).experimentalSetTextTrackTranscodingEnabled(false)
        // A window segment that says it is 65520 bytes long, in a packet of
        // its own before the first display set.
        val output = extract(stream(bdav = true, brokenSegment = true), extractors.createExtractors().single())

        val pgs = output.tracks.getValue(PGS_PID)
        assertEquals(listOf(1_000_000L, 2_000_000L), pgs.samples.map { it.first })
        assertArrayEquals(DISPLAY_SETS[0].reduce { all, next -> all + next }, pgs.samples[0].second)
    }

    @Test
    fun trueHdComesOutAsItsAc3CoreAtTheIndexTheServerListsIt() {
        val output = extract(trueHdStream())

        // The TrueHD itself has no track. Its AC-3 has one under the index the
        // server's probe gives it, which is what the player matches it by.
        assertFalse(TRUEHD_PID in output.tracks)
        val core = output.tracks.getValue(TRUEHD_PID + TsProbe.TRUEHD_CORE_OFFSET)
        val format = checkNotNull(core.format)
        assertEquals("1/${TRUEHD_PID + TsProbe.TRUEHD_CORE_OFFSET}", format.id)
        assertEquals(MimeTypes.AUDIO_AC3, format.sampleMimeType)
        assertEquals(6, format.channelCount)
        assertEquals(48_000, format.sampleRate)
        // Every frame whole, at its own packet's time.
        assertEquals((0 until CORE_FRAMES).map { it * FRAME_US }, core.samples.map { it.first })
        core.samples.forEachIndexed { n, (_, bytes) -> assertArrayEquals(ac3Frame(CORE_FILL + n), bytes) }
        // A plain AC-3 track beside it keeps its PID.
        val plain = output.tracks.getValue(AC3_PID)
        assertEquals("1/$AC3_PID", plain.format?.id)
        assertEquals(CORE_FRAMES, plain.samples.size)
    }

    @Test
    fun onlyTheAc3PacketsOfATrueHdStreamReachItsTrack() {
        // Before every core packet, at the same time: a TrueHD packet, one with
        // no PES extension, which FFmpeg counts as TrueHD as well, and one with
        // another stream_id_extension. Each holds an AC-3 frame of its own.
        val output = extract(trueHdStream(decoys = true))

        val core = output.tracks.getValue(TRUEHD_PID + TsProbe.TRUEHD_CORE_OFFSET)
        assertEquals((0 until CORE_FRAMES).map { CORE_FILL + it }, core.samples.map { it.second.last().toInt() })
        assertEquals((0 until CORE_FRAMES).map { it * FRAME_US }, core.samples.map { it.first })
    }

    @Test
    fun aCoreFrameWithABrokenHeaderCostsOnlyItself() {
        // media3's own AC-3 reader looks the broken frame size code up in its
        // table unchecked, and the exception would end playback.
        val output = extract(trueHdStream(damaged = true))

        val core = output.tracks.getValue(TRUEHD_PID + TsProbe.TRUEHD_CORE_OFFSET)
        assertEquals((0 until CORE_FRAMES).map { CORE_FILL + it }, core.samples.map { it.second.last().toInt() })
        assertEquals((0 until CORE_FRAMES).map { it * FRAME_US }, core.samples.map { it.first })
    }

    @Test
    fun aTrueHdStreamWithoutItsCoreGetsNoTrackAndHoldsNothingUp() {
        // A remux that kept the TrueHD alone: a core track would never get a
        // format, and media3 starts nothing until every track has one. Over
        // half a second of TrueHD with no core frame is what gives it away.
        val output = extract(trueHdStream(decoys = true, cores = false, frames = LONG_FRAMES))

        assertFalse(TRUEHD_PID in output.tracks)
        assertFalse(TRUEHD_PID + TsProbe.TRUEHD_CORE_OFFSET in output.tracks)
        assertTrue(output.tracks.values.all { it.format != null })
        assertEquals(LONG_FRAMES, output.tracks.getValue(AC3_PID).samples.size)
    }

    @Test
    fun aTrueHdWithoutItsCoreBesideOneWithACoreCostsOnlyItself() {
        val output = extract(trueHdStream(frames = LONG_FRAMES, corelessBeside = true))

        assertEquals(LONG_FRAMES, output.tracks.getValue(TRUEHD_PID + TsProbe.TRUEHD_CORE_OFFSET).samples.size)
        assertFalse(CORELESS_PID + TsProbe.TRUEHD_CORE_OFFSET in output.tracks)
        assertTrue(output.tracks.values.all { it.format != null })
    }

    private fun extractor(): Extractor = BluRayExtractors(ExtractorsFactory.EMPTY).createExtractors().single()

    private fun extract(bytes: ByteArray, extractor: Extractor = extractor()): RecordingOutput {
        assertTrue(extractor.sniff(input(bytes)))
        val output = RecordingOutput()
        extractor.init(output)
        var input = input(bytes)
        val seek = PositionHolder()
        while (true) {
            when (extractor.read(input, seek)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> {
                    output.seeks += seek.position
                    input = input(bytes, seek.position)
                }
            }
        }
        return output
    }

    private fun input(bytes: ByteArray, position: Long = 0): DefaultExtractorInput {
        val source = ByteArrayDataSource(bytes)
        source.open(DataSpec.Builder().setUri(Uri.EMPTY).setPosition(position).build())
        return DefaultExtractorInput(source, position, bytes.size.toLong())
    }

    // --- a Blu-ray transport stream, packet by packet -----------------------

    /**
     * PAT, PMT, a PCR, then the LPCM packets with the two PGS display sets
     * between them, and a PCR three seconds after the first. [assignment] and
     * [bitsCode] go into every LPCM header. [fillerPackets] packets with
     * nothing but a clock reference go before the tables; [hdmv] false leaves
     * the Blu-ray registration out of the program map; [brokenSegment] puts a
     * PGS segment with a wrong length before the first display set.
     */
    private fun stream(
        bdav: Boolean,
        assignment: Int = 3,
        bitsCode: Int = 1,
        fillerPackets: Int = 0,
        hdmv: Boolean = true,
        brokenSegment: Boolean = false
    ): ByteArray {
        val packets = Packets(bdav)
        repeat(fillerPackets) { packets.pcr(START_PTS - 9_000) }
        packets.section(0, pat())
        packets.section(PMT_PID, pmt(hdmv))
        packets.pcr(START_PTS - 9_000)
        repeat(PES_COUNT) { n ->
            packets.pes(LPCM_PID, START_PTS + n * PTS_PER_PES, lpcm(n, assignment, bitsCode))
            when (n) {
                0 -> if (brokenSegment) packets.pes(PGS_PID, START_PTS + 45_000, byteArrayOf(0x17, 0xFF.toByte(), 0xF0.toByte(), 0))
                2 -> DISPLAY_SETS[0].forEachIndexed { i, segment -> packets.pes(PGS_PID, START_PTS + 90_000 + i * 900, segment) }
                5 -> DISPLAY_SETS[1].forEachIndexed { i, segment -> packets.pes(PGS_PID, START_PTS + 180_000 + i * 900, segment) }
            }
        }
        packets.pcr(START_PTS - 9_000 + 270_000)
        return packets.bytes()
    }

    private fun lpcm(n: Int, assignment: Int, bitsCode: Int): ByteArray {
        val coded = if (assignment == 9) 6 else 2
        val sampleBytes = if (bitsCode == 1) 2 else 3
        val out = ByteArrayOutputStream()
        val size = FRAMES_PER_PES * coded * sampleBytes
        out.write(byteArrayOf((size shr 8).toByte(), size.toByte(), ((assignment shl 4) or 1).toByte(), (bitsCode shl 6).toByte()))
        repeat(FRAMES_PER_PES) { frame -> repeat(coded) { channel -> out.write(sample(n, frame, channel, sampleBytes)) } }
        return out.toByteArray()
    }

    /** A sample whose bytes say which packet, frame and coded channel it is. */
    private fun sample(n: Int, frame: Int, channel: Int, sampleBytes: Int): ByteArray =
        if (sampleBytes == 2) byteArrayOf((n * 16 + channel).toByte(), frame.toByte())
        else byteArrayOf((n * 16 + channel).toByte(), frame.toByte(), 0x5A)

    /**
     * PAT, PMT and a PCR, then [CORE_FRAMES] AC-3 frames as the core of a
     * TrueHD stream, a frame to a packet as on a disc, as many frames on a
     * plain AC-3 stream beside it, and a PCR at the end. With [decoys], each
     * core packet comes after a TrueHD packet, one with no PES extension and
     * one with another stream_id_extension, each of them holding an AC-3 frame
     * too. [damaged] puts a frame with a broken header before the first core
     * frame.
     */
    private fun trueHdStream(
        decoys: Boolean = false,
        damaged: Boolean = false,
        cores: Boolean = true,
        frames: Int = CORE_FRAMES,
        corelessBeside: Boolean = false
    ): ByteArray {
        val packets = Packets(bdav = true)
        packets.section(0, pat())
        val beside = if (corelessBeside) listOf(0x83 to CORELESS_PID) else emptyList()
        packets.section(PMT_PID, pmt(hdmv = true, streams = listOf(0x83 to TRUEHD_PID, 0x81 to AC3_PID) + beside))
        packets.pcr(START_PTS - 9_000)
        repeat(frames) { n ->
            val pts = START_PTS + n * FRAME_TICKS
            if (corelessBeside) packets.pes(CORELESS_PID, pts, TRUEHD_MAJOR_SYNC + ac3Frame(DECOY_FILL), extension = 0x72)
            if (decoys) {
                packets.pes(TRUEHD_PID, pts, TRUEHD_MAJOR_SYNC + ac3Frame(DECOY_FILL), extension = 0x72)
                packets.pes(TRUEHD_PID, pts, ac3Frame(DECOY_FILL + 1))
                packets.pes(TRUEHD_PID, pts, ac3Frame(DECOY_FILL + 2), extension = 0x71)
            }
            val frame = ac3Frame(CORE_FILL + n)
            // A frame size code past the end of the table.
            val broken = ac3Frame(DECOY_FILL + 3).also { it[4] = 0x3F }
            if (cores) packets.pes(TRUEHD_PID, pts, if (damaged && n == 0) broken + frame else frame, extension = 0x76)
            packets.pes(AC3_PID, pts, ac3Frame(PLAIN_FILL + n))
        }
        packets.pcr(START_PTS - 9_000 + 90_000)
        return packets.bytes()
    }

    /** Program 1, its map on [PMT_PID]. */
    private fun pat(): ByteArray = section(0x00, 0x0001, byteArrayOf(0x00, 0x01) + pid(PMT_PID))

    /** The PCR PID, the registration a disc's program has ([hdmv]), and [streams], each a stream type and its PID. */
    private fun pmt(hdmv: Boolean, streams: List<Pair<Int, Int>> = listOf(0x80 to LPCM_PID, 0x90 to PGS_PID)): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(pid(PCR_PID) + byteArrayOf(0xF0.toByte(), if (hdmv) 6 else 0))
        if (hdmv) body.write(byteArrayOf(0x05, 4) + "HDMV".toByteArray(Charsets.US_ASCII))
        for ((type, pid) in streams) {
            body.write(byteArrayOf(type.toByte()) + pid(pid) + byteArrayOf(0xF0.toByte(), 0))
        }
        return section(0x02, 0x0001, body.toByteArray())
    }

    /** A PID with the three reserved bits before it set. */
    private fun pid(pid: Int): ByteArray = byteArrayOf((0xE0 or (pid shr 8)).toByte(), pid.toByte())

    /** A long-form PSI section with its CRC-32/MPEG-2. */
    private fun section(tableId: Int, idExtension: Int, body: ByteArray): ByteArray {
        val length = 5 + body.size + 4
        val head = byteArrayOf(
            tableId.toByte(), (0xB0 or (length shr 8)).toByte(), length.toByte(),
            (idExtension shr 8).toByte(), idExtension.toByte(), 0xC1.toByte(), 0, 0
        )
        val section = head + body
        val crc = crc32(section)
        return section + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    private fun crc32(bytes: ByteArray): Int {
        var crc = -1
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    /** TS packets, each with a four-byte arrival timestamp in front when [bdav]. */
    private class Packets(private val bdav: Boolean) {
        private val out = ByteArrayOutputStream()
        private val counters = HashMap<Int, Int>()
        private var arrival = 0

        fun bytes(): ByteArray = out.toByteArray()

        fun section(pid: Int, section: ByteArray) {
            packet(pid, unitStart = true, adaptation = null, payload = byteArrayOf(0) + section)
        }

        fun pcr(base: Long) {
            val field = ByteArray(183) { 0xFF.toByte() }
            field[0] = 0x10
            field[1] = (base shr 25).toByte()
            field[2] = (base shr 17).toByte()
            field[3] = (base shr 9).toByte()
            field[4] = (base shr 1).toByte()
            field[5] = (((base and 1) shl 7) or 0x7E).toInt().toByte()
            field[6] = 0
            packet(PCR_PID, unitStart = false, adaptation = field, payload = null)
        }

        /**
         * A PES packet with a PTS. An [extension] goes in the way a disc has
         * a stream_id_extension: stream_id 0xFD, and a PES extension holding
         * nothing else.
         */
        fun pes(pid: Int, pts: Long, payload: ByteArray, extension: Int? = null) {
            val tail = if (extension == null) ByteArray(0) else byteArrayOf(0x01, 0x81.toByte(), extension.toByte())
            val length = payload.size + 8 + tail.size
            val header = byteArrayOf(
                0, 0, 1, (if (extension == null) 0xBD else 0xFD).toByte(),
                (length shr 8).toByte(), length.toByte(),
                // data_alignment_indicator set, as a disc has it: the payload
                // starts with something whole (a PGS segment, an LPCM header,
                // an AC-3 frame).
                0x84.toByte(), (if (extension == null) 0x80 else 0x81).toByte(), (5 + tail.size).toByte(),
                (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(),
                (pts shr 22).toByte(), (((pts shr 14) and 0xFE) or 1).toByte(),
                (pts shr 7).toByte(), (((pts shl 1) and 0xFE) or 1).toByte()
            ) + tail
            val pes = header + payload
            var at = 0
            while (at < pes.size) {
                val left = pes.size - at
                if (left >= 184) {
                    packet(pid, unitStart = at == 0, adaptation = null, payload = pes.copyOfRange(at, at + 184))
                    at += 184
                } else {
                    // The last piece: an adaptation field of stuffing fills the packet.
                    val field = ByteArray(183 - left) { if (it == 0) 0.toByte() else 0xFF.toByte() }
                    packet(pid, unitStart = at == 0, adaptation = field, payload = pes.copyOfRange(at, pes.size))
                    at = pes.size
                }
            }
        }

        private fun packet(pid: Int, unitStart: Boolean, adaptation: ByteArray?, payload: ByteArray?) {
            if (bdav) {
                arrival += 1000
                out.write(byteArrayOf((arrival shr 24).toByte(), (arrival shr 16).toByte(), (arrival shr 8).toByte(), arrival.toByte()))
            }
            val counter = counters[pid] ?: 0
            if (payload != null) counters[pid] = (counter + 1) and 0x0F
            val control = (if (adaptation != null) 0x20 else 0) or (if (payload != null) 0x10 else 0)
            val packet = ByteArray(188) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = ((if (unitStart) 0x40 else 0) or (pid shr 8)).toByte()
            packet[2] = pid.toByte()
            packet[3] = (control or counter).toByte()
            var at = 4
            if (adaptation != null) {
                packet[at++] = adaptation.size.toByte()
                adaptation.copyInto(packet, at)
                at += adaptation.size
            }
            payload?.copyInto(packet, at)
            out.write(packet)
        }
    }

    private class RecordingOutput : ExtractorOutput {
        val tracks = LinkedHashMap<Int, RecordingTrack>()
        var seekMap: SeekMap? = null
        val seeks = ArrayList<Long>()

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { RecordingTrack(type) }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private class RecordingTrack(val type: Int) : TrackOutput {
        var format: Format? = null
        val samples = ArrayList<Pair<Long, ByteArray>>()
        private val pending = ByteArrayOutputStream()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            val read = input.read(buffer, 0, length)
            if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            pending.write(buffer, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buffer = ByteArray(length)
            data.readBytes(buffer, 0, length)
            pending.write(buffer, 0, length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val bytes = pending.toByteArray()
            val end = bytes.size - offset
            samples.add(timeUs to bytes.copyOfRange(end - size, end))
            pending.reset()
            pending.write(bytes, end, offset)
        }
    }

    private companion object {
        const val PMT_PID = 0x100
        const val PCR_PID = 0x1001
        const val LPCM_PID = 0x1100
        const val PGS_PID = 0x1200
        const val START_PTS = 900_000L
        const val FRAMES_PER_PES = 16

        // 16 frames at 48 kHz, in 90 kHz ticks.
        const val PTS_PER_PES = 30L
        const val PES_COUNT = 8

        const val TRUEHD_PID = 0x1101
        const val AC3_PID = 0x1102
        const val CORE_FRAMES = 4
        // Past the half second after which a TrueHD stream with no core frame
        // is taken to have none.
        const val LONG_FRAMES = 20
        const val CORELESS_PID = 0x1103

        // An AC-3 frame's 1536 samples at 48 kHz, in 90 kHz ticks and in microseconds.
        const val FRAME_TICKS = 2_880L
        const val FRAME_US = 32_000L

        // What fills the frames on each stream, so that a sample tells where it came from.
        const val CORE_FILL = 0x10
        const val PLAIN_FILL = 0x20
        const val DECOY_FILL = 0x30

        val TRUEHD_MAJOR_SYNC = byteArrayOf(0xF8.toByte(), 0x72, 0x6F, 0xBA.toByte())

        val MATROSKA_HEAD = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(4000) { (it * 7).toByte() }

        /**
         * An AC-3 frame such as a disc carries beside its TrueHD: 48 kHz, 640
         * kbit/s (2560 bytes), 5.1. After the header every byte is [fill].
         */
        private fun ac3Frame(fill: Int): ByteArray {
            val frame = ByteArray(2560) { fill.toByte() }
            // Syncword, crc1, 48 kHz and 640 kbit/s, bsid 8, then 3/2 channels
            // with an LFE channel.
            byteArrayOf(0x0B, 0x77, 0, 0, 0x24, 0x40, 0xE1.toByte(), 0).copyInto(frame)
            return frame
        }

        private fun segment(type: Int, vararg body: Int): ByteArray =
            byteArrayOf(type.toByte(), (body.size shr 8).toByte(), body.size.toByte()) + ByteArray(body.size) { body[it].toByte() }

        /**
         * Two display sets, a segment to a PES packet as on a disc: one that
         * shows an object (whose bitmap is left unfinished, so the parser has
         * nothing to draw), and one that clears the screen again.
         */
        val DISPLAY_SETS: List<List<ByteArray>> = listOf(
            listOf(
                segment(0x16, 0x07, 0x80, 0x04, 0x38, 0x10, 0x00, 0x01, 0x80, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
                segment(0x17, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x10),
                segment(0x14, 0x00, 0x00, 0x01, 0xEB, 0x80, 0x80, 0xFF),
                segment(0x15, 0x00, 0x00, 0x00, 0xC0, 0x00, 0x00, 0x14, 0x00, 0x10, 0x00, 0x10, 0x01, 0x02),
                segment(0x80)
            ),
            listOf(
                segment(0x16, 0x07, 0x80, 0x04, 0x38, 0x10, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00),
                segment(0x17, 0x00),
                segment(0x80)
            )
        )
    }
}
