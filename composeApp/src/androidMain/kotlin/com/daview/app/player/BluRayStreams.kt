package com.daview.app.player

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.DtsReader
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsPayloadReader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Readers for the stream types of a Blu-ray transport stream, which from 0x80
 * up mean what the Blu-ray specification says. The same numbers mean other
 * things elsewhere — 0x86 is an SCTE-35 splice table in broadcast — so when
 * the program does not declare itself a Blu-ray one ([hdmv] false), every
 * stream is left to media3's usual readers.
 */
@UnstableApi
internal class BluRayPayloadReaders(
    private val hdmv: Boolean,
    // The TrueHD PIDs that carry the AC-3 core, which is all of them read
    // here; null when that was not looked into, and every one is taken to.
    private val coredTrueHd: Set<Int>? = null
) : TsPayloadReader.Factory {

    private val defaults = DefaultTsPayloadReaderFactory(0)

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = defaults.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
        if (!hdmv) return defaults.createPayloadReader(streamType, esInfo)
        return when (streamType) {
            0x80 -> PesReader(BluRayLpcmReader(esInfo.language))
            0x81 -> PesReader(Ac3Reader(esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T))
            // DTS, DTS-HD High Resolution, DTS-HD Master Audio, and DTS-HD as a
            // secondary audio track. media3's reader puts the core frame and the
            // extension that goes with it together into one sample.
            0x82, 0x85, 0x86, 0xA2 ->
                PesReader(DtsReader(esInfo.language, esInfo.roleFlags, DTS_EXTENSION_HEADER_MAX, MimeTypes.VIDEO_MP2T))
            0x90 -> PesReader(BluRayPgsReader(esInfo.language))
            // TrueHD, with an AC-3 version of the same audio interleaved in the
            // same stream. Only the AC-3 is read, as a track of its own — when
            // there is one: a remux that kept only the TrueHD would leave that
            // track without a format, and media3 plays nothing until every
            // track has one.
            0x83 -> BluRayTrueHdCoreReader(esInfo.language, esInfo.roleFlags, coredTrueHd)
            // Blu-ray E-AC-3: an AC-3 core and a dependent substream beside it,
            // which media3's AC-3 reader would give out as two formats in turn.
            0x84, 0xA1 -> null
            // The disc menu's buttons.
            0x91 -> null
            // Text subtitles, a Blu-ray format of their own with no reader in media3.
            0x92 -> null
            // VC-1, which media3 cannot read.
            0xEA -> null
            // The second view of 3D video; the first view plays on its own.
            0x20 -> null
            // H.264, HEVC, MPEG-2 video and the rest mean the same as anywhere.
            else -> defaults.createPayloadReader(streamType, esInfo)
        }
    }
}

/**
 * PGS, the Blu-ray's picture subtitles.
 *
 * media3's parser takes a whole display set at a time — the segments from a
 * presentation composition segment to its end segment — and builds its cue at
 * the end segment. A Blu-ray puts every segment in a PES packet of its own, so
 * the segments are collected here and the set goes out as one sample, at the
 * time of the packet its composition segment began in: that is when the disc
 * shows it, or, for a set with nothing in it, takes the last one away.
 */
@UnstableApi
internal class BluRayPgsReader(private val language: String?) : ElementaryStreamReader {

    private lateinit var output: TrackOutput

    // Segments not yet complete, which may go on into the next packet.
    private val pending = GrowableBytes()
    private val displaySet = GrowableBytes()
    private val sample = ParsableByteArray()
    private var pesTimeUs = C.TIME_UNSET

    // The time of the packet the first segment in [pending] began in.
    private var segmentTimeUs = C.TIME_UNSET
    private var setTimeUs = C.TIME_UNSET
    private var inSet = false

    override fun seek() {
        pending.clear()
        displaySet.clear()
        inSet = false
        pesTimeUs = C.TIME_UNSET
        segmentTimeUs = C.TIME_UNSET
        setTimeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        // The format goes out now rather than with the first subtitle: the
        // player waits for every track's format before it can start, and the
        // first subtitle may be minutes into the film.
        output.format(
            Format.Builder()
                .setId(idGenerator.formatId)
                .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                .setSampleMimeType(MimeTypes.APPLICATION_PGS)
                .setLanguage(language)
                .build()
        )
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        if (pesTimeUs != C.TIME_UNSET) this.pesTimeUs = pesTimeUs
        // A disc starts every segment in a packet of its own and marks the
        // packet as starting one. Bytes still waiting for the rest of their
        // segment then belong to one whose length was wrong, and waiting on
        // would swallow every display set in the next 64 KiB. Only that segment
        // goes: a set short of it still replaces the subtitle on screen at its
        // time, where dropping the whole set would leave the last one up.
        if (flags and TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR != 0) pending.clear()
        if (pending.size == 0) segmentTimeUs = this.pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        pending.append(data, data.bytesLeft())
    }

    override fun packetFinished() {
        var at = 0
        while (pending.size - at >= SEGMENT_HEADER) {
            val type = pending.u8(at)
            if (type !in SEGMENT_TYPES) {
                // Not the start of a segment: the reader has lost its place. The
                // next packet starts on a segment again.
                pending.clear()
                displaySet.clear()
                inSet = false
                return
            }
            val size = SEGMENT_HEADER + pending.u16(at + 1)
            if (pending.size - at < size) break
            segment(type, at, size)
            at += size
            // Whatever follows began in this packet.
            segmentTimeUs = pesTimeUs
        }
        pending.dropFirst(at)
    }

    private fun segment(type: Int, at: Int, size: Int) {
        if (type == PRESENTATION_COMPOSITION) {
            // A set that never reached its end segment is dropped.
            displaySet.clear()
            inSet = true
            setTimeUs = segmentTimeUs
        }
        // Segments outside a set belong to one that was cut off by a seek or by
        // the start of the file; there is nothing to show them with.
        if (!inSet) return
        displaySet.append(pending.data, at, size)
        if (type != END_OF_DISPLAY_SET) return
        if (setTimeUs != C.TIME_UNSET) {
            sample.reset(displaySet.data, displaySet.size)
            output.sampleData(sample, displaySet.size)
            output.sampleMetadata(setTimeUs, C.BUFFER_FLAG_KEY_FRAME, displaySet.size, 0, null)
        }
        displaySet.clear()
        inSet = false
    }

    private companion object {
        const val SEGMENT_HEADER = 3
        const val PRESENTATION_COMPOSITION = 0x16
        const val END_OF_DISPLAY_SET = 0x80

        // Palette, object, presentation composition, window, end.
        val SEGMENT_TYPES = setOf(0x14, 0x15, PRESENTATION_COMPOSITION, 0x17, END_OF_DISPLAY_SET)
    }
}

/**
 * Blu-ray LPCM, as FFmpeg reads it (libavcodec/pcm-bluray.c).
 *
 * Every PES packet is a four-byte header and then the samples: big-endian,
 * interleaved, in the disc's channel order, and always an even number of
 * channels, so a layout with an odd number carries one empty channel. media3
 * plays big-endian PCM without a decoder, converting it on the way to the
 * audio track; what it cannot take is the empty channel or the disc's order.
 * So each packet goes out as one sample with the empty channel dropped and the
 * rest in the order media3's audio sink gives a track of that many channels
 * (Util.getAudioTrackChannelConfig), where 6 is FL FR FC LFE BL BR and 8 is
 * FL FR FC LFE BL BR SL SR.
 */
@UnstableApi
internal class BluRayLpcmReader(private val language: String?) : ElementaryStreamReader {

    private lateinit var output: TrackOutput
    private lateinit var formatId: String
    private val pes = GrowableBytes()
    private val converted = GrowableBytes()
    private val sample = ParsableByteArray()
    private var pesTimeUs = C.TIME_UNSET
    private var nextTimeUs = C.TIME_UNSET

    // The header bits the format was made from; a change means a new format.
    private var formatBits = -1

    override fun seek() {
        pes.clear()
        nextTimeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.formatId
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        this.pesTimeUs = pesTimeUs
        pes.clear()
    }

    override fun consume(data: ParsableByteArray) {
        pes.append(data, data.bytesLeft())
    }

    override fun packetFinished() {
        if (pes.size < HEADER) return
        // The first two bytes give the size of the audio data, which the PES
        // packet's own length already says; FFmpeg does not read them either.
        val layout = LAYOUTS[pes.u8(2) shr 4]
        val sampleRate = SAMPLE_RATES[pes.u8(2) and 0x0F]
        val bits = BITS_PER_SAMPLE[pes.u8(3) shr 6]
        // Reserved values: there is nothing here that can be played.
        if (layout == null || sampleRate == 0 || bits == 0) return
        val sampleBytes = if (bits == 16) 2 else 3
        // Only whole frames: the audio sink takes buffers of whole frames.
        val frames = (pes.size - HEADER) / (layout.coded * sampleBytes)
        val timeUs = if (pesTimeUs != C.TIME_UNSET) pesTimeUs else nextTimeUs
        if (frames == 0 || timeUs == C.TIME_UNSET) return

        val header = (pes.u8(2) shl 8) or (pes.u8(3) and 0xC0)
        if (header != formatBits) {
            formatBits = header
            output.format(
                Format.Builder()
                    .setId(formatId)
                    .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    // 20-bit samples are carried in 24 bits, the low four zero.
                    .setPcmEncoding(if (bits == 16) C.ENCODING_PCM_16BIT_BIG_ENDIAN else C.ENCODING_PCM_24BIT_BIG_ENDIAN)
                    .setChannelCount(layout.channels)
                    .setSampleRate(sampleRate)
                    .setLanguage(language)
                    .build()
            )
        }
        val size = frames * layout.channels * sampleBytes
        if (layout.isAsCoded) {
            sample.reset(pes.data, HEADER + size)
            sample.skipBytes(HEADER)
        } else {
            layout.convert(pes.data, HEADER, frames, sampleBytes, converted.reserve(size))
            sample.reset(converted.data, size)
        }
        output.sampleData(sample, size)
        output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
        nextTimeUs = timeUs + frames * C.MICROS_PER_SECOND / sampleRate
    }

    /**
     * How a channel assignment's coded channels become the channels media3
     * plays: [sources] gives, for every channel played, the coded channel it
     * comes from, or [SILENT]. Channels whose bit is set in [halfPower] carry
     * their source 3 dB down, which is how one surround channel is spread over
     * two speakers without growing louder (libswresample does the same).
     */
    private class Layout(val coded: Int, private val sources: IntArray, private val halfPower: Int = 0) {

        val channels: Int get() = sources.size

        val isAsCoded: Boolean = coded == sources.size && halfPower == 0 && sources.withIndex().all { (index, source) -> index == source }

        /** Writes [frames] frames of coded samples, starting at [from] in [data], to [target] as they are played. */
        fun convert(data: ByteArray, from: Int, frames: Int, sampleBytes: Int, target: ByteArray) {
            var frame = from
            var to = 0
            repeat(frames) {
                for (channel in sources.indices) {
                    val source = sources[channel]
                    when {
                        source == SILENT -> target.fill(0, to, to + sampleBytes)
                        halfPower and (1 shl channel) != 0 -> halve(data, frame + source * sampleBytes, sampleBytes, target, to)
                        else -> System.arraycopy(data, frame + source * sampleBytes, target, to, sampleBytes)
                    }
                    to += sampleBytes
                }
                frame += coded * sampleBytes
            }
        }

        private fun halve(data: ByteArray, at: Int, sampleBytes: Int, target: ByteArray, to: Int) {
            // The first byte keeps its sign when widened, which makes the sample signed.
            var value = data[at].toInt()
            for (i in 1 until sampleBytes) value = (value shl 8) or (data[at + i].toInt() and 0xFF)
            var scaled = (value * HALF_POWER).roundToInt()
            for (i in sampleBytes - 1 downTo 0) {
                target[to + i] = scaled.toByte()
                scaled = scaled shr 8
            }
        }
    }

    private companion object {
        const val HEADER = 4
        const val SILENT = -1
        const val HALF_POWER = 0.7071067811865476

        val BITS_PER_SAMPLE = intArrayOf(0, 16, 20, 24)
        val SAMPLE_RATES = IntArray(16).apply {
            this[1] = 48_000
            this[4] = 96_000
            this[5] = 192_000
        }

        // By channel assignment, with the coded order from the Blu-ray
        // specification as FFmpeg's decoder quotes it (X is the empty channel).
        val LAYOUTS: Array<Layout?> = arrayOfNulls<Layout>(16).apply {
            // M X
            this[1] = Layout(2, intArrayOf(0))
            // L R
            this[3] = Layout(2, intArrayOf(0, 1))
            // L R C X, played as FL FR FC.
            this[4] = Layout(4, intArrayOf(0, 1, 2))
            // L R S X. media3 has no three-channel layout with a surround — its
            // three channels are FL FR FC — so the surround is spread over the
            // two back speakers of a four-channel track: FL FR BL BR.
            this[5] = Layout(4, intArrayOf(0, 1, 2, 2), halfPower = 0b1100)
            // L R C S, the same way: FL FR FC BL BR.
            this[6] = Layout(4, intArrayOf(0, 1, 2, 3, 3), halfPower = 0b11000)
            // L R LS RS, played as FL FR BL BR.
            this[7] = Layout(4, intArrayOf(0, 1, 2, 3))
            // L R C LS RS X, played as FL FR FC BL BR.
            this[8] = Layout(6, intArrayOf(0, 1, 2, 3, 4))
            // L R C LS RS LFE, played as FL FR FC LFE BL BR.
            this[9] = Layout(6, intArrayOf(0, 1, 2, 5, 3, 4))
            // L R C LS Rls Rrs RS X. media3's seven channels are 6.1, not 7.0,
            // so this plays as 7.1 with a silent LFE: FL FR FC LFE BL BR SL SR.
            this[10] = Layout(8, intArrayOf(0, 1, 2, SILENT, 4, 5, 3, 6))
            // L R C LS Rls Rrs RS LFE, played as FL FR FC LFE BL BR SL SR.
            this[11] = Layout(8, intArrayOf(0, 1, 2, 7, 4, 5, 3, 6))
        }
    }
}

/**
 * Blu-ray TrueHD, of which only the AC-3 core is read.
 *
 * A disc's TrueHD stream carries the same audio twice: as TrueHD access units,
 * and as AC-3 frames for players without a TrueHD decoder. Each PES packet
 * holds one or the other and says which in the stream_id_extension of its PES
 * extension: 0x72 for TrueHD, 0x76 for AC-3. FFmpeg makes two streams of them
 * (libavformat/mpegts.c), the TrueHD first, and the server's probe lists both:
 * the TrueHD at the PID, the AC-3 at the PID plus TsProbe.TRUEHD_CORE_OFFSET.
 *
 * media3's PES reader throws the PES extension away, so the packets are read
 * here with its state machine (PesReader, media3 1.11.0), the whole optional
 * header read rather than its first ten bytes. Every packet's timestamps go to
 * the timestamp adjuster as PesReader's would. A packet with any other
 * stream_id_extension, or with none, which FFmpeg also counts as TrueHD, goes
 * no further, and the TrueHD has no track.
 *
 * The extractor gives each PID ids from the PID up in steps of 0x2000. The
 * first is the TrueHD's, so the AC-3 track takes the second, which is the
 * index the probe gives it.
 */
@UnstableApi
internal class BluRayTrueHdCoreReader(
    language: String?,
    roleFlags: Int,
    // See BluRayPayloadReaders.
    private val coredPids: Set<Int>? = null
) : TsPayloadReader {

    private val core = Ac3Reader(language, roleFlags, MimeTypes.VIDEO_MP2T)
    private lateinit var timestampAdjuster: TimestampAdjuster

    // The fixed part of the PES header, then up to 255 bytes of optional fields.
    private val header = ByteArray(HEADER + 255)
    private var state = FINDING_HEADER
    private var bytesRead = 0
    private var headerDataLength = 0
    private var payloadSize = C.LENGTH_UNSET
    private var seenFirstDts = false

    // Whether the packet being read is an AC-3 one, for [core].
    private var isCore = false

    // Whether this stream was found to carry no core, and is read no further.
    private var coreless = false

    // Whether [core] has had a time since the last seek to count its frames on from.
    private var coreHasTime = false

    // The AC-3 frame header being gathered, and the rest of the frame after one (see [consumeCore]).
    private val syncHeader = ByteArray(SYNC_HEADER)
    private val syncHeaderData = ParsableByteArray()
    private var syncHeaderBytes = 0
    private var frameLeft = 0

    override fun init(timestampAdjuster: TimestampAdjuster, extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        this.timestampAdjuster = timestampAdjuster
        // The TrueHD's id, which goes unused; it is the PID.
        idGenerator.generateNewId()
        // A stream without the core gets no track at all: one that never had
        // a format would keep media3 from playing anything.
        coreless = coredPids != null && idGenerator.trackId !in coredPids
        if (!coreless) core.createTracks(extractorOutput, idGenerator)
    }

    override fun seek() {
        state = FINDING_HEADER
        bytesRead = 0
        seenFirstDts = false
        isCore = false
        coreHasTime = false
        syncHeaderBytes = 0
        frameLeft = 0
        core.seek()
    }

    override fun consume(data: ParsableByteArray, flags: Int) {
        if (coreless) return
        if (flags and TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR != 0) {
            // The packet being read ends here: one without a length always
            // does, one with a length when it was cut short.
            if (state == READING_BODY && isCore) core.packetFinished()
            if (data.limit() == 0) core.endOfInputReached()
            setState(READING_HEADER)
        }
        while (data.bytesLeft() > 0) {
            when (state) {
                FINDING_HEADER -> data.skipBytes(data.bytesLeft())
                READING_HEADER -> if (continueRead(data, 0, HEADER)) {
                    setState(if (parseHeader()) READING_HEADER_DATA else FINDING_HEADER)
                }
                READING_HEADER_DATA -> if (continueRead(data, HEADER, headerDataLength)) {
                    parseHeaderData(flags)
                    setState(READING_BODY)
                }
                READING_BODY -> {
                    var length = data.bytesLeft()
                    // Anything after the end of the packet is stuffing.
                    if (payloadSize != C.LENGTH_UNSET && length > payloadSize) {
                        length = payloadSize
                        data.setLimit(data.position + length)
                    }
                    if (isCore) consumeCore(data) else data.skipBytes(length)
                    if (payloadSize != C.LENGTH_UNSET) {
                        payloadSize -= length
                        if (payloadSize == 0) {
                            if (isCore) core.packetFinished()
                            setState(READING_HEADER)
                        }
                    }
                }
            }
        }
    }

    private fun setState(state: Int) {
        this.state = state
        bytesRead = 0
    }

    /** Reads on into [header] from [offset] until [length] bytes are there; true when they are. */
    private fun continueRead(source: ParsableByteArray, offset: Int, length: Int): Boolean {
        val count = min(source.bytesLeft(), length - bytesRead)
        if (count <= 0) return true
        source.readBytes(header, offset + bytesRead, count)
        bytesRead += count
        return bytesRead == length
    }

    /** The fixed part of the header (ISO/IEC 13818-1, 2.4.3.6); false if it is not one to read on from. */
    private fun parseHeader(): Boolean {
        if (u8(0) != 0 || u8(1) != 0 || u8(2) != 1 || u8(3) in NO_OPTIONAL_HEADER) {
            payloadSize = C.LENGTH_UNSET
            return false
        }
        val packetLength = (u8(4) shl 8) or u8(5)
        headerDataLength = u8(8)
        val size = packetLength + 6 - HEADER - headerDataLength
        // A length of 0 means the packet runs on to the next one; a length
        // too short for the packet's own header is read the same way.
        payloadSize = if (packetLength == 0 || size < 0) C.LENGTH_UNSET else size
        return true
    }

    /** The optional fields, in the order they come in, up to the stream_id_extension. */
    private fun parseHeaderData(flags: Int) {
        val end = HEADER + headerDataLength
        val fields = u8(7)
        var at = HEADER
        var pts = C.TIME_UNSET
        var dts = C.TIME_UNSET
        // PTS_DTS_flags: '10' a PTS, '11' a PTS and a DTS.
        if (fields and 0x80 != 0) {
            pts = timestamp(at, end)
            at += 5
            if (fields and 0x40 != 0) {
                dts = timestamp(at, end)
                at += 5
            }
        }
        // ESCR, ES_rate, DSM_trick_mode, additional_copy_info, previous_PES_packet_CRC.
        if (fields and 0x20 != 0) at += 6
        if (fields and 0x10 != 0) at += 3
        if (fields and 0x08 != 0) at += 1
        if (fields and 0x04 != 0) at += 1
        if (fields and 0x02 != 0) at += 2
        val extension = if (fields and 0x01 != 0) streamIdExtension(at, end) else NONE

        var timeUs = C.TIME_UNSET
        if (pts != C.TIME_UNSET) {
            // As PesReader does: the first decode time goes to the adjuster
            // first, so that no presentation time after it comes out negative.
            if (!seenFirstDts && dts != C.TIME_UNSET) {
                timestampAdjuster.adjustTsTimestamp(dts)
                seenFirstDts = true
            }
            timeUs = timestampAdjuster.adjustTsTimestamp(pts)
        }
        // media3's AC-3 reader times each frame from the last packet it was
        // told of, and fails (IllegalStateException) on a frame with no time.
        // A packet without one, which MPEG allows and no disc has, goes on from
        // the frames before it; with none since the last seek it is dropped.
        isCore = extension == CORE && (timeUs != C.TIME_UNSET || coreHasTime)
        if (isCore && timeUs != C.TIME_UNSET) {
            val aligned = if (u8(6) and 0x04 != 0) TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR else 0
            core.packetStarted(timeUs, flags or aligned)
            coreHasTime = true
        }
    }

    private fun streamIdExtension(start: Int, end: Int): Int = pesStreamIdExtension(header, start, end)

    /** The 33-bit timestamp in the five bytes at [at], or C.TIME_UNSET if the header ends first. */
    private fun timestamp(at: Int, end: Int): Long {
        if (at + 5 > end) return C.TIME_UNSET
        return ((u8(at).toLong() shr 1 and 0x07L) shl 30) or (u8(at + 1).toLong() shl 22) or
            ((u8(at + 2).toLong() shr 1) shl 15) or (u8(at + 3).toLong() shl 7) or (u8(at + 4).toLong() shr 1)
    }

    /**
     * Hands [core] the AC-3 frames in [data], whole, and nothing else.
     *
     * media3's AC-3 reader takes whatever follows a syncword for a frame
     * header, and looks its frame size code up in a table without checking
     * it: codes 38 to 63 throw ArrayIndexOutOfBoundsException, and an
     * exception out of a payload reader ends playback. The reserved sample
     * rate code gives a format with no sample type and a frame size of -1.
     * So the frames are found here, the way that reader finds them — a
     * syncword, then the size the header gives — and only one whose header
     * gives a size is handed on; everything between frames goes no further.
     * The reader takes each frame by that same size, so it stays in step and
     * sees every frame from its syncword on.
     */
    private fun consumeCore(data: ParsableByteArray) {
        val end = data.limit()
        while (data.bytesLeft() > 0) {
            if (frameLeft > 0) {
                val length = min(frameLeft, data.bytesLeft())
                data.setLimit(data.position + length)
                core.consume(data)
                data.setLimit(end)
                frameLeft -= length
                continue
            }
            if (!addToSyncHeader(data.readUnsignedByte())) continue
            val size = frameSize()
            if (size == C.LENGTH_UNSET) {
                dropSyncHeader()
                continue
            }
            syncHeaderBytes = 0
            syncHeaderData.reset(syncHeader, SYNC_HEADER)
            core.consume(syncHeaderData)
            frameLeft = size - SYNC_HEADER
        }
    }

    /** Takes [byte] as the next byte of a frame header, or of the search for one; true once the header is whole. */
    private fun addToSyncHeader(byte: Int): Boolean {
        if (syncHeaderBytes == 0 && byte != SYNC_FIRST) return false
        if (syncHeaderBytes == 1 && byte != SYNC_SECOND) {
            syncHeaderBytes = if (byte == SYNC_FIRST) 1 else 0
            return false
        }
        syncHeader[syncHeaderBytes++] = byte.toByte()
        return syncHeaderBytes == SYNC_HEADER
    }

    /**
     * The size of the frame whose header has been gathered, or
     * C.LENGTH_UNSET. A bsid above 10 is E-AC-3 (Ac3Util's test), which a
     * TrueHD stream's core is not, and whose sample rate media3's reader looks
     * up unchecked as well. For AC-3, Ac3Util has no size for the reserved
     * sample rate code or a frame size code past its table; every size it has
     * is at least the 128 bytes that reader reads as a header.
     */
    private fun frameSize(): Int =
        if ((syncHeader[5].toInt() and 0xFF) shr 3 > 10) C.LENGTH_UNSET else Ac3Util.parseAc3SyncframeSize(syncHeader)

    /** Gives up on the gathered header, keeping any syncword that begins inside it. */
    private fun dropSyncHeader() {
        var start = 2
        while (start < SYNC_HEADER && !isSyncStart(start)) start++
        syncHeader.copyInto(syncHeader, 0, start, SYNC_HEADER)
        syncHeaderBytes = SYNC_HEADER - start
    }

    private fun isSyncStart(index: Int): Boolean =
        syncHeader[index].toInt() == SYNC_FIRST && (index == SYNC_HEADER - 1 || syncHeader[index + 1].toInt() == SYNC_SECOND)

    private fun u8(index: Int): Int = header[index].toInt() and 0xFF

    private companion object {
        // PesReader's states, with the optional header read whole.
        const val FINDING_HEADER = 0
        const val READING_HEADER = 1
        const val READING_HEADER_DATA = 2
        const val READING_BODY = 3

        // The PES header up to and including PES_header_data_length.
        const val HEADER = 9
        const val NONE = -1

        // The stream_id_extension of the AC-3 frames; the TrueHD's is 0x72.
        const val CORE = 0x76

        // An AC-3 frame's syncword, its crc1, the byte with its sample rate
        // and frame size codes, and the one with its bsid: enough for its size.
        const val SYNC_HEADER = 6
        const val SYNC_FIRST = 0x0B
        const val SYNC_SECOND = 0x77

        // Streams whose packets have no optional header (ISO/IEC 13818-1,
        // 2.4.3.6, as FFmpeg lists them), so no stream_id_extension either.
        val NO_OPTIONAL_HEADER = setOf(0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF)
    }
}

/** Bytes gathered in pieces; the array grows by doubling, so many small appends cost about what one large one does. */
@UnstableApi
private class GrowableBytes {

    var data = ByteArray(INITIAL_CAPACITY)
        private set
    var size = 0
        private set

    fun u8(index: Int): Int = data[index].toInt() and 0xFF

    fun u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

    fun append(source: ParsableByteArray, length: Int) {
        ensureCapacity(size + length)
        source.readBytes(data, size, length)
        size += length
    }

    fun append(source: ByteArray, from: Int, length: Int) {
        ensureCapacity(size + length)
        System.arraycopy(source, from, data, size, length)
        size += length
    }

    /** Makes room for [length] bytes, discarding what was there, and returns the array to write them to. */
    fun reserve(length: Int): ByteArray {
        if (length > data.size) data = ByteArray(max(length, data.size * 2))
        size = length
        return data
    }

    fun dropFirst(count: Int) {
        if (count == 0) return
        System.arraycopy(data, count, data, 0, size - count)
        size -= count
    }

    fun clear() {
        size = 0
    }

    private fun ensureCapacity(capacity: Int) {
        if (capacity > data.size) data = data.copyOf(max(capacity, data.size * 2))
    }

    private companion object {
        const val INITIAL_CAPACITY = 4096
    }
}

// DtsReader.EXTSS_HEADER_SIZE_MAX, which is package-private: the largest
// header of the extension substream that carries DTS-HD's lossless part.
private const val DTS_EXTENSION_HEADER_MAX = 4096

/**
 * The stream_id_extension in the PES extension that starts at [start] in
 * [bytes], or -1 for none. Every field is checked against [end], the end of
 * the header.
 */
internal fun pesStreamIdExtension(bytes: ByteArray, start: Int, end: Int): Int {
    val u8 = { i: Int -> bytes[i].toInt() and 0xFF }
    var at = start
    if (at >= end) return -1
    val extensionFlags = u8(at++)
    // PES_private_data; pack_header_field, a length and that many bytes;
    // program_packet_sequence_counter; P-STD_buffer.
    if (extensionFlags and 0x80 != 0) at += 16
    if (extensionFlags and 0x40 != 0) at += if (at < end) 1 + u8(at) else 1
    if (extensionFlags and 0x20 != 0) at += 2
    if (extensionFlags and 0x10 != 0) at += 2
    // PES_extension_flag_2: a byte of marker_bit and PES_extension_field_length,
    // which counts the bytes after it and so is at least 1 here, then
    // stream_id_extension_flag, 0 when the seven bits after it are the
    // stream_id_extension.
    if (extensionFlags and 0x01 == 0 || at + 2 > end) return -1
    if (u8(at) and 0x7F == 0 || u8(at + 1) and 0x80 != 0) return -1
    return u8(at + 1)
}

/**
 * Where a PES header's extension starts, given the header from its start code
 * on in [bytes] at [start], [end] its end: after the timestamps and the other
 * optional fields. -1 when the header has no extension.
 */
internal fun pesExtensionStart(bytes: ByteArray, start: Int, end: Int): Int {
    if (start + 9 > end) return -1
    val fields = bytes[start + 7].toInt() and 0xFF
    if (fields and 0x01 == 0) return -1
    var at = start + 9
    if (fields and 0x80 != 0) at += if (fields and 0x40 != 0) 10 else 5
    if (fields and 0x20 != 0) at += 6
    if (fields and 0x10 != 0) at += 3
    if (fields and 0x08 != 0) at += 1
    if (fields and 0x04 != 0) at += 1
    if (fields and 0x02 != 0) at += 2
    return at.takeIf { it < end } ?: -1
}
