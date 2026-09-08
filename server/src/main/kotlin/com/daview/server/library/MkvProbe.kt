package com.daview.server.library

import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType

/**
 * Just enough EBML/Matroska to answer the three questions the app has about a
 * remote file without downloading it: how long is it, what tracks does it have,
 * and where in the byte stream does a given timestamp live.
 *
 * Everything is driven by a [RangeReader] so the same code works over WebDAV,
 * a CDN redirect or a local file.
 */
object MkvProbe {

    fun interface RangeReader {
        /** Reads [length] bytes starting at [start]; may return fewer at EOF. */
        fun read(start: Long, length: Int): ByteArray
    }

    data class TrackInfo(
        val number: Int,
        val type: StreamType,
        val codec: String?,
        val language: String?,
        val name: String?,
        val isDefault: Boolean,
        val isForced: Boolean,
        val width: Int? = null,
        val height: Int? = null,
        val channels: Int? = null
    )

    data class MkvInfo(
        val durationMs: Long?,
        val tracks: List<TrackInfo>,
        val title: String?,
        /** Absolute byte offset of the Segment payload; cue positions are relative to it. */
        val segmentDataOffset: Long,
        val seekPositions: Map<Long, Long>
    )

    /** `(timestampMs, absoluteByteOffset)` pairs, sorted by timestamp. */
    data class CueIndex(val entries: List<Pair<Long, Long>>) {
        fun timeAtOrBefore(byteOffset: Long): Long? =
            entries.lastOrNull { it.second <= byteOffset }?.first

        fun byteAtOrBefore(timeMs: Long): Long? =
            entries.lastOrNull { it.first <= timeMs }?.second

        val isEmpty: Boolean get() = entries.isEmpty()
    }

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_DURATION = 0x4489L
    private const val ID_TITLE = 0x7BA9L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_FLAG_DEFAULT = 0x88L
    private const val ID_FLAG_FORCED = 0x55AAL
    private const val ID_CODEC_ID = 0x86L
    private const val ID_TRACK_NAME = 0x536EL
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_LANGUAGE_BCP47 = 0x22B59DL
    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXEL_WIDTH = 0xB0L
    private const val ID_PIXEL_HEIGHT = 0xBAL
    private const val ID_AUDIO = 0xE1L
    private const val ID_CHANNELS = 0x9FL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_CLUSTER_POSITION = 0xF1L

    private const val HEAD_READ_SIZE = 512 * 1024

    fun isMatroska(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext == "mkv" || ext == "mka" || ext == "webm" || ext == "mk3d"
    }

    /**
     * Reads the head of the file and returns duration + track list. Returns null
     * when the file is not Matroska or the header is not where it should be.
     */
    fun probe(reader: RangeReader): MkvInfo? {
        val head = reader.read(0, HEAD_READ_SIZE)
        if (head.size < 64) return null
        val cursor = Cursor(head, 0)
        val ebml = cursor.readElementHeader() ?: return null
        if (ebml.id != ID_EBML) return null
        cursor.position = ebml.dataStart + ebml.size.coerceAtLeast(0)

        val segment = cursor.readElementHeader() ?: return null
        if (segment.id != ID_SEGMENT) return null
        val segmentDataOffset = segment.dataStart

        var durationMs: Long? = null
        var timecodeScale = 1_000_000L
        var title: String? = null
        val tracks = mutableListOf<TrackInfo>()
        val seekPositions = mutableMapOf<Long, Long>()
        var rawDuration: Double? = null

        var position = segment.dataStart
        val limit = head.size.toLong()
        while (position < limit - 8) {
            cursor.position = position
            val element = cursor.readElementHeader() ?: break
            val size = if (element.size < 0) limit - element.dataStart else element.size
            val end = element.dataStart + size
            when (element.id) {
                ID_SEEK_HEAD -> parseSeekHead(head, element.dataStart, minOf(end, limit), seekPositions)
                ID_INFO -> {
                    forEachChild(head, element.dataStart, minOf(end, limit)) { child, data ->
                        when (child.id) {
                            ID_TIMECODE_SCALE -> timecodeScale = readUInt(data)
                            ID_DURATION -> rawDuration = readFloat(data)
                            ID_TITLE -> title = readString(data)
                        }
                    }
                }
                ID_TRACKS -> {
                    forEachChild(head, element.dataStart, minOf(end, limit)) { child, data ->
                        if (child.id == ID_TRACK_ENTRY) parseTrackEntry(data)?.let { tracks += it }
                    }
                }
            }
            if (element.id == ID_TRACKS && tracks.isNotEmpty() && rawDuration != null) break
            if (end <= position) break
            position = end
        }

        rawDuration?.let { durationMs = (it * timecodeScale / 1_000_000.0).toLong() }
        if (tracks.isEmpty() && durationMs == null) return null
        return MkvInfo(durationMs, tracks, title, segmentDataOffset, seekPositions)
    }

    /**
     * Loads the cue index. Cues normally sit at the tail of the file, so the
     * SeekHead from [probe] is used to jump straight at them.
     */
    fun probeCues(reader: RangeReader, info: MkvInfo, fileSize: Long): CueIndex {
        val relative = info.seekPositions[ID_CUES] ?: return scanTailForCues(reader, info, fileSize)
        val absolute = info.segmentDataOffset + relative
        if (absolute <= 0 || absolute >= fileSize) return CueIndex(emptyList())
        return readCuesAt(reader, info, absolute, fileSize)
    }

    private fun scanTailForCues(reader: RangeReader, info: MkvInfo, fileSize: Long): CueIndex {
        // Some muxers omit the SeekHead entry; the Cues element is then usually
        // within the last few megabytes.
        val window = minOf(8L * 1024 * 1024, fileSize)
        val start = fileSize - window
        val bytes = reader.read(start, window.toInt())
        val marker = byteArrayOf(0x1C, 0x53.toByte(), 0xBB.toByte(), 0x6B)
        val index = indexOf(bytes, marker) ?: return CueIndex(emptyList())
        return readCuesAt(reader, info, start + index, fileSize)
    }

    private fun readCuesAt(reader: RangeReader, info: MkvInfo, absolute: Long, fileSize: Long): CueIndex {
        val header = reader.read(absolute, 16)
        val cursor = Cursor(header, 0)
        val element = cursor.readElementHeader() ?: return CueIndex(emptyList())
        if (element.id != ID_CUES) return CueIndex(emptyList())
        val headerLength = (element.dataStart - 0)
        val size = if (element.size <= 0) minOf(4L * 1024 * 1024, fileSize - absolute) else element.size
        val payload = reader.read(absolute + headerLength, minOf(size, 16L * 1024 * 1024).toInt())

        val entries = mutableListOf<Pair<Long, Long>>()
        forEachChild(payload, 0, payload.size.toLong()) { child, data ->
            if (child.id != ID_CUE_POINT) return@forEachChild
            var time: Long? = null
            var cluster: Long? = null
            forEachChild(data, 0, data.size.toLong()) { inner, innerData ->
                when (inner.id) {
                    ID_CUE_TIME -> time = readUInt(innerData)
                    ID_CUE_TRACK_POSITIONS -> forEachChild(innerData, 0, innerData.size.toLong()) { leaf, leafData ->
                        if (leaf.id == ID_CUE_CLUSTER_POSITION && cluster == null) cluster = readUInt(leafData)
                    }
                }
            }
            val t = time
            val c = cluster
            if (t != null && c != null) entries += (t to info.segmentDataOffset + c)
        }
        // CueTime is expressed in timecode-scale units; the default 1ms scale is
        // overwhelmingly common, and probe() already normalised duration for the rest.
        return CueIndex(entries.sortedBy { it.first })
    }

    fun toMediaStreams(tracks: List<TrackInfo>): List<MediaStreamDto> = tracks.map { track ->
        MediaStreamDto(
            index = track.number,
            type = track.type,
            codec = normaliseCodec(track.codec),
            language = track.language,
            title = track.name,
            isDefault = track.isDefault,
            isForced = track.isForced,
            isExternal = false,
            channels = track.channels,
            width = track.width,
            height = track.height
        )
    }

    private fun normaliseCodec(codec: String?): String? = when {
        codec == null -> null
        codec.startsWith("V_MPEG4/ISO/AVC") -> "h264"
        codec.startsWith("V_MPEGH/ISO/HEVC") -> "hevc"
        codec.startsWith("V_AV1") -> "av1"
        codec.startsWith("V_VP9") -> "vp9"
        codec.startsWith("A_AAC") -> "aac"
        codec.startsWith("A_AC3") -> "ac3"
        codec.startsWith("A_EAC3") -> "eac3"
        codec.startsWith("A_DTS") -> "dts"
        codec.startsWith("A_FLAC") -> "flac"
        codec.startsWith("A_OPUS") -> "opus"
        codec.startsWith("A_TRUEHD") -> "truehd"
        codec.startsWith("S_TEXT/ASS") || codec.startsWith("S_TEXT/SSA") -> "ass"
        codec.startsWith("S_TEXT/UTF8") -> "srt"
        codec.startsWith("S_TEXT/WEBVTT") -> "vtt"
        codec.startsWith("S_HDMV/PGS") -> "pgs"
        codec.startsWith("S_VOBSUB") -> "vobsub"
        else -> codec.substringAfterLast('/').lowercase()
    }

    // ------------------------------------------------------------ EBML plumbing

    private data class ElementHeader(val id: Long, val dataStart: Long, val size: Long)

    private class Cursor(val bytes: ByteArray, var position: Long) {
        fun readElementHeader(): ElementHeader? {
            val idStart = position
            if (idStart >= bytes.size) return null
            val id = readId() ?: return null
            val size = readSize() ?: return null
            return ElementHeader(id, position, size)
        }

        private fun readId(): Long? {
            val index = position.toInt()
            if (index >= bytes.size) return null
            val first = bytes[index].toInt() and 0xFF
            val length = when {
                first and 0x80 != 0 -> 1
                first and 0x40 != 0 -> 2
                first and 0x20 != 0 -> 3
                first and 0x10 != 0 -> 4
                else -> return null
            }
            if (index + length > bytes.size) return null
            var value = 0L
            for (i in 0 until length) value = (value shl 8) or (bytes[index + i].toLong() and 0xFF)
            position += length
            return value
        }

        private fun readSize(): Long? {
            val index = position.toInt()
            if (index >= bytes.size) return null
            val first = bytes[index].toInt() and 0xFF
            if (first == 0) return null
            var mask = 0x80
            var length = 1
            while (length <= 8 && first and mask == 0) {
                mask = mask shr 1
                length++
            }
            if (length > 8 || index + length > bytes.size) return null
            var value = (first and (mask - 1)).toLong()
            var allOnes = value == (mask - 1).toLong()
            for (i in 1 until length) {
                val byte = bytes[index + i].toLong() and 0xFF
                if (byte != 0xFFL) allOnes = false
                value = (value shl 8) or byte
            }
            position += length
            return if (allOnes) -1L else value
        }
    }

    private inline fun forEachChild(
        bytes: ByteArray,
        start: Long,
        end: Long,
        action: (ElementHeader, ByteArray) -> Unit
    ) {
        var position = start
        val hardEnd = minOf(end, bytes.size.toLong())
        while (position < hardEnd) {
            val cursor = Cursor(bytes, position)
            val element = cursor.readElementHeader() ?: return
            if (element.size < 0) return
            val dataEnd = element.dataStart + element.size
            if (dataEnd > hardEnd) return
            action(element, bytes.copyOfRange(element.dataStart.toInt(), dataEnd.toInt()))
            if (dataEnd <= position) return
            position = dataEnd
        }
    }

    private fun parseSeekHead(bytes: ByteArray, start: Long, end: Long, into: MutableMap<Long, Long>) {
        forEachChild(bytes, start, end) { child, data ->
            if (child.id != ID_SEEK) return@forEachChild
            var seekId: Long? = null
            var seekPosition: Long? = null
            forEachChild(data, 0, data.size.toLong()) { inner, innerData ->
                when (inner.id) {
                    ID_SEEK_ID -> {
                        var value = 0L
                        innerData.forEach { value = (value shl 8) or (it.toLong() and 0xFF) }
                        seekId = value
                    }
                    ID_SEEK_POSITION -> seekPosition = readUInt(innerData)
                }
            }
            val id = seekId
            val pos = seekPosition
            if (id != null && pos != null) into[id] = pos
        }
    }

    private fun parseTrackEntry(data: ByteArray): TrackInfo? {
        var number: Int? = null
        var type: Int? = null
        var codec: String? = null
        var name: String? = null
        var language: String? = null
        var languageBcp47: String? = null
        var isDefault = true
        var isForced = false
        var width: Int? = null
        var height: Int? = null
        var channels: Int? = null

        forEachChild(data, 0, data.size.toLong()) { child, childData ->
            when (child.id) {
                ID_TRACK_NUMBER -> number = readUInt(childData).toInt()
                ID_TRACK_TYPE -> type = readUInt(childData).toInt()
                ID_CODEC_ID -> codec = readString(childData)
                ID_TRACK_NAME -> name = readString(childData)
                ID_LANGUAGE -> language = readString(childData)
                ID_LANGUAGE_BCP47 -> languageBcp47 = readString(childData)
                ID_FLAG_DEFAULT -> isDefault = readUInt(childData) == 1L
                ID_FLAG_FORCED -> isForced = readUInt(childData) == 1L
                ID_VIDEO -> forEachChild(childData, 0, childData.size.toLong()) { inner, innerData ->
                    when (inner.id) {
                        ID_PIXEL_WIDTH -> width = readUInt(innerData).toInt()
                        ID_PIXEL_HEIGHT -> height = readUInt(innerData).toInt()
                    }
                }
                ID_AUDIO -> forEachChild(childData, 0, childData.size.toLong()) { inner, innerData ->
                    if (inner.id == ID_CHANNELS) channels = readUInt(innerData).toInt()
                }
            }
        }

        val streamType = when (type) {
            1 -> StreamType.VIDEO
            2 -> StreamType.AUDIO
            17 -> StreamType.SUBTITLE
            else -> return null
        }
        return TrackInfo(
            number = number ?: return null,
            type = streamType,
            codec = codec,
            language = (languageBcp47 ?: language)?.takeIf { it.isNotBlank() && it != "und" },
            name = name,
            isDefault = isDefault,
            isForced = isForced,
            width = width,
            height = height,
            channels = channels
        )
    }

    private fun readUInt(bytes: ByteArray): Long {
        var value = 0L
        bytes.forEach { value = (value shl 8) or (it.toLong() and 0xFF) }
        return value
    }

    private fun readFloat(bytes: ByteArray): Double = when (bytes.size) {
        4 -> java.lang.Float.intBitsToFloat(readUInt(bytes).toInt()).toDouble()
        8 -> java.lang.Double.longBitsToDouble(readUInt(bytes))
        else -> 0.0
    }

    private fun readString(bytes: ByteArray): String? =
        String(bytes, Charsets.UTF_8).trimEnd(' ').takeIf { it.isNotBlank() }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }
}
