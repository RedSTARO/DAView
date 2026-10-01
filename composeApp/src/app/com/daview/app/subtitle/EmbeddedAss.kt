package com.daview.app.subtitle

/**
 * An ASS track carried inside the video, put together as the player reads it.
 *
 * Matroska keeps a script's header — `[Script Info]` and the styles — once, in
 * the track's codec data, and every `Dialogue:` line as a block of its own at
 * the moment it plays, so there is no file to fetch: the events arrive with
 * the video, a buffer's length ahead of the picture, the way mpv hands them to
 * libass. media3's Matroska extractor turns each block into
 * `Dialogue: 0:00:00:00,<duration>,<ReadOrder>,<Layer>,<Style>,...,<Text>` —
 * the start is the block's own timestamp, which arrives beside the text rather
 * than in it — and that is what [add] takes.
 *
 * Blocks come round again whenever the player reads a stretch twice, after a
 * seek back most often, so a line read twice is kept once: the same text at
 * the same moment is the same line. ReadOrder — the line's place in the
 * original script — would say the same on its own, but not every muxer fills
 * it in, and one that writes 0 throughout would fold a whole track into a
 * single line. It still orders the lines that start together.
 *
 * The lines are kept in order as they come, which is nearly always at the end:
 * a snapshot, taken every time the track grows, is then a copy rather than a
 * sort of everything read so far.
 *
 * Written from the player's loading thread and read from the one that draws.
 */
class EmbeddedAss(header: String) {

    private class Line(val readOrder: Int, val event: AssEvent)

    private val base = AssScript.parse(header)
    private val seen = HashSet<String>()
    private val ordered = ArrayList<Line>()

    /** Moves every time a line is added, so a reader can tell there is more. */
    @Volatile
    var version: Int = 0
        private set

    /**
     * Adds one block as media3 wrote it, starting at [startMs] in the video.
     * Returns whether it was new.
     */
    fun add(sample: String, startMs: Long): Boolean {
        val line = sample.trim().removePrefix("﻿")
        if (!line.startsWith(DIALOGUE, ignoreCase = true)) return false
        val fields = line.substring(DIALOGUE.length)
        val readOrder = fields.split(',', limit = 4).getOrNull(2)?.trim()?.toIntOrNull() ?: return false
        val parsed = AssEvent.parse(fields, SAMPLE_FORMAT) ?: return false
        // The sample's own start is always zero and its end is the duration.
        val event = parsed.copy(startMs = startMs, endMs = startMs + (parsed.endMs - parsed.startMs))
        synchronized(ordered) {
            if (!seen.add("$startMs|$fields")) return false
            // After the last line that starts no later — in start order, then
            // in the order the script wrote them.
            var low = 0
            var high = ordered.size
            while (low < high) {
                val mid = (low + high) ushr 1
                val other = ordered[mid]
                val before = other.event.startMs < startMs ||
                    (other.event.startMs == startMs && other.readOrder <= readOrder)
                if (before) low = mid + 1 else high = mid
            }
            ordered.add(low, Line(readOrder, event))
            version++
        }
        return true
    }

    /**
     * The script as it stands: the header's styles and every line read so far,
     * in the order they start and then in the order they were written.
     */
    fun snapshot(): AssScript {
        val events = synchronized(ordered) { ordered.map { it.event } }
        return AssScript(
            playResX = base.playResX,
            playResY = base.playResY,
            wrapStyle = base.wrapStyle,
            scaledBorderAndShadow = base.scaledBorderAndShadow,
            styles = base.styles,
            events = events
        )
    }

    private companion object {
        const val DIALOGUE = "Dialogue:"

        /** The line layout media3 writes, which is its `SSA_DIALOGUE_FORMAT`. */
        val SAMPLE_FORMAT = listOf(
            "start", "end", "readorder", "layer", "style", "name",
            "marginl", "marginr", "marginv", "effect", "text"
        )
    }
}

/**
 * The ASS tracks of the video being played, by the container's track number —
 * the number the server's stream list gives them as their index.
 *
 * A track is opened again whenever the player reads the file from the start
 * again, which setting the media item anew does; it is the same track, so it
 * keeps what was read before.
 */
class EmbeddedAssTracks {
    private val tracks = java.util.concurrent.ConcurrentHashMap<String, EmbeddedAss>()

    fun open(id: String, header: String): EmbeddedAss = tracks.computeIfAbsent(id) { EmbeddedAss(header) }

    operator fun get(id: String): EmbeddedAss? = tracks[id]
}
