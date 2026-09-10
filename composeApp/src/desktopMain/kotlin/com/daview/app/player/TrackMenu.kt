package com.daview.app.player

import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType

/**
 * The track lists the desktop player shows, and the step from one entry to the
 * next.
 *
 * They are drawn on mpv's own OSD rather than by Compose. Nothing this app
 * paints can appear above mpv's native child window (see [MpvPlayer]), so a
 * Compose list had to take room of its own in the layout, and the picture moved
 * down every time one was opened. mpv's OSD is the one layer that is already
 * over the video, so the list goes there and the picture never moves.
 *
 * Which is also why there is no cursor to move and no open/closed state: an OSD
 * message is a message, not a widget. Pressing the key again simply selects the
 * next entry and redraws the list with the mark in its new place.
 */
object TrackMenu {

    /** One line of a list: a DAView stream index, or null for "subtitles off". */
    data class Entry(val index: Int?, val label: String)

    fun audio(streams: List<MediaStreamDto>): List<Entry> =
        streams.filter { it.type == StreamType.AUDIO }
            .map { Entry(it.index, it.displayTitle) }

    /**
     * Subtitles carry an extra entry for off, which is a choice someone makes
     * rather than the absence of one. It sits last so that stepping through
     * reaches every subtitle before turning them off.
     */
    fun subtitles(streams: List<MediaStreamDto>): List<Entry> =
        streams.filter { it.type == StreamType.SUBTITLE }
            .map { Entry(it.index, it.displayTitle) } + Entry(null, OFF)

    /**
     * The entry after [current], wrapping at the end.
     *
     * A [current] the list does not contain starts from the top — that is the
     * container this app could not probe, or a track mpv picked on its own,
     * and stepping from an entry that is not there has to go somewhere.
     */
    fun next(entries: List<Entry>, current: Int?): Entry? {
        if (entries.isEmpty()) return null
        // indexOfFirst returns -1 when there is no match, which lands on 0.
        val position = entries.indexOfFirst { it.index == current }
        return entries[(position + 1) % entries.size]
    }

    /**
     * The list as one OSD message, with [selected] marked.
     *
     * Long lists are trimmed around the mark rather than drawn whole: a season
     * pack with a dozen subtitle files would otherwise fill the screen top to
     * bottom every time the key was pressed.
     */
    fun osd(title: String, entries: List<Entry>, selected: Int?): String {
        val shown = window(entries, entries.indexOfFirst { it.index == selected })
        return buildString {
            append(title)
            if (shown.size < entries.size) append(" (${shown.size}/${entries.size})")
            shown.forEach { entry ->
                append('\n')
                append(if (entry.index == selected) MARK else PAD)
                append(entry.label)
            }
        }
    }

    /** At most [MAX_LINES] entries, centred on [position] where there is one. */
    private fun window(entries: List<Entry>, position: Int): List<Entry> {
        if (entries.size <= MAX_LINES) return entries
        val start = (if (position < 0) 0 else position - MAX_LINES / 2)
            .coerceIn(0, entries.size - MAX_LINES)
        return entries.subList(start, start + MAX_LINES)
    }

    const val OFF = "关闭字幕"
    private const val MARK = "▸ "
    private const val PAD = "   "
    private const val MAX_LINES = 8
}
