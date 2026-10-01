package com.daview.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.TsExtractor
import com.daview.server.library.TsProbe
import java.io.EOFException
import kotlin.math.max
import kotlin.math.min

/**
 * [delegate]'s extractors, with one in front of them for the transport streams
 * a Blu-ray keeps its video in: the `.m2ts` files of a disc copied as it is.
 *
 * media3's TS extractor does not recognise those files at all. A Blu-ray puts
 * a four-byte arrival timestamp before every 188-byte packet, so the sync bytes
 * the extractor looks for are never where it expects them. The streams inside
 * are numbered the Blu-ray way too: 0x86 is DTS-HD Master Audio on a disc and
 * a splice table in broadcast, and 0x90, the disc's subtitles, has no reader.
 * [BluRayTsExtractor] deals with both, and turns down every other file, which
 * [delegate]'s extractors then sniff as they always did.
 *
 * It goes first because nothing else would single the file out: media3 has no
 * extension mapping for `.m2ts`, and the app's own server answers every stream
 * as application/octet-stream, so the extractors are tried in order and the
 * one that recognises the bytes wins.
 */
@UnstableApi
class BluRayExtractors(private val delegate: ExtractorsFactory) : ExtractorsFactory {

    private var parsers: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var parseDuringExtraction = true

    // DefaultMediaSourceFactory tells its extractors factory how subtitles are
    // to be handled; a disc's subtitles have to be handled the same way as a
    // file's, or the player would get them in a form it did not ask for.
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory {
        parseDuringExtraction = enabled
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled)
        return this
    }

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
        parsers = factory
        delegate.setSubtitleParserFactory(factory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags: Int): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags)
        return this
    }

    override fun createExtractors(): Array<Extractor> = withBluRay(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        withBluRay(delegate.createExtractors(uri, responseHeaders))

    private fun withBluRay(extractors: Array<Extractor>): Array<Extractor> =
        arrayOf<Extractor>(BluRayTsExtractor(parsers, parseDuringExtraction)) + extractors
}

/**
 * media3's TS extractor, reading a Blu-ray transport stream through
 * [VirtualTsInput], which leaves the arrival timestamps out.
 *
 * The translation happens here, between the file and the extractor, rather
 * than in a data source serving a stripped copy: that way every position
 * outside the extractor stays a byte of the real file — where the player
 * seeks to, where it opens the stream again after a network error, how far it
 * counts the loading to have got. Positions the extractor gives out, the seek
 * map's and those it asks to be moved to, are turned back into bytes of the
 * file on their way out.
 */
@UnstableApi
internal class BluRayTsExtractor(
    private val parsers: SubtitleParser.Factory,
    private val parseDuringExtraction: Boolean
) : Extractor {

    private var packets = BdavPackets(start = 0)
    private var transportStream: TsExtractor? = null
    private val virtualSeek = PositionHolder()
    private var input: VirtualTsInput? = null

    override fun sniff(input: ExtractorInput): Boolean {
        val from = input.peekPosition
        var head = ByteArray(SNIFF_PACKETS * BDAV_PACKET)
        var length = peekUpTo(input, head, 0)
        val framing = TsProbe.framing(head, length, minRun = SYNC_RUN) ?: return false
        // A plain transport stream, and a DVB capture with error correction
        // after every packet, are media3's own extractor's to read.
        if (framing.packetSize != BDAV_PACKET) return false
        var program = TsProbe.program(head, length, framing)
        // A disc's clips open on their tables. A recording cut out of a longer
        // stream has to wait for them to come round again, and is given as long
        // as the server's probe (TsProbe.probe) gives it, so that the player and
        // the track list agree on whether the program is a Blu-ray's.
        for (window in TABLE_WINDOWS) {
            if (program != null || length < head.size) break
            head = head.copyOf(window)
            length += peekUpTo(input, head, length)
            program = TsProbe.program(head, length, framing)
        }
        // Stream types from 0x80 up are read the Blu-ray way only in a program
        // that says it is one. A disc repeats its tables every half megabyte or
        // so, so a file still without them here is no disc's — a recording
        // with timestamps, perhaps — and its streams get the readers any
        // transport stream gets.
        val hdmv = program?.isHdmv == true
        // A disc puts an AC-3 core beside its TrueHD, every 32 ms; a remux may
        // have kept the TrueHD alone, and a core track would then never get a
        // format, which keeps media3 from playing anything. Each TrueHD PID is
        // looked into until its packets say which it is, or the window ends.
        val trueHd = program?.streams.orEmpty().filter { it.streamType == STREAM_TYPE_TRUEHD }.mapTo(HashSet()) { it.pid }
        var coredTrueHd: Set<Int>? = null
        if (hdmv && trueHd.isNotEmpty()) {
            var cores = TrueHdCores(trueHd).also { it.scan(head, length, framing.start) }
            for (window in CORE_WINDOWS) {
                if (cores.decided || length < head.size || head.size >= window) continue
                head = head.copyOf(window)
                length += peekUpTo(input, head, length)
                cores = TrueHdCores(trueHd).also { it.scan(head, length, framing.start) }
            }
            coredTrueHd = cores.cored
        }
        // A file cut out of a longer stream can start part-way into a packet.
        packets = BdavPackets(from + framing.start)
        transportStream = transportStream(
            hdmv = hdmv,
            hevc = program?.streams?.any { it.streamType == STREAM_TYPE_HEVC } == true,
            coredTrueHd = coredTrueHd
        )
        return true
    }

    override fun init(output: ExtractorOutput) {
        // Without a sniff (a factory that offers this extractor alone) the file
        // is taken to start on a packet and to be an ordinary Blu-ray.
        val extractor = transportStream ?: transportStream(hdmv = true, hevc = false).also { transportStream = it }
        extractor.init(RealPositionsOutput(output, packets))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val extractor = checkNotNull(transportStream) { "read before init" }
        // The player opens a new input every time it opens the stream again.
        val virtual = this.input?.takeIf { it.real === input } ?: VirtualTsInput(input, packets).also { this.input = it }
        val result = extractor.read(virtual, virtualSeek)
        if (result == Extractor.RESULT_SEEK) seekPosition.position = packets.toReal(virtualSeek.position)
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        transportStream?.seek(packets.toVirtual(position), timeUs)
    }

    override fun release() {
        transportStream?.release()
    }

    /**
     * The extractor media3 would make for a transport stream, except for the
     * stream types and for how far it looks for a clock reference (PCR).
     *
     * It looks for one at both ends of the file to learn the duration, and
     * around every position it tries while seeking. media3's 600 packets are
     * too few for a Blu-ray: on a disc measured here the PCRs were 90 ms apart
     * in time but up to 2588 packets apart in the file, and when the window at
     * the end holds none, the duration stays unknown and nothing in the file
     * can be seeked to. 6000 packets hold 100 ms, the most MPEG allows between
     * two PCRs, of a stream at the Blu-ray maximum of 48 Mbit/s nearly twice
     * over; an Ultra HD disc (HEVC) runs at up to three times that and gets
     * twice the window. The extractor keeps a buffer that size while it
     * searches.
     */
    private fun transportStream(hdmv: Boolean, hevc: Boolean, coredTrueHd: Set<Int>? = null) = TsExtractor(
        TsExtractor.MODE_SINGLE_PMT,
        if (parseDuringExtraction) 0 else TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        parsers,
        TimestampAdjuster(0),
        BluRayPayloadReaders(hdmv, coredTrueHd),
        (if (hevc) UHD_SEARCH_PACKETS else SEARCH_PACKETS) * TS_PACKET
    )
}

/**
 * Where the packets of a Blu-ray transport stream are: 192 bytes each from
 * [start] on, a four-byte arrival timestamp and then an ordinary 188-byte TS
 * packet. A virtual position counts only the bytes of those 188-byte packets,
 * which is all media3's extractor is shown; a real position is a byte of the
 * file. The bytes of a timestamp, and any before the first whole packet, share
 * the virtual position of the packet byte that follows them.
 */
private class BdavPackets(private val start: Long) {

    fun toVirtual(real: Long): Long {
        if (real <= start) return 0
        val offset = real - start
        return offset / BDAV_PACKET * TS_PACKET + max(0L, offset % BDAV_PACKET - TIMESTAMP)
    }

    fun toReal(virtual: Long): Long = start + virtual / TS_PACKET * BDAV_PACKET + TIMESTAMP + virtual % TS_PACKET

    fun virtualLength(realLength: Long): Long =
        if (realLength == C.LENGTH_UNSET.toLong()) realLength else toVirtual(realLength)

    /** How many bytes from [real] on are not packet bytes: the rest of a timestamp, or what precedes the first packet. */
    fun headerAt(real: Long): Int {
        if (real < start) return min(start - real + TIMESTAMP, Int.MAX_VALUE.toLong()).toInt()
        val phase = ((real - start) % BDAV_PACKET).toInt()
        return if (phase < TIMESTAMP) TIMESTAMP - phase else 0
    }

    /** How many bytes of its packet are left from [real], which is a packet byte. */
    fun payloadAt(real: Long): Int = BDAV_PACKET - ((real - start) % BDAV_PACKET).toInt()

    /** Where the first packet from [real] on has its sync byte: the first of its 188 bytes. */
    fun syncFrom(real: Long): Long {
        val first = start + TIMESTAMP
        if (real <= first) return first
        val past = (real - first) % BDAV_PACKET
        return if (past == 0L) real else real + BDAV_PACKET - past
    }

    /** The real bytes from [real], a packet byte, up to and including the [count]th packet byte. */
    fun span(real: Long, count: Int): Int = (toReal(toVirtual(real) + count - 1) + 1 - real).toInt()

    /**
     * Takes the timestamps out of [count] bytes that were read from [real] into
     * [buffer] at [offset], moving the packet bytes up to close the gaps.
     * Returns how many bytes are left.
     */
    fun keepPackets(buffer: ByteArray, offset: Int, count: Int, real: Long): Int {
        var read = 0
        var kept = 0
        var position = real
        while (read < count) {
            val header = headerAt(position)
            val length: Int
            if (header > 0) {
                length = min(header, count - read)
            } else {
                length = min(payloadAt(position), count - read)
                if (kept != read) System.arraycopy(buffer, offset + read, buffer, offset + kept, length)
                kept += length
            }
            read += length
            position += length
        }
        return kept
    }
}

/**
 * The input as media3's TS extractor has to see it: the 188-byte packets one
 * after another, positions counted in them, the timestamps left out.
 *
 * A read goes to [real] in the size it was asked for, and the timestamps are
 * taken out of the caller's buffer afterwards. The extractor reads several
 * kilobytes at a time; going packet by packet instead would make each of those
 * reads two reads of the stream for every 192 bytes. [real] hands back what it
 * has already peeked before reading any further, so a read after a peek sees
 * the same bytes, and a timestamp stepped over while peeking is stepped over
 * again, out of the peek buffer, when the read gets there.
 *
 * Reading also checks that each packet starts where [packets] says. A file
 * that lost some bytes on the way — a sector a copy could not read and left
 * out, a download resumed at the wrong place — has every packet after that
 * point a few bytes away from where [packets] puts it, and cut there, every
 * one of them would reach the extractor broken. So once the sync bytes turn up
 * at another place, the bytes handed out are cut where the packets are now
 * ([cut]), and the gap costs only the packets it fell in, as it would in any
 * player. Positions stay on [packets] all the same: every position given out
 * has to name the same byte of the file whichever input counted it, and a new
 * input starts out on [packets] again and finds the gap for itself. Peeking
 * does not check: the extractor peeks only to search for timestamps, counts on
 * the length to the byte when it does, and keeps nothing it peeks.
 */
@UnstableApi
private class VirtualTsInput(val real: ExtractorInput, private val packets: BdavPackets) : ExtractorInput {

    private var cut = packets
    private val discard = ByteArray(BDAV_PACKET + TIMESTAMP)
    private val ahead = ByteArray(SYNC_RUN * BDAV_PACKET)

    // A read or skip of nothing still goes to [real]: reading resets the peek
    // position, even when there is nothing to read.

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return real.read(buffer, offset, 0)
        while (true) {
            val from = real.position
            val read = real.read(buffer, offset, length)
            if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            val kept = keepRead(buffer, offset, read, from)
            // Nothing but timestamp came back; the packet byte after it is next.
            if (kept > 0) return kept
        }
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        if (length == 0) return real.readFully(target, offset, 0, allowEndOfInput)
        var done = 0
        while (done < length) {
            if (!skipTimestamp()) return endOfInput(done, allowEndOfInput)
            val from = real.position
            if (!real.readFully(target, offset + done, length - done, allowEndOfInput && done == 0)) return false
            done += keepRead(target, offset + done, length - done, from)
        }
        return true
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
        if (length == 0) return real.skip(0)
        if (!skipTimestamp()) return C.RESULT_END_OF_INPUT
        val from = real.position
        val skipped = real.skip(cut.span(from, min(length, MAX_SPAN)))
        if (skipped == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        return (cut.toVirtual(from + skipped) - cut.toVirtual(from)).toInt()
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        if (length == 0) return real.skipFully(0, allowEndOfInput)
        var done = 0
        while (done < length) {
            if (!skipTimestamp()) return endOfInput(done, allowEndOfInput)
            val count = min(length - done, MAX_SPAN)
            if (!real.skipFully(cut.span(real.position, count), allowEndOfInput && done == 0)) return false
            done += count
        }
        return true
    }

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val from = real.peekPosition
            val peeked = real.peek(target, offset, length)
            if (peeked == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            val kept = cut.keepPackets(target, offset, peeked, from)
            if (kept > 0) return kept
        }
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        var done = 0
        while (done < length) {
            if (!peekPastTimestamp()) return endOfInput(done, allowEndOfInput)
            val from = real.peekPosition
            if (!real.peekFully(target, offset + done, length - done, allowEndOfInput && done == 0)) return false
            done += cut.keepPackets(target, offset + done, length - done, from)
        }
        return true
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        peekFully(target, offset, length, false)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        var done = 0
        while (done < length) {
            if (!peekPastTimestamp()) return endOfInput(done, allowEndOfInput)
            val count = min(length - done, MAX_SPAN)
            if (!real.advancePeekPosition(cut.span(real.peekPosition, count), allowEndOfInput && done == 0)) return false
            done += count
        }
        return true
    }

    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, false)
    }

    override fun resetPeekPosition() = real.resetPeekPosition()

    override fun getPeekPosition(): Long = packets.toVirtual(real.peekPosition)

    override fun getPosition(): Long = packets.toVirtual(real.position)

    override fun getLength(): Long = packets.virtualLength(real.length)

    override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
        real.setRetryPosition(packets.toReal(position), e)
    }

    /**
     * Takes the timestamps out of [count] bytes just read from [from] into
     * [buffer] at [offset], and returns how many bytes are left. Every sync
     * byte is looked at on the way, and where the packets turn out to have
     * moved, the rest is cut where they are now.
     */
    private fun keepRead(buffer: ByteArray, offset: Int, count: Int, from: Long): Int {
        val end = from + count
        var kept = 0
        var done = from
        var sync = cut.syncFrom(from)
        while (sync < end) {
            val at = offset + (sync - from).toInt()
            val moved = if (buffer[at] == SYNC_BYTE) null else movedPackets(buffer, at, (end - sync).toInt(), sync)
            if (moved == null) {
                sync += BDAV_PACKET
                continue
            }
            // The packet the gap fell in goes out as far as it got; what lies
            // between it and the first whole packet after the gap is left out.
            val split = max(sync - TIMESTAMP, done)
            kept += keepSpan(buffer, offset, from, done, split, kept)
            cut = moved
            done = split
            sync = moved.syncFrom(split)
        }
        return kept + keepSpan(buffer, offset, from, done, end, kept)
    }

    /** [BdavPackets.keepPackets] for the read bytes from [start] to [end], moved up behind the [kept] bytes before them. */
    private fun keepSpan(buffer: ByteArray, offset: Int, from: Long, start: Long, end: Long, kept: Int): Int {
        val at = offset + (start - from).toInt()
        val left = cut.keepPackets(buffer, at, (end - start).toInt(), start)
        if (at != offset + kept) System.arraycopy(buffer, at, buffer, offset + kept, left)
        return left
    }

    /**
     * Where the packets are if the sync byte that should be at [missed] is not
     * there because the file lost or gained bytes before it: [SYNC_RUN] sync
     * bytes in a row, a packet apart, starting less than a packet after
     * [missed], while the ones [cut] expects after [missed] are missing too
     * (one may be a 0x47 by chance). Null for anything else — a packet broken
     * where it stands, a stretch of zeros a copy wrote for what it could not
     * read — which costs only itself. The bytes from [missed] on are the
     * [length] bytes at [at] in [buffer], and then the input's own, peeked.
     */
    private fun movedPackets(buffer: ByteArray, at: Int, length: Int, missed: Long): BdavPackets? {
        var have = min(length, ahead.size)
        System.arraycopy(buffer, at, ahead, 0, have)
        if (have < ahead.size) {
            // Peeked from where the read stopped: the next read takes the same
            // bytes out of the peek buffer, and the peek position is the read
            // position again, as a read leaves it.
            real.resetPeekPosition()
            have += peekUpTo(real, ahead, have)
            real.resetPeekPosition()
            // Too near the end of the file to tell.
            if (have < ahead.size) return null
        }
        var stillThere = 0
        for (packet in 1 until SYNC_RUN) if (ahead[packet * BDAV_PACKET] == SYNC_BYTE) stillThere++
        if (stillThere > 1) return null
        for (sync in 1 until BDAV_PACKET) {
            var packet = 0
            while (packet < SYNC_RUN && ahead[sync + packet * BDAV_PACKET] == SYNC_BYTE) packet++
            if (packet == SYNC_RUN) return BdavPackets(missed + sync - TIMESTAMP)
        }
        return null
    }

    /**
     * Moves the read position on to the next packet byte. False if the input
     * ends first, which for the extractor is the end of the input right there:
     * a file cut inside a timestamp has no more packet bytes.
     */
    private fun skipTimestamp(): Boolean {
        var left = cut.headerAt(real.position)
        while (left > 0) {
            val skipped = real.skip(left)
            if (skipped == C.RESULT_END_OF_INPUT) return false
            left -= skipped
        }
        return true
    }

    /** [skipTimestamp] for the peek position. */
    private fun peekPastTimestamp(): Boolean {
        var left = cut.headerAt(real.peekPosition)
        while (left > 0) {
            val peeked = real.peek(discard, 0, min(left, discard.size))
            if (peeked == C.RESULT_END_OF_INPUT) return false
            left -= peeked
        }
        return true
    }

    // What ExtractorInput promises for a block cut short: nothing read at all
    // is what allowEndOfInput allows; any part of a block is an error.
    private fun endOfInput(done: Int, allowEndOfInput: Boolean): Boolean {
        if (done == 0 && allowEndOfInput) return false
        throw EOFException()
    }
}

/** Hands on what the extractor finds, with the seek map's positions turned into bytes of the file. */
@UnstableApi
private class RealPositionsOutput(
    private val output: ExtractorOutput,
    private val packets: BdavPackets
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)

    override fun endTracks() = output.endTracks()

    override fun seekMap(seekMap: SeekMap) = output.seekMap(object : ForwardingSeekMap(seekMap) {
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val points = seekMap.getSeekPoints(timeUs)
            val first = real(points.first)
            return if (points.second == points.first) SeekMap.SeekPoints(first) else SeekMap.SeekPoints(first, real(points.second))
        }
    })

    private fun real(point: SeekPoint) = SeekPoint(point.timeUs, packets.toReal(point.position))
}

/**
 * Which of the TrueHD streams on [pids] carry the AC-3 core a disc puts
 * beside its TrueHD: PES packets marked 0x76 in their stream_id_extension.
 * A stream is taken to have none once its packets have run on for
 * [CORELESS_AFTER] without one, since a disc sends a core frame every 32 ms;
 * one not seen for that long in the bytes there are is taken to have a core,
 * as a disc's has.
 */
private class TrueHdCores(private val pids: Set<Int>) {
    // Per stream: the first and the latest presentation time seen.
    private val first = HashMap<Int, Long>()
    private val latest = HashMap<Int, Long>()
    private val withCore = HashSet<Int>()

    private fun coreless(pid: Int): Boolean {
        val from = first[pid] ?: return false
        val to = latest[pid] ?: return false
        return pid !in withCore && (to - from + PTS_WRAP) % PTS_WRAP in CORELESS_AFTER until PTS_WRAP / 2
    }

    /** Whether every stream is known to have a core or not. */
    val decided: Boolean get() = pids.all { it in withCore || coreless(it) }

    /** The streams taken to carry a core: all but those known to have none. */
    val cored: Set<Int> get() = pids.filterNotTo(HashSet()) { coreless(it) }

    /**
     * Reads the 192-byte packets of [bytes] from [start] on. A file that lost
     * bytes on the way has its packets somewhere else after the gap; they are
     * found again there, as the extractor itself finds them.
     */
    fun scan(bytes: ByteArray, length: Int, start: Int) {
        var packet = start
        while (packet + BDAV_PACKET <= length && !decided) {
            val ts = packet + TIMESTAMP
            if (bytes[ts] != SYNC_BYTE) {
                val rest = bytes.copyOfRange(packet + 1, length)
                val framing = TsProbe.framing(rest, rest.size, SYNC_RUN)?.takeIf { it.packetSize == BDAV_PACKET } ?: return
                packet += 1 + framing.start
                continue
            }
            packet += BDAV_PACKET
            val flags = bytes[ts + 1].toInt() and 0xFF
            val pid = ((flags and 0x1F) shl 8) or (bytes[ts + 2].toInt() and 0xFF)
            if (flags and 0x40 == 0 || pid !in pids) continue
            val control = (bytes[ts + 3].toInt() shr 4) and 0x03
            if (control and 0x01 == 0) continue
            var at = ts + 4
            if (control and 0x02 != 0) at += 1 + (bytes[ts + 4].toInt() and 0xFF)
            val end = ts + TS_PACKET
            if (at + 9 > end || bytes[at].toInt() != 0 || bytes[at + 1].toInt() != 0 || bytes[at + 2].toInt() != 1) continue
            val headerEnd = min(end, at + 9 + (bytes[at + 8].toInt() and 0xFF))
            if (bytes[at + 7].toInt() and 0x80 != 0 && at + 14 <= headerEnd) {
                val b = { i: Int -> bytes[at + 9 + i].toLong() and 0xFF }
                val pts = ((b(0) shr 1) and 0x07 shl 30) or (b(1) shl 22) or ((b(2) shr 1) shl 15) or (b(3) shl 7) or (b(4) shr 1)
                first.getOrPut(pid) { pts }
                latest[pid] = pts
            }
            val extension = pesExtensionStart(bytes, at, headerEnd)
            if (extension >= 0 && pesStreamIdExtension(bytes, extension, headerEnd) == AC3_CORE) withCore += pid
        }
    }
}

/** Peeks into [target] from [from] on, as far as the input goes, and returns how many bytes that was. */
@UnstableApi
private fun peekUpTo(input: ExtractorInput, target: ByteArray, from: Int): Int {
    var length = from
    while (length < target.size) {
        val peeked = input.peek(target, length, target.size - length)
        if (peeked == C.RESULT_END_OF_INPUT) break
        length += peeked
    }
    return length - from
}

private const val BDAV_PACKET = 192
private const val TS_PACKET = 188
private const val TIMESTAMP = BDAV_PACKET - TS_PACKET
private const val SYNC_BYTE: Byte = 0x47
private const val SNIFF_PACKETS = 64
private const val SEARCH_PACKETS = 6000
private const val UHD_SEARCH_PACKETS = 12000
private const val STREAM_TYPE_HEVC = 0x24
private const val STREAM_TYPE_TRUEHD = 0x83

// The stream_id_extension of the PES packets that carry a TrueHD stream's AC-3 core.
private const val AC3_CORE = 0x76

// How far the sniff looks for that core: the windows of the table search.
// AC-3 frames come every 32 ms, some five to a megabyte at the Blu-ray maximum
// of 48 Mbit/s, so four megabytes hold enough of them to tell.
private val CORE_WINDOWS = intArrayOf(1 shl 20, 4 shl 20)

// Half a second of a TrueHD stream (in 90 kHz ticks), which on a disc holds
// some fifteen core frames: none in that time means there is no core.
private const val CORELESS_AFTER = 45_000L
private const val PTS_WRAP = 1L shl 33

// How many sync bytes in a row, a packet apart, it takes to believe that the
// packets are there, rather than a byte that happens to be 0x47.
private const val SYNC_RUN = 5

// How far the sniff looks for the program tables, after its first 64 packets:
// what the server's probe reads (TsProbe.probe), first 1 MiB, then 4 MiB.
private val TABLE_WINDOWS = intArrayOf(1 shl 20, 4 shl 20)

// The most packet bytes turned into one span of the real input at a time, so
// that the span, a little longer, still fits in an Int.
private const val MAX_SPAN = 1 shl 30
