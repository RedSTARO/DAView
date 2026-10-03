package com.daview.app.subtitle

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * media3's own extractors, except that the ASS tracks inside a Matroska file
 * that the app knows of — [listed], the track numbers the server's stream list
 * gives as their index — are kept in [tracks] for [AssSubtitleView] instead of
 * reaching media3.
 *
 * media3 parses an ASS track into plain cues, keeping the alignment, the
 * position and a few style fields — the typesetting is gone, and what is left
 * is drawn by its SubtitleView, not by the renderer that draws the same kind of
 * script when it comes in a file of its own. Nor does a parser alone help: it
 * is handed each line without the time it plays at, and a line's start is what
 * every fade, move and karaoke syllable counts from. So the Matroska extractor
 * is asked for its subtitle blocks untouched, the ASS ones are kept here with
 * their timestamps, and every other subtitle track goes on to be parsed exactly
 * as media3 would have parsed it.
 *
 * An ASS track the server never listed — a probe that failed, a track beyond
 * the part of the file it reads — goes to media3 as it always did: the app
 * has no way to offer it, and media3 still shows it when it is the default.
 */
@UnstableApi
class EmbeddedAssExtractors(
    private val tracks: EmbeddedAssTracks,
    private val listed: Set<String>
) : ExtractorsFactory {

    private val defaults = DefaultExtractorsFactory()
    private var parsers: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var parseDuringExtraction = true

    // DefaultMediaSourceFactory tells its extractors factory how subtitles are
    // to be handled; what it says has to hold for the tracks that pass through.
    @androidx.annotation.OptIn(ExperimentalApi::class)
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory {
        parseDuringExtraction = enabled
        defaults.experimentalSetTextTrackTranscodingEnabled(enabled)
        return this
    }

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
        parsers = factory
        defaults.setSubtitleParserFactory(factory)
        return this
    }

    @androidx.annotation.OptIn(ExperimentalApi::class)
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags: Int): ExtractorsFactory {
        defaults.experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags)
        return this
    }

    override fun createExtractors(): Array<Extractor> = withAss(defaults.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        withAss(defaults.createExtractors(uri, responseHeaders))

    private fun withAss(extractors: Array<Extractor>): Array<Extractor> = Array(extractors.size) { index ->
        val extractor = extractors[index]
        if (extractor is MatroskaExtractor) {
            AssKeepingMatroskaExtractor(tracks, listed, parsers.takeIf { parseDuringExtraction })
        } else {
            extractor
        }
    }
}

/**
 * A Matroska extractor whose [listed] ASS tracks go to [tracks]. [parsers] is
 * what the other subtitle tracks are parsed with, or null when media3 wants
 * them raw.
 */
@UnstableApi
private class AssKeepingMatroskaExtractor(
    private val tracks: EmbeddedAssTracks,
    private val listed: Set<String>,
    private val parsers: SubtitleParser.Factory?
) : Extractor {

    private val matroska = MatroskaExtractor(
        parsers ?: SubtitleParser.Factory.UNSUPPORTED,
        MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA
    )
    private var split: AssSplittingOutput? = null

    override fun sniff(input: ExtractorInput): Boolean = matroska.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = matroska.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        // The parsing media3 would have done inside the extractor now happens
        // one step further out, where the ASS tracks have already left.
        val downstream = parsers?.let { SubtitleTranscodingExtractorOutput(output, it) }
        val ass = AssSplittingOutput(downstream ?: output, tracks, listed)
        split = ass
        matroska.init(ass)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = matroska.read(input, seekPosition)

    // The parsers are left as they are, as media3's own Matroska extractor
    // leaves them: a DVB subtitle parser cleared here would draw nothing until
    // the stream's next full page.
    override fun seek(position: Long, timeUs: Long) {
        matroska.seek(position, timeUs)
        split?.dropPending()
    }

    override fun release() = matroska.release()

    override fun getUnderlyingImplementation(): Extractor = matroska
}

/** Sends the [listed] ASS tracks to [tracks] and everything else to [downstream]. */
@UnstableApi
private class AssSplittingOutput(
    private val downstream: ExtractorOutput,
    private val tracks: EmbeddedAssTracks,
    private val listed: Set<String>
) : ExtractorOutput {

    private val captures = ArrayList<AssLines>()

    /** Forgets a line cut short by a seek; the extractor starts it again. */
    fun dropPending() = captures.forEach { it.dropPending() }

    override fun track(id: Int, type: Int): TrackOutput =
        if (type == C.TRACK_TYPE_TEXT) TextTrack(id, type) else downstream.track(id, type)

    override fun endTracks() = downstream.endTracks()

    override fun seekMap(seekMap: SeekMap) = downstream.seekMap(seekMap)

    /**
     * A text track, which is ASS or not only once its format says so. The
     * extractor gives the format straight after asking for the track, so an ASS
     * track is never announced to media3 at all — media3 would otherwise wait
     * for samples it is never going to get.
     */
    private inner class TextTrack(private val id: Int, private val type: Int) : TrackOutput {
        private var target: TrackOutput? = null
        private val discard by lazy(LazyThreadSafetyMode.NONE) { DiscardingTrackOutput() }

        // The extractor never writes to a track before giving its format; if it
        // ever did, those bytes would have nowhere to go either way.
        private fun target(): TrackOutput = target ?: discard

        override fun format(format: Format) {
            val current = target ?: open(format).also { target = it }
            current.format(format)
        }

        private fun open(format: Format): TrackOutput {
            val key = format.id ?: id.toString()
            return if (format.sampleMimeType == MimeTypes.TEXT_SSA && key in listed) {
                AssLines(tracks.open(key, header(format))).also { captures.add(it) }
            } else {
                downstream.track(id, type)
            }
        }

        override fun durationUs(durationUs: Long) {
            target?.durationUs(durationUs)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            target().sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
            target().sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
            target().sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    /**
     * The script's header: Matroska keeps it as the track's codec data, which
     * the extractor passes on after the line layout it writes its blocks in.
     */
    private fun header(format: Format): String =
        format.initializationData.drop(1).joinToString("\n") { String(it, Charsets.UTF_8) }
}

/** Collects the blocks of one ASS track and hands each to [track] with its start time. */
@UnstableApi
private class AssLines(private val track: EmbeddedAss) : TrackOutput {

    // The bytes given since the last whole sample; a sample may arrive in parts.
    private val pending = ByteArrayOutputStream()

    fun dropPending() = pending.reset()

    override fun format(format: Format) = Unit

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val buffer = ByteArray(length)
        val read = input.read(buffer, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        pending.write(buffer, 0, read)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        val buffer = ByteArray(length)
        data.readBytes(buffer, 0, length)
        pending.write(buffer, 0, length)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        // The sample is the [size] bytes that end [offset] bytes before the
        // last one given; anything after it already belongs to the next.
        val bytes = pending.toByteArray()
        val end = bytes.size - offset
        val start = end - size
        if (start >= 0 && end <= bytes.size) {
            track.add(String(bytes, start, size, Charsets.UTF_8), timeUs / 1000)
        }
        pending.reset()
        if (offset in 1..bytes.size) pending.write(bytes, bytes.size - offset, offset)
    }
}
