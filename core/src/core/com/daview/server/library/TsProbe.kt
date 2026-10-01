package com.daview.server.library

import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType

/**
 * MPEG transport streams: broadcast `.ts`, and the `.m2ts` a Blu-ray keeps
 * its streams in, which is what a disc ripped without remuxing is made of.
 *
 * A transport stream has no header and no index. What its tracks are is in the
 * program tables repeated through the stream, and how long it plays is only
 * known by reading timestamps at both ends — so the file is read twice, a
 * little at the head and a little at the tail, and never in between.
 *
 * The track list is built the way libavformat builds it, because the desktop
 * player maps DAView's streams onto mpv's by position (see TrackMapping): a
 * track listed here that mpv does not list, or the other way round, would move
 * every choice after it onto the wrong track.
 */
object TsProbe {

    /** The first stream index of the second track libavformat makes of an HDMV TrueHD stream: its AC-3 core. */
    const val TRUEHD_CORE_OFFSET = 0x2000

    data class TsInfo(val durationMs: Long?, val streams: List<MediaStreamDto>)

    /**
     * How the bytes are cut into packets. Blu-ray (BDAV) puts a four-byte
     * arrival timestamp before every 188-byte packet; DVB captures sometimes
     * keep 16 bytes of error correction after it.
     */
    data class Framing(val packetSize: Int, val start: Int) {
        /** Where the 0x47 sync byte sits within a packet. */
        val syncOffset: Int get() = if (packetSize == BDAV_PACKET) 4 else 0
    }

    data class ElementaryStream(val pid: Int, val streamType: Int, val descriptors: ByteArray)

    data class Program(
        val number: Int,
        val pcrPid: Int,
        /** The program's registration descriptor, e.g. `HDMV` for a Blu-ray. */
        val registration: String?,
        val streams: List<ElementaryStream>
    ) {
        /** Whether stream types 0x80 and up mean what Blu-ray says they mean, as libavformat decides it. */
        val isHdmv: Boolean get() = registration == "HDMV" || registration == "HDPR"
    }

    private const val TS_PACKET = 188
    private const val BDAV_PACKET = 192
    private const val DVB_FEC_PACKET = 204
    private const val SYNC = 0x47
    private const val TIMESTAMP_BYTES = 4
    private const val PTS_WRAP = 1L shl 33

    private const val HEAD_READ_SIZE = 1024 * 1024
    private const val TAIL_READ_SIZE = 1024 * 1024
    private const val RETRY_READ_SIZE = 4 * 1024 * 1024

    fun isTransportStream(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in setOf("ts", "m2ts", "mts", "m2t")

    /**
     * Reads the tables and the first timestamps from the head and the last ones
     * from the tail. Null when the file is not a transport stream or carries no
     * program table where one should be.
     */
    fun probe(reader: MkvProbe.RangeReader, fileSize: Long?): TsInfo? {
        var head = reader.read(0, HEAD_READ_SIZE)
        var framing = framing(head, head.size, MIN_SYNC_RUN) ?: return null
        var program = program(head, head.size, framing)
        // Most files open on their tables; a broadcast capture that was cut in
        // mid-stream has to wait for them to come round again.
        if (program == null && head.size >= HEAD_READ_SIZE) {
            head = reader.read(0, RETRY_READ_SIZE)
            framing = framing(head, head.size, MIN_SYNC_RUN) ?: return null
            program = program(head, head.size, framing)
        }
        program ?: return null

        val listed = tracks(program) { pid -> sniffed(head, head.size, framing, pid) }
        val timed = listed
            .filter { it.type == StreamType.VIDEO || it.type == StreamType.AUDIO }
            .map { it.pid }
            .toSet()
        val start = timestamps(head, head.size, framing, timed).minOrNull()
        val end = if (start != null && fileSize != null && fileSize > 0) lastTimestamp(reader, fileSize, framing, timed, start) else null
        val durationMs = if (start != null && end != null) (end - start) / 90 else null

        val video = listed.firstOrNull { it.type == StreamType.VIDEO }
        val picture = video?.let { pictureSize(head, head.size, framing, it.pid, it.streamType) }
        return TsInfo(durationMs, toMediaStreams(listed, picture?.let { video.pid to it }))
    }

    /**
     * Finds the packet grid: the packet size, and where in [bytes] the first
     * whole packet starts. [minRun] packets in a row have to line up, which
     * is what keeps a four-byte arrival timestamp that happens to hold 0x47
     * from passing for a sync byte.
     */
    fun framing(bytes: ByteArray, length: Int, minRun: Int): Framing? {
        for (size in intArrayOf(BDAV_PACKET, TS_PACKET, DVB_FEC_PACKET)) {
            val syncOffset = if (size == BDAV_PACKET) 4 else 0
            val run = minRun.coerceAtMost(length / size)
            if (run < 2) continue
            val lines = { start: Int ->
                start + syncOffset + (run - 1) * size < length &&
                    (0 until run).all { bytes[start + syncOffset + it * size].toInt() and 0xFF == SYNC }
            }
            for (start in 0 until size) {
                if (start + syncOffset + (run - 1) * size >= length) break
                if (!lines(start)) continue
                // The first bytes of a Blu-ray arrival stamp change only every
                // few milliseconds or more, and where one of them is 0x47 a run
                // of stamps lines up a packet apart just as the sync bytes do,
                // a few bytes before them. The last of the runs within a stamp's
                // length is the sync bytes: the bytes after a sync byte do not
                // line up like that.
                var found = start
                if (size == BDAV_PACKET) for (later in 1..TIMESTAMP_BYTES) if (lines(start + later)) found = start + later
                return Framing(size, found)
            }
        }
        return null
    }

    /**
     * The first program in the program association table and its map, as far
     * as [length] bytes of [bytes] carry them. Program 0 is the network
     * information table's entry, not a program.
     */
    fun program(bytes: ByteArray, length: Int, framing: Framing): Program? {
        val pat = section(bytes, length, framing, pid = 0, tableId = 0x00) ?: return null
        val pmtPid = patPrograms(pat).firstOrNull { it.first != 0 }?.second ?: return null
        val pmt = section(bytes, length, framing, pmtPid, tableId = 0x02) ?: return null
        return parsePmt(pmt)
    }

    // ------------------------------------------------------------ the track list

    private data class Listed(
        val index: Int,
        val pid: Int,
        val streamType: Int,
        val type: StreamType,
        val codec: String,
        val language: String?
    )

    /**
     * The streams libavformat would make of this program, in its order: the
     * program map's own order, with an HDMV TrueHD stream followed by the AC-3
     * core it also carries. Checked against the libmpv the desktop ships.
     */
    private fun tracks(program: Program, sniff: (pid: Int) -> Pair<StreamType, String>?): List<Listed> {
        val found = mutableListOf<Listed>()
        for (stream in program.streams) {
            val kinds = classify(stream, program.isHdmv).ifEmpty { listOfNotNull(sniff(stream.pid)) }
            val language = language(stream.descriptors)
            kinds.forEachIndexed { n, (type, codec) ->
                val index = if (n == 0) stream.pid else stream.pid + TRUEHD_CORE_OFFSET
                found += Listed(index, stream.pid, stream.streamType, type, codec, language)
            }
        }
        // The stream index is the PID, which is what media3 names the track
        // after too ("1/4352"), so the Android player finds it exactly. Stream
        // numbers from 1000 up belong to subtitle files beside the video; in
        // the rare file whose PIDs reach into that range, its streams are
        // numbered in order instead, and matched by position.
        if (found.any { it.pid in EXTERNAL_RANGE }) {
            return found.mapIndexed { n, it -> it.copy(index = n + 1) }
        }
        return found
    }

    private val EXTERNAL_RANGE = Scanner.EXTERNAL_STREAM_BASE until Scanner.EXTERNAL_STREAM_BASE + 1000

    /** libavformat's `mpegts_set_stream_info`, down to which table is asked first. */
    private fun classify(stream: ElementaryStream, hdmv: Boolean): List<Pair<StreamType, String>> {
        ISO_TYPES[stream.streamType]?.let { return listOf(it) }
        if (hdmv) {
            HDMV_TYPES[stream.streamType]?.let {
                // A Blu-ray TrueHD stream carries an AC-3 version of the same
                // audio beside it, and libavformat lists that as a second track.
                return if (stream.streamType == 0x83) listOf(it, StreamType.AUDIO to "ac3") else listOf(it)
            }
        }
        MISC_TYPES[stream.streamType]?.let { return listOf(it) }
        // Then the descriptors, for a stream the type alone did not settle.
        forEachDescriptor(stream.descriptors) { tag, body ->
            if (tag == 0x05 && body.size >= 4) {
                REGISTERED_TYPES[String(body, 0, 4, Charsets.ISO_8859_1)]?.let { return listOf(it) }
            }
            if (stream.streamType == 0x06) {
                DESCRIBED_TYPES[tag]?.let { return listOf(it) }
            }
        }
        return emptyList()
    }

    private val ISO_TYPES: Map<Int, Pair<StreamType, String>> = mapOf(
        0x01 to (StreamType.VIDEO to "mpeg2"),
        0x02 to (StreamType.VIDEO to "mpeg2"),
        0x03 to (StreamType.AUDIO to "mp2"),
        0x04 to (StreamType.AUDIO to "mp2"),
        0x0F to (StreamType.AUDIO to "aac"),
        0x10 to (StreamType.VIDEO to "mpeg4"),
        0x11 to (StreamType.AUDIO to "aac"),
        0x1B to (StreamType.VIDEO to "h264"),
        0x1C to (StreamType.AUDIO to "aac"),
        // The second view of a 3D disc, which mpv lists as another video track.
        0x20 to (StreamType.VIDEO to "h264"),
        0x24 to (StreamType.VIDEO to "hevc"),
        0x33 to (StreamType.VIDEO to "vvc"),
        0xEA to (StreamType.VIDEO to "vc1")
    )

    private val HDMV_TYPES: Map<Int, Pair<StreamType, String>> = mapOf(
        0x80 to (StreamType.AUDIO to "pcm"),
        0x81 to (StreamType.AUDIO to "ac3"),
        0x82 to (StreamType.AUDIO to "dts"),
        0x83 to (StreamType.AUDIO to "truehd"),
        0x84 to (StreamType.AUDIO to "eac3"),
        0x85 to (StreamType.AUDIO to "dts"),
        0x86 to (StreamType.AUDIO to "dts"),
        0xA1 to (StreamType.AUDIO to "eac3"),
        0xA2 to (StreamType.AUDIO to "dts"),
        0x90 to (StreamType.SUBTITLE to "pgs"),
        0x92 to (StreamType.SUBTITLE to "textst")
        // 0x91, the disc menu's buttons, is left out, as libavformat leaves it.
    )

    private val MISC_TYPES: Map<Int, Pair<StreamType, String>> = mapOf(
        0x81 to (StreamType.AUDIO to "ac3"),
        0x87 to (StreamType.AUDIO to "eac3"),
        0x8A to (StreamType.AUDIO to "dts")
    )

    private val REGISTERED_TYPES: Map<String, Pair<StreamType, String>> = mapOf(
        "AC-3" to (StreamType.AUDIO to "ac3"),
        "AC-4" to (StreamType.AUDIO to "ac4"),
        "BSSD" to (StreamType.AUDIO to "s302m"),
        "DTS1" to (StreamType.AUDIO to "dts"),
        "DTS2" to (StreamType.AUDIO to "dts"),
        "DTS3" to (StreamType.AUDIO to "dts"),
        "EAC3" to (StreamType.AUDIO to "eac3"),
        "HEVC" to (StreamType.VIDEO to "hevc"),
        "VVC " to (StreamType.VIDEO to "vvc"),
        "Opus" to (StreamType.AUDIO to "opus"),
        "VC-1" to (StreamType.VIDEO to "vc1")
    )

    /** Private-data streams (type 0x06) that say what they are only in a DVB descriptor. */
    private val DESCRIBED_TYPES: Map<Int, Pair<StreamType, String>> = mapOf(
        0x6A to (StreamType.AUDIO to "ac3"),
        0x7A to (StreamType.AUDIO to "eac3"),
        0x7B to (StreamType.AUDIO to "dts"),
        0x56 to (StreamType.SUBTITLE to "teletext"),
        0x59 to (StreamType.SUBTITLE to "dvbsub")
    )

    /**
     * What libavformat finds by looking into a stream the tables leave
     * unnamed: it probes the data, and a PID that carries DTS or AC-3 frames is
     * listed as that audio — a Blu-ray's DTS remuxed without the disc's HDMV
     * registration, say. Only those two are looked for.
     */
    private fun sniffed(bytes: ByteArray, length: Int, framing: Framing, pid: Int): Pair<StreamType, String>? {
        val data = firstPesPayloads(bytes, length, framing, pid, 16)
        if (data.size < 6) return null
        val word = { i: Int -> data[i].toInt() and 0xFF }
        if (word(0) == 0x7F && word(1) == 0xFE && word(2) == 0x80 && word(3) == 0x01) return StreamType.AUDIO to "dts"
        if (word(0) == 0x0B && word(1) == 0x77) {
            return when (word(5) shr 3) {
                in 0..10 -> StreamType.AUDIO to "ac3"
                in 11..16 -> StreamType.AUDIO to "eac3"
                else -> null
            }
        }
        return null
    }

    /** ISO 639 language descriptor. A Blu-ray has none: the disc keeps its languages in the playlist files. */
    private fun language(descriptors: ByteArray): String? {
        forEachDescriptor(descriptors) { tag, body ->
            if (tag == 0x0A && body.size >= 3) {
                val code = String(body, 0, 3, Charsets.ISO_8859_1).trim().lowercase()
                return code.takeIf { it.length == 3 && it.all(Char::isLetter) && it != "und" }
            }
        }
        return null
    }

    private fun toMediaStreams(listed: List<Listed>, picture: Pair<Int, Pair<Int, Int>>?): List<MediaStreamDto> =
        listed.map {
            val size = picture?.takeIf { (pid, _) -> it.type == StreamType.VIDEO && pid == it.pid }?.second
            MediaStreamDto(
                index = it.index,
                type = it.type,
                codec = it.codec,
                language = it.language,
                // A transport stream has no default flag, and calling every
                // track default would put the disc's own subtitles ahead of
                // the file beside the video that someone chose to put there.
                isDefault = false,
                isForced = false,
                isExternal = false,
                width = size?.first,
                height = size?.second
            )
        }

    // ------------------------------------------------------------ timestamps

    /** Presentation timestamps of every PES on [pids] that starts in the bytes. */
    private fun timestamps(bytes: ByteArray, length: Int, framing: Framing, pids: Set<Int>): List<Long> {
        val found = mutableListOf<Long>()
        forEachPacket(bytes, length, framing) { pid, unitStart, payload, from, to ->
            if (unitStart && pid in pids) presentationTime(payload, from, to)?.let { found += it }
        }
        return found
    }

    /**
     * The last presentation timestamp, as an offset from [start] that has been
     * unwrapped past the 33-bit rollover. The tail is read on the head's packet
     * grid; if the file ends in something else, it is found again.
     */
    private fun lastTimestamp(
        reader: MkvProbe.RangeReader,
        fileSize: Long,
        framing: Framing,
        pids: Set<Int>,
        start: Long
    ): Long? {
        for (size in intArrayOf(TAIL_READ_SIZE, RETRY_READ_SIZE)) {
            val wanted = size.toLong().coerceAtMost(fileSize - framing.start)
            if (wanted <= 0) return null
            val from = framing.start + ((fileSize - wanted - framing.start) / framing.packetSize).coerceAtLeast(0) * framing.packetSize
            val tail = reader.read(from, (fileSize - from).toInt())
            val tailFraming = framing(tail, tail.size, MIN_SYNC_RUN)?.takeIf { it.packetSize == framing.packetSize }
                ?: return null
            val end = timestamps(tail, tail.size, tailFraming, pids)
                .map { (it - start + PTS_WRAP) % PTS_WRAP }
                // Anything that far "ahead" is a frame from just before the
                // start, not one from thirteen hours later.
                .filter { it < PTS_WRAP / 2 }
                .maxOrNull()
            if (end != null) return start + end
            if (wanted >= fileSize - framing.start) return null
        }
        return null
    }

    private fun presentationTime(payload: ByteArray, from: Int, to: Int): Long? {
        if (to - from < 14) return null
        if (payload[from].toInt() != 0 || payload[from + 1].toInt() != 0 || payload[from + 2].toInt() != 1) return null
        val streamId = payload[from + 3].toInt() and 0xFF
        // Streams whose PES header has no timestamp fields at all.
        if (streamId in NO_HEADER_STREAM_IDS) return null
        if (payload[from + 6].toInt() and 0xC0 != 0x80) return null
        if (payload[from + 7].toInt() and 0x80 == 0) return null
        val b = { i: Int -> payload[from + 9 + i].toLong() and 0xFF }
        return ((b(0) shr 1) and 0x07 shl 30) or (b(1) shl 22) or ((b(2) shr 1) shl 15) or (b(3) shl 7) or (b(4) shr 1)
    }

    private val NO_HEADER_STREAM_IDS = setOf(0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF)

    // ------------------------------------------------------------ picture size

    /**
     * Width and height from the first sequence header of the video stream:
     * H.264's sequence parameter set, MPEG-2's sequence header. Other codecs
     * are left without a size rather than guessed.
     */
    private fun pictureSize(bytes: ByteArray, length: Int, framing: Framing, pid: Int, streamType: Int): Pair<Int, Int>? {
        val elementary = firstPesPayloads(bytes, length, framing, pid, MAX_SEQUENCE_SEARCH)
        return when (streamType) {
            0x1B -> nalUnits(elementary).firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1F == 7 }
                ?.let { runCatching { h264PictureSize(unescape(it)) }.getOrNull() }
            0x01, 0x02 -> mpeg2PictureSize(elementary)
            else -> null
        }
    }

    private const val MAX_SEQUENCE_SEARCH = 256 * 1024

    /** The elementary stream of [pid] from its first PES start on, PES headers removed, up to [limit] bytes. */
    private fun firstPesPayloads(bytes: ByteArray, length: Int, framing: Framing, pid: Int, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var started = false
        forEachPacket(bytes, length, framing) { packetPid, unitStart, payload, from, to ->
            if (packetPid != pid || out.size() >= limit) return@forEachPacket
            var at = from
            if (unitStart) {
                if (to - at < 9 || payload[at].toInt() != 0 || payload[at + 1].toInt() != 0 || payload[at + 2].toInt() != 1) return@forEachPacket
                at += 9 + (payload[at + 8].toInt() and 0xFF)
                started = true
            }
            if (started && at < to) out.write(payload, at, to - at)
        }
        return out.toByteArray()
    }

    private fun nalUnits(stream: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 <= stream.size) {
            if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { n, start ->
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else stream.size
            while (end > start && stream[end - 1].toInt() == 0) end--
            stream.copyOfRange(start, end)
        }
    }

    /** Drops the emulation-prevention bytes (00 00 03) the encoder put in to keep start codes unique. */
    private fun unescape(nal: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (byte in nal) {
            val value = byte.toInt() and 0xFF
            if (zeros >= 2 && value == 3) {
                zeros = 0
                continue
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** ITU-T H.264 7.3.2.1.1, as far as the frame size and its cropping. */
    private fun h264PictureSize(sps: ByteArray): Pair<Int, Int>? {
        val bits = BitReader(sps, 8)
        val profile = bits.bits(8)
        bits.skip(16)
        bits.ue()
        var chromaFormat = 1
        if (profile in HIGH_PROFILES) {
            chromaFormat = bits.ue()
            if (chromaFormat == 3) bits.skip(1)
            bits.ue()
            bits.ue()
            bits.skip(1)
            if (bits.bits(1) == 1) {
                repeat(if (chromaFormat != 3) 8 else 12) { list ->
                    if (bits.bits(1) == 1) {
                        var last = 8
                        var next = 8
                        repeat(if (list < 6) 16 else 64) {
                            if (next != 0) next = (last + bits.se() + 256) % 256
                            if (next != 0) last = next
                        }
                    }
                }
            }
        }
        bits.ue()
        when (bits.ue()) {
            0 -> bits.ue()
            1 -> {
                bits.skip(1)
                bits.se()
                bits.se()
                repeat(bits.ue()) { bits.se() }
            }
        }
        bits.ue()
        bits.skip(1)
        val widthInMbs = bits.ue() + 1
        val heightInMapUnits = bits.ue() + 1
        val frameMbsOnly = bits.bits(1)
        if (frameMbsOnly == 0) bits.skip(1)
        bits.skip(1)
        var width = widthInMbs * 16
        var height = (2 - frameMbsOnly) * heightInMapUnits * 16
        if (bits.bits(1) == 1) {
            val left = bits.ue()
            val right = bits.ue()
            val top = bits.ue()
            val bottom = bits.ue()
            val cropX = if (chromaFormat == 0 || chromaFormat == 3) 1 else 2
            val cropY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
            width -= (left + right) * cropX
            height -= (top + bottom) * cropY
        }
        return if (width > 0 && height > 0) width to height else null
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private fun mpeg2PictureSize(stream: ByteArray): Pair<Int, Int>? {
        var i = 0
        while (i + 7 <= stream.size) {
            if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1 &&
                stream[i + 3].toInt() and 0xFF == 0xB3
            ) {
                val a = stream[i + 4].toInt() and 0xFF
                val b = stream[i + 5].toInt() and 0xFF
                val c = stream[i + 6].toInt() and 0xFF
                val width = (a shl 4) or (b shr 4)
                val height = ((b and 0x0F) shl 8) or c
                return if (width > 0 && height > 0) width to height else null
            }
            i++
        }
        return null
    }

    private class BitReader(private val bytes: ByteArray, private var bit: Int) {
        fun bits(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = bytes[bit / 8].toInt() and 0xFF
                value = (value shl 1) or ((byte shr (7 - bit % 8)) and 1)
                bit++
            }
            return value
        }

        fun skip(count: Int) {
            bit += count
        }

        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) {
                zeros++
                check(zeros <= 31)
            }
            return if (zeros == 0) 0 else ((1 shl zeros) - 1 + bits(zeros))
        }

        fun se(): Int {
            val code = ue()
            return if (code % 2 == 1) (code + 1) / 2 else -(code / 2)
        }
    }

    // ------------------------------------------------------------ packets and sections

    private inline fun forEachPacket(
        bytes: ByteArray,
        length: Int,
        framing: Framing,
        block: (pid: Int, unitStart: Boolean, payload: ByteArray, from: Int, to: Int) -> Unit
    ) {
        var packet = framing.start
        while (packet + framing.packetSize <= length) {
            val at = packet + framing.syncOffset
            packet += framing.packetSize
            if (bytes[at].toInt() and 0xFF != SYNC) continue
            val flags = bytes[at + 1].toInt() and 0xFF
            // Transport error indicator: the packet is known to be damaged.
            if (flags and 0x80 != 0) continue
            val pid = ((flags and 0x1F) shl 8) or (bytes[at + 2].toInt() and 0xFF)
            val control = (bytes[at + 3].toInt() shr 4) and 0x03
            if (control and 0x01 == 0) continue
            var from = at + 4
            if (control and 0x02 != 0) from += 1 + (bytes[at + 4].toInt() and 0xFF)
            val to = at + TS_PACKET
            if (from >= to) continue
            block(pid, flags and 0x40 != 0, bytes, from, to)
        }
    }

    /**
     * The first complete section of table [tableId] on [pid], reassembled from
     * as many packets as it spans, with its CRC checked. Returns the section
     * from its table id to the end of the CRC.
     */
    private fun section(bytes: ByteArray, length: Int, framing: Framing, pid: Int, tableId: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        var collecting = false
        forEachPacket(bytes, length, framing) { packetPid, unitStart, payload, from, to ->
            if (packetPid != pid) return@forEachPacket
            var at = from
            if (unitStart) {
                val pointer = payload[at].toInt() and 0xFF
                if (collecting) buffer.write(payload, at + 1, minOf(pointer, to - at - 1))
                val complete = completeSection(buffer.toByteArray(), tableId)
                if (collecting && complete != null) return complete
                buffer.reset()
                at += 1 + pointer
                if (at >= to) {
                    collecting = false
                    return@forEachPacket
                }
                collecting = true
            }
            if (collecting) {
                buffer.write(payload, at, to - at)
                completeSection(buffer.toByteArray(), tableId)?.let { return it }
            }
        }
        return null
    }

    private fun completeSection(data: ByteArray, tableId: Int): ByteArray? {
        if (data.size < 3 || data[0].toInt() and 0xFF != tableId) return null
        val sectionLength = ((data[1].toInt() and 0x0F) shl 8) or (data[2].toInt() and 0xFF)
        val total = 3 + sectionLength
        if (sectionLength < 9 || data.size < total) return null
        val section = data.copyOf(total)
        return section.takeIf { crc32(it, 0, total) == 0 }
    }

    private fun patPrograms(section: ByteArray): List<Pair<Int, Int>> {
        val end = section.size - 4
        return (8 until end step 4).map { at ->
            val number = ((section[at].toInt() and 0xFF) shl 8) or (section[at + 1].toInt() and 0xFF)
            val pid = ((section[at + 2].toInt() and 0x1F) shl 8) or (section[at + 3].toInt() and 0xFF)
            number to pid
        }
    }

    private fun parsePmt(section: ByteArray): Program? {
        val end = section.size - 4
        if (end < 12) return null
        val number = ((section[3].toInt() and 0xFF) shl 8) or (section[4].toInt() and 0xFF)
        val pcrPid = ((section[8].toInt() and 0x1F) shl 8) or (section[9].toInt() and 0xFF)
        val infoLength = ((section[10].toInt() and 0x0F) shl 8) or (section[11].toInt() and 0xFF)
        if (12 + infoLength > end) return null
        var registration: String? = null
        forEachDescriptor(section.copyOfRange(12, 12 + infoLength)) { tag, body ->
            if (tag == 0x05 && body.size >= 4 && registration == null) {
                registration = String(body, 0, 4, Charsets.ISO_8859_1)
            }
        }
        val streams = mutableListOf<ElementaryStream>()
        var at = 12 + infoLength
        while (at + 5 <= end) {
            val streamType = section[at].toInt() and 0xFF
            val pid = ((section[at + 1].toInt() and 0x1F) shl 8) or (section[at + 2].toInt() and 0xFF)
            val esInfoLength = ((section[at + 3].toInt() and 0x0F) shl 8) or (section[at + 4].toInt() and 0xFF)
            if (at + 5 + esInfoLength > end) break
            streams += ElementaryStream(pid, streamType, section.copyOfRange(at + 5, at + 5 + esInfoLength))
            at += 5 + esInfoLength
        }
        return Program(number, pcrPid, registration, streams)
    }

    private inline fun forEachDescriptor(descriptors: ByteArray, block: (tag: Int, body: ByteArray) -> Unit) {
        var at = 0
        while (at + 2 <= descriptors.size) {
            val tag = descriptors[at].toInt() and 0xFF
            val length = descriptors[at + 1].toInt() and 0xFF
            if (at + 2 + length > descriptors.size) return
            block(tag, descriptors.copyOfRange(at + 2, at + 2 + length))
            at += 2 + length
        }
    }

    /** CRC-32/MPEG-2 over a whole section, CRC included, is zero when the section is intact. */
    private fun crc32(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = -1
        for (i in from until to) {
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (bytes[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var value = n shl 24
        repeat(8) { value = if (value and 0x80000000.toInt() != 0) (value shl 1) xor 0x04C11DB7 else value shl 1 }
        value
    }

    private const val MIN_SYNC_RUN = 8
}
