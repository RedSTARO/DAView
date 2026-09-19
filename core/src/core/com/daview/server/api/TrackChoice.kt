package com.daview.server.api

import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.SUBTITLE_OFF
import com.daview.shared.model.SUBTITLE_PREF_OFF
import com.daview.shared.model.StreamType
import com.daview.shared.model.languageGroup

/**
 * Which audio and subtitle tracks a file starts on.
 *
 * In order: what was chosen for this very item; what was chosen on the episode
 * watched before it in the same series, matched onto this file's tracks; the
 * viewer's standing language preference; and last the container's own default.
 * The choice used to stop at the first and the last of those, so a dual-audio
 * series went back to the dubbed track at the start of every episode.
 */
internal object TrackChoice {

    fun audio(item: MediaItemDto, previous: MediaItemDto?, preferred: String): Int? {
        val tracks = item.mediaStreams.filter { it.type == StreamType.AUDIO }
        if (tracks.isEmpty()) return item.userData.audioStreamIndex
        item.userData.audioStreamIndex?.takeIf { stored -> tracks.any { it.index == stored } }?.let { return it }

        previous?.let { prev ->
            val chosen = prev.userData.audioStreamIndex
                ?.let { index -> prev.mediaStreams.firstOrNull { it.type == StreamType.AUDIO && it.index == index } }
            if (chosen != null) {
                matching(tracks, chosen, prev.mediaStreams.filter { it.type == StreamType.AUDIO })
                    ?.let { return it.index }
            }
        }

        if (preferred.isNotBlank()) byLanguage(tracks, preferred)?.let { return it.index }
        return (tracks.firstOrNull { it.isDefault } ?: tracks.first()).index
    }

    fun subtitle(item: MediaItemDto, previous: MediaItemDto?, preferred: String): Int? {
        val tracks = item.mediaStreams.filter { it.type == StreamType.SUBTITLE }
        item.userData.subtitleStreamIndex?.let { stored ->
            if (stored == SUBTITLE_OFF || tracks.any { it.index == stored }) return stored
        }
        if (tracks.isEmpty()) return null

        previous?.let { prev ->
            val index = prev.userData.subtitleStreamIndex
            if (index == SUBTITLE_OFF) return SUBTITLE_OFF
            val chosen = index?.let { i ->
                prev.mediaStreams.firstOrNull { it.type == StreamType.SUBTITLE && it.index == i }
            }
            if (chosen != null) {
                matching(tracks, chosen, prev.mediaStreams.filter { it.type == StreamType.SUBTITLE })
                    ?.let { return it.index }
            }
        }

        when (preferred) {
            SUBTITLE_PREF_OFF -> return SUBTITLE_OFF
            "" -> Unit
            else -> byLanguage(tracks.filterNot { it.isForced }, preferred)
                ?.let { return it.index }
        }
        return pickDefaultSubtitle(item)
    }

    /**
     * The track on this file that plays the part [chosen] played on the other
     * one. Language decides first; being an external file, carrying the same
     * title and the same codec break ties. With no language to go on, the
     * track in the same position among its own kind is the best remaining
     * guess — two episodes of one release are usually muxed identically.
     */
    private fun matching(
        candidates: List<MediaStreamDto>,
        chosen: MediaStreamDto,
        chosenSiblings: List<MediaStreamDto>
    ): MediaStreamDto? {
        val group = languageGroup(chosen.language)
        if (group != null) {
            return candidates
                .filter { languageGroup(it.language) == group }
                .maxByOrNull { candidate ->
                    (if (candidate.isExternal == chosen.isExternal) 4 else 0) +
                        (if (!chosen.title.isNullOrBlank() && candidate.title == chosen.title) 2 else 0) +
                        (if (candidate.codec != null && candidate.codec == chosen.codec) 1 else 0)
                }
        }
        val position = chosenSiblings.filter { it.isExternal == chosen.isExternal }.indexOfFirst { it.index == chosen.index }
        return candidates.filter { it.isExternal == chosen.isExternal }.getOrNull(position)
    }

    /**
     * The first track in the preferred language. A preference for one Chinese
     * script accepts a track that only says "Chinese" before settling for the
     * other script, and a bilingual file that leads with it counts as a match.
     */
    private fun byLanguage(tracks: List<MediaStreamDto>, preferred: String): MediaStreamDto? {
        tracks.firstOrNull { languageGroup(it.language) == preferred }?.let { return it }
        if (preferred.startsWith("zh")) {
            tracks.firstOrNull { languageGroup(it.language) == "zh" }?.let { return it }
            tracks.firstOrNull { languageGroup(it.language)?.startsWith("zh") == true }?.let { return it }
        }
        return null
    }
}
