package com.daview.app.player

import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType

/**
 * Translates between DAView's stream indices and mpv's track ids.
 *
 * They do not agree and cannot be made to. A DAView index is the container's
 * own track number for an embedded track (Matroska numbers them however it
 * likes) and a synthetic number from 1000 up for an external subtitle file,
 * which is not in the container at all. mpv numbers tracks 1..n per type, in
 * the order it finds them: the container's order first, then external files in
 * the order they were added.
 *
 * So the mapping is positional, and it holds only because both sides walk the
 * container in the same order and this file also decides the order external
 * subtitles are attached in.
 */
object TrackMapping {

    fun embeddedAudio(streams: List<MediaStreamDto>): List<MediaStreamDto> =
        streams.filter { it.type == StreamType.AUDIO && !it.isExternal }

    fun embeddedSubtitles(streams: List<MediaStreamDto>): List<MediaStreamDto> =
        streams.filter { it.type == StreamType.SUBTITLE && !it.isExternal }

    /** External subtitles in the order they are handed to `sub-add`. */
    fun externalSubtitles(streams: List<MediaStreamDto>): List<MediaStreamDto> =
        streams.filter { it.type == StreamType.SUBTITLE && it.isExternal }.sortedBy { it.index }

    /** mpv `aid` for a DAView audio stream index, or null when there is no match. */
    fun audioId(streams: List<MediaStreamDto>, index: Int?): Int? {
        if (index == null) return null
        val position = embeddedAudio(streams).indexOfFirst { it.index == index }
        return if (position < 0) null else position + 1
    }

    /**
     * mpv `sid` for a DAView subtitle stream index. External files sit after
     * every embedded track, which is where `sub-add` puts them.
     */
    fun subtitleId(streams: List<MediaStreamDto>, index: Int?): Int? {
        if (index == null) return null
        val embedded = embeddedSubtitles(streams)
        val embeddedPosition = embedded.indexOfFirst { it.index == index }
        if (embeddedPosition >= 0) return embeddedPosition + 1
        val externalPosition = externalSubtitles(streams).indexOfFirst { it.index == index }
        return if (externalPosition < 0) null else embedded.size + externalPosition + 1
    }

    /**
     * The way back: a DAView index for the track mpv says it is playing.
     *
     * mpv has bindings and defaults of its own, so it is not always this app
     * that chose. Without reading the choice back, a track switched inside mpv
     * was never recorded, and the next device resumed on the one nobody picked.
     *
     * Null means the id maps to nothing this app knows about — an unprobed
     * container has no streams to match — and is a reason to leave the stored
     * choice alone rather than to overwrite it with nothing.
     */
    fun audioIndex(streams: List<MediaStreamDto>, mpvId: Int?): Int? {
        if (mpvId == null || mpvId < 1) return null
        return embeddedAudio(streams).getOrNull(mpvId - 1)?.index
    }

    fun subtitleIndex(streams: List<MediaStreamDto>, mpvId: Int?): Int? {
        if (mpvId == null || mpvId < 1) return null
        val embedded = embeddedSubtitles(streams)
        embedded.getOrNull(mpvId - 1)?.let { return it.index }
        return externalSubtitles(streams).getOrNull(mpvId - 1 - embedded.size)?.index
    }
}
