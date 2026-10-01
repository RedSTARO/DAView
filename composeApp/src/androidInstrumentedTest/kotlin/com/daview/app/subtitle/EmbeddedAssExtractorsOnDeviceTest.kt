package com.daview.app.subtitle

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
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.CueDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daview.app.player.BluRayExtractors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Runs the extractor over a small Matroska file holding one SRT track and one
 * ASS track: the ASS has to end up with the app and nowhere else, and the SRT
 * has to reach media3 exactly as media3's own extractor would have sent it.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class EmbeddedAssExtractorsOnDeviceTest {

    private val header = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 1920
        PlayResY: 1080

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,60,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent()

    @Test
    fun theAssTrackStaysWithTheAppAndTheSrtTrackIsParsedAsBefore() {
        val tracks = EmbeddedAssTracks()
        val output = extract(EmbeddedAssExtractors(tracks, LISTED))

        // Only the SRT track was ever announced to media3.
        assertEquals(listOf(SRT_TRACK), output.tracks.keys.toList())
        assertTrue(output.ended)
        val srt = output.tracks.getValue(SRT_TRACK)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, srt.format?.sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, srt.format?.codecs)
        val (timeUs, bytes) = srt.samples.single()
        val cues = CueDecoder().decode(timeUs, bytes, 0, bytes.size)
        assertEquals("hello from srt", cues.cues.single().text.toString())
        assertEquals(1_000_000L, cues.startTimeUs)
        assertEquals(2_000_000L, cues.durationUs)

        val script = checkNotNull(tracks[ASS_TRACK.toString()]).snapshot()
        assertEquals(1920, script.playResX)
        val event = script.events.single()
        assertEquals(1_500L, event.startMs)
        assertEquals(2_500L, event.endMs)
        assertEquals(1, event.layer)
        assertEquals("Default", event.styleName)
        assertEquals("{\\an8\\fad(100,0)}top, with a comma", event.text)
    }

    @Test
    fun withParsingDuringExtractionOffTheSrtTrackStaysRaw() {
        val tracks = EmbeddedAssTracks()
        val factory = EmbeddedAssExtractors(tracks, LISTED).experimentalSetTextTrackTranscodingEnabled(false)
        val output = extract(factory)

        assertEquals(listOf(SRT_TRACK), output.tracks.keys.toList())
        assertEquals(MimeTypes.APPLICATION_SUBRIP, output.tracks.getValue(SRT_TRACK).format?.sampleMimeType)
        assertEquals(1, checkNotNull(tracks[ASS_TRACK.toString()]).snapshot().events.size)
    }

    @Test
    fun anAssTrackTheServerNeverListedGoesToMedia3AsBefore() {
        // Nothing in the menu could offer it, so media3 keeps it — and shows
        // it, when it is the default.
        val tracks = EmbeddedAssTracks()
        val output = extract(EmbeddedAssExtractors(tracks, emptySet()))

        assertEquals(listOf(SRT_TRACK, ASS_TRACK), output.tracks.keys.toList())
        val ass = output.tracks.getValue(ASS_TRACK)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, ass.format?.sampleMimeType)
        assertEquals(MimeTypes.TEXT_SSA, ass.format?.codecs)
        assertEquals(1, ass.samples.size)
        assertEquals(null, tracks[ASS_TRACK.toString()])
    }

    @Test
    fun otherContainersAreLeftToMedia3() {
        val extractors = EmbeddedAssExtractors(EmbeddedAssTracks(), LISTED).createExtractors()
        // Exactly one Matroska extractor, and it is the one that keeps the ASS.
        val matroska = extractors.filter { it.underlyingImplementation is MatroskaExtractor }
        assertEquals(1, matroska.size)
        assertFalse(matroska.single() is MatroskaExtractor)
        assertTrue(extractors.size > 5)
    }

    @Test
    fun throughTheBluRayExtractorsTheAssTrackStillReachesTheApp() {
        // The player puts the Blu-ray transport stream extractor in front of
        // these; a Matroska file has to get past it to the one keeping the ASS,
        // picked the way media3 picks, by asking each in turn.
        val tracks = EmbeddedAssTracks()
        val factory = BluRayExtractors(EmbeddedAssExtractors(tracks, LISTED))
        val bytes = matroska()
        val chosen = factory.createExtractors(Uri.parse("https://example.invalid/episode.mkv"), emptyMap())
            .first { candidate -> runCatching { candidate.sniff(input(bytes, 0)) }.getOrDefault(false) }
        assertTrue(chosen.underlyingImplementation is MatroskaExtractor)
        assertFalse(chosen is MatroskaExtractor)

        val output = extract(factory)
        assertEquals(listOf(SRT_TRACK), output.tracks.keys.toList())
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, output.tracks.getValue(SRT_TRACK).format?.sampleMimeType)
        assertEquals("{\\an8\\fad(100,0)}top, with a comma", checkNotNull(tracks[ASS_TRACK.toString()]).snapshot().events.single().text)
    }

    private fun extract(factory: ExtractorsFactory): RecordingOutput {
        val bytes = matroska()
        val extractor = factory.createExtractors().first { it.underlyingImplementation is MatroskaExtractor }
        assertTrue(extractor.sniff(input(bytes, 0)))
        val output = RecordingOutput()
        extractor.init(output)
        var input = input(bytes, 0)
        val seek = PositionHolder()
        while (true) {
            when (extractor.read(input, seek)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> input = input(bytes, seek.position)
            }
        }
        return output
    }

    private fun input(bytes: ByteArray, position: Long): DefaultExtractorInput {
        val source = ByteArrayDataSource(bytes)
        source.open(DataSpec.Builder().setUri(Uri.EMPTY).setPosition(position).build())
        return DefaultExtractorInput(source, position, bytes.size.toLong())
    }

    // --- a Matroska file, element by element -------------------------------

    private fun matroska(): ByteArray = element(
        0x1A45DFA3, // EBML
        uint(0x4286, 1), uint(0x42F7, 1), uint(0x42F2, 4), uint(0x42F3, 8),
        string(0x4282, "matroska"), uint(0x4287, 4), uint(0x4285, 2)
    ) + element(
        0x18538067, // Segment
        element(0x1549A966, uint(0x2AD7B1, 1_000_000)), // Info: millisecond timecodes
        element(
            0x1654AE6B, // Tracks
            element(0xAE, uint(0xD7, SRT_TRACK.toLong()), uint(0x73C5, 1), uint(0x83, 0x11), string(0x86, "S_TEXT/UTF8")),
            element(
                0xAE, uint(0xD7, ASS_TRACK.toLong()), uint(0x73C5, 2), uint(0x83, 0x11), string(0x86, "S_TEXT/ASS"),
                element(0x63A2, header.toByteArray(Charsets.UTF_8))
            )
        ),
        element(
            0x1F43B675, // Cluster
            uint(0xE7, 0),
            element(0xA0, element(0xA1, block(SRT_TRACK, 1000, "hello from srt")), uint(0x9B, 2000)),
            element(
                0xA0,
                element(0xA1, block(ASS_TRACK, 1500, "7,1,Default,,0,0,0,,{\\an8\\fad(100,0)}top, with a comma")),
                uint(0x9B, 1000)
            )
        )
    )

    private fun element(id: Long, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { all, next -> all + next }
        return idBytes(id) + size(body.size.toLong()) + body
    }

    private fun idBytes(id: Long): ByteArray {
        val length = (64 - java.lang.Long.numberOfLeadingZeros(id) + 7) / 8
        return ByteArray(length) { ((id shr (8 * (length - 1 - it))) and 0xFF).toByte() }
    }

    /** Every size is written eight bytes long, which EBML allows for any value. */
    private fun size(value: Long): ByteArray =
        ByteArray(8) { if (it == 0) 0x01 else ((value shr (8 * (7 - it))) and 0xFF).toByte() }

    private fun uint(id: Long, value: Long): ByteArray =
        element(id, ByteArray(8) { ((value shr (8 * (7 - it))) and 0xFF).toByte() })

    private fun string(id: Long, value: String): ByteArray = element(id, value.toByteArray(Charsets.UTF_8))

    /** A Block with no lacing: track number, timecode relative to the cluster, flags, data. */
    private fun block(track: Int, timecode: Int, payload: String): ByteArray =
        byteArrayOf((0x80 or track).toByte(), (timecode shr 8).toByte(), timecode.toByte(), 0) +
            payload.toByteArray(Charsets.UTF_8)

    private class RecordingOutput : ExtractorOutput {
        val tracks = LinkedHashMap<Int, RecordingTrack>()
        var ended = false

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { RecordingTrack() }

        override fun endTracks() {
            ended = true
        }

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class RecordingTrack : TrackOutput {
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
        const val SRT_TRACK = 3
        const val ASS_TRACK = 4

        /** What the server's stream list says the file holds: the ASS track. */
        val LISTED = setOf(ASS_TRACK.toString())
    }
}
