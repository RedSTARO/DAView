package com.daview.app.subtitle

/**
 * A parsed Advanced SubStation Alpha script.
 *
 * This package holds the part of ASS support that is pure Kotlin — parsing,
 * override tags, timing and layout arithmetic. Only the drawing itself is
 * platform code, which is what keeps this testable: the shared `src/app`
 * directory is compiled into both JVM targets, so `:composeApp:desktopTest`
 * covers it and CI already runs that.
 *
 * Why any of this exists: media3 renders ASS through `SsaParser`, which keeps
 * the alignment, position and a handful of style fields and throws every other
 * override away. For a plain dialogue script that is a fair approximation. For
 * the scripts in this library it is not — a quarter of the playable items carry
 * an external `.ass`, and the typeset ones spend most of their events on vector
 * drawings (`\p1`). Those drawing commands live *outside* the `{...}` braces
 * that `SsaParser` strips, so they survive as literal text and the viewer gets
 * `m 12 0 b 6 4 5 5 0 12` across the picture, dozens at a time.
 */
class AssScript(
    val playResX: Int,
    val playResY: Int,
    val wrapStyle: Int,
    val scaledBorderAndShadow: Boolean,
    val styles: List<AssStyle>,
    /** Sorted by start time. */
    val events: List<AssEvent>
) {
    private val longestEventMs: Long = events.maxOfOrNull { it.endMs - it.startMs } ?: 0L

    /**
     * The style of that name, or [AssStyle.DEFAULT].
     *
     * Searched from the end, which is not an arbitrary choice: scripts that
     * were merged from two releases — common here, one script for the dialogue
     * and one for the songs — declare the same style name twice, and libass
     * resolves such a name to the *last* definition. Taking the first instead
     * renders the dialogue of this library's merged scripts in the song styles'
     * font and size.
     */
    fun styleFor(name: String): AssStyle =
        styles.lastOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: styles.lastOrNull { it.name.equals("Default", ignoreCase = true) }
            ?: AssStyle.DEFAULT

    /**
     * Events on screen at [timeMs], in the order they should be drawn: by layer,
     * then by their order in the file, which is how ASS defines what covers what.
     */
    fun eventsAt(timeMs: Long): List<AssEvent> {
        if (events.isEmpty()) return emptyList()
        // Everything that can still be showing started no earlier than the
        // longest event before now, so the scan is bounded by that rather than
        // by the size of the script.
        var high = events.binarySearchLastAtOrBefore(timeMs)
        if (high < 0) return emptyList()
        val floor = timeMs - longestEventMs
        val hits = ArrayList<AssEvent>()
        while (high >= 0 && events[high].startMs >= floor) {
            val event = events[high]
            if (timeMs >= event.startMs && timeMs < event.endMs) hits.add(event)
            high--
        }
        hits.reverse()
        hits.sortBy { it.layer }
        return hits
    }

    companion object {
        /** What a script declares no resolution for. Matches libass. */
        const val DEFAULT_PLAY_RES_X = 384
        const val DEFAULT_PLAY_RES_Y = 288

        fun parse(text: String): AssScript {
            var playResX = 0
            var playResY = 0
            var wrapStyle = 0
            var scaledBorderAndShadow = true
            val styles = ArrayList<AssStyle>()
            val events = ArrayList<AssEvent>()

            var section = ""
            var styleFormat: List<String>? = null
            var eventFormat: List<String>? = null
            // SSA v4 and ASS v4+ share a section name shape but not a colour
            // model: v4's third colour is the shadow, not the outline.
            var legacyStyles = false

            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim().removePrefix("﻿")
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("!:")) continue
                if (line.startsWith("[")) {
                    section = line.trim('[', ']').lowercase()
                    legacyStyles = section == "v4 styles"
                    continue
                }
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()

                when {
                    section == "script info" -> when (key) {
                        "playresx" -> playResX = value.toIntOrNull() ?: 0
                        "playresy" -> playResY = value.toIntOrNull() ?: 0
                        "wrapstyle" -> wrapStyle = value.toIntOrNull() ?: 0
                        "scaledborderandshadow" ->
                            scaledBorderAndShadow = !value.startsWith("n", ignoreCase = true)
                    }

                    section.endsWith("styles") -> when (key) {
                        "format" -> styleFormat = value.split(',').map { it.trim().lowercase() }
                        "style" -> AssStyle.parse(value, styleFormat, legacyStyles)
                            ?.let { styles.add(it) }
                    }

                    section == "events" -> when (key) {
                        "format" -> eventFormat = value.split(',').map { it.trim().lowercase() }
                        // `Comment:` lines are the script's own scratch space and
                        // are never shown; `Picture:`/`Sound:`/`Movie:` are SSA
                        // commands nothing here implements.
                        "dialogue" -> AssEvent.parse(value, eventFormat)?.let { events.add(it) }
                    }
                }
            }

            events.sortBy { it.startMs }
            return AssScript(
                playResX = if (playResX > 0) playResX else DEFAULT_PLAY_RES_X,
                playResY = if (playResY > 0) playResY else DEFAULT_PLAY_RES_Y,
                wrapStyle = wrapStyle,
                scaledBorderAndShadow = scaledBorderAndShadow,
                styles = styles,
                events = events
            )
        }
    }
}

/** Index of the last event starting at or before [timeMs], or -1. */
private fun List<AssEvent>.binarySearchLastAtOrBefore(timeMs: Long): Int {
    var low = 0
    var high = size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].startMs <= timeMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

/** One `[V4+ Styles]` entry. Colours are ARGB; ASS stores them as ABGR inverted. */
data class AssStyle(
    val name: String,
    val fontName: String,
    val fontSize: Float,
    val primaryColour: Int,
    val secondaryColour: Int,
    val outlineColour: Int,
    val backColour: Int,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikeOut: Boolean,
    val scaleX: Float,
    val scaleY: Float,
    val spacing: Float,
    val angle: Float,
    val borderStyle: Int,
    val outline: Float,
    val shadow: Float,
    val alignment: Int,
    val marginL: Int,
    val marginR: Int,
    val marginV: Int
) {
    companion object {
        val DEFAULT = AssStyle(
            name = "Default",
            fontName = "Arial",
            fontSize = 18f,
            primaryColour = 0xFFFFFFFF.toInt(),
            secondaryColour = 0xFFFF0000.toInt(),
            outlineColour = 0xFF000000.toInt(),
            backColour = 0xFF000000.toInt(),
            bold = false,
            italic = false,
            underline = false,
            strikeOut = false,
            scaleX = 100f,
            scaleY = 100f,
            spacing = 0f,
            angle = 0f,
            borderStyle = 1,
            outline = 2f,
            shadow = 2f,
            alignment = 2,
            marginL = 10,
            marginR = 10,
            marginV = 10
        )

        private val V4PLUS = listOf(
            "name", "fontname", "fontsize", "primarycolour", "secondarycolour",
            "outlinecolour", "backcolour", "bold", "italic", "underline", "strikeout",
            "scalex", "scaley", "spacing", "angle", "borderstyle", "outline", "shadow",
            "alignment", "marginl", "marginr", "marginv", "encoding"
        )

        private val V4 = listOf(
            "name", "fontname", "fontsize", "primarycolour", "secondarycolour",
            "tertiarycolour", "backcolour", "bold", "italic", "borderstyle", "outline",
            "shadow", "alignment", "marginl", "marginr", "marginv", "alphalevel", "encoding"
        )

        fun parse(line: String, format: List<String>?, legacy: Boolean): AssStyle? {
            val names = format ?: if (legacy) V4 else V4PLUS
            val fields = line.split(',')
            if (fields.isEmpty()) return null
            fun field(key: String): String? =
                names.indexOf(key).takeIf { it >= 0 }?.let { fields.getOrNull(it)?.trim() }

            val name = field("name")?.takeIf { it.isNotEmpty() } ?: return null
            val d = DEFAULT
            // SSA v4 counts alignment as 1/2/3 for left/centre/right with +4 for
            // the top of the frame and +8 for the middle; ASS uses the numeric
            // keypad. Everything downstream assumes the keypad.
            val alignment = field("alignment")?.toIntOrNull()?.let {
                if (legacy) legacyAlignment(it) else it
            }?.takeIf { it in 1..9 } ?: d.alignment

            return AssStyle(
                name = name,
                fontName = field("fontname")?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }
                    ?: d.fontName,
                fontSize = field("fontsize")?.toFloatOrNull() ?: d.fontSize,
                primaryColour = parseColour(field("primarycolour")) ?: d.primaryColour,
                secondaryColour = parseColour(field("secondarycolour")) ?: d.secondaryColour,
                // v4's "TertiaryColour" is the outline in the same slot.
                outlineColour = parseColour(field(if (legacy) "tertiarycolour" else "outlinecolour"))
                    ?: d.outlineColour,
                backColour = parseColour(field("backcolour")) ?: d.backColour,
                bold = field("bold").toAssBoolean(),
                italic = field("italic").toAssBoolean(),
                underline = field("underline").toAssBoolean(),
                strikeOut = field("strikeout").toAssBoolean(),
                scaleX = field("scalex")?.toFloatOrNull() ?: d.scaleX,
                scaleY = field("scaley")?.toFloatOrNull() ?: d.scaleY,
                spacing = field("spacing")?.toFloatOrNull() ?: d.spacing,
                angle = field("angle")?.toFloatOrNull() ?: d.angle,
                borderStyle = field("borderstyle")?.toIntOrNull() ?: d.borderStyle,
                outline = field("outline")?.toFloatOrNull() ?: d.outline,
                shadow = field("shadow")?.toFloatOrNull() ?: d.shadow,
                alignment = alignment,
                marginL = field("marginl")?.toFloatOrNull()?.toInt() ?: d.marginL,
                marginR = field("marginr")?.toFloatOrNull()?.toInt() ?: d.marginR,
                marginV = field("marginv")?.toFloatOrNull()?.toInt() ?: d.marginV
            )
        }

        private fun legacyAlignment(value: Int): Int {
            val horizontal = value and 0x3
            return when {
                value and 0x8 != 0 -> 3 + horizontal   // middle row: 4,5,6
                value and 0x4 != 0 -> 6 + horizontal   // top row: 7,8,9
                else -> horizontal                      // bottom row: 1,2,3
            }
        }
    }
}

private fun String?.toAssBoolean(): Boolean {
    val value = this?.trim() ?: return false
    // -1 is true in ASS; some scripts write 1. Anything else, including "0", is
    // false — and a bare "-1" must not be read as a truthy non-zero number.
    return value == "-1" || value == "1"
}

/** One `Dialogue:` line, its text still carrying the override tags. */
data class AssEvent(
    val layer: Int,
    val startMs: Long,
    val endMs: Long,
    val styleName: String,
    val marginL: Int,
    val marginR: Int,
    val marginV: Int,
    val effect: String,
    val text: String
) {
    companion object {
        private val DEFAULT_FORMAT = listOf(
            "layer", "start", "end", "style", "name",
            "marginl", "marginr", "marginv", "effect", "text"
        )

        fun parse(line: String, format: List<String>?): AssEvent? {
            val names = format ?: DEFAULT_FORMAT
            val textIndex = names.indexOf("text").takeIf { it >= 0 } ?: (names.size - 1)
            // The text is the last field and may hold any number of commas, so
            // the split stops there rather than counting them.
            val fields = line.split(',', limit = names.size)
            if (fields.size <= textIndex) return null
            fun field(key: String): String? =
                names.indexOf(key).takeIf { it >= 0 }?.let { fields.getOrNull(it)?.trim() }

            val start = parseTime(field("start")) ?: return null
            val end = parseTime(field("end")) ?: return null
            return AssEvent(
                // "Marked=0" in SSA v4 sits where the layer does in v4+.
                layer = field("layer")?.substringAfter('=')?.trim()?.toIntOrNull() ?: 0,
                startMs = start,
                endMs = end,
                styleName = field("style") ?: "Default",
                marginL = field("marginl")?.toIntOrNull() ?: 0,
                marginR = field("marginr")?.toIntOrNull() ?: 0,
                marginV = field("marginv")?.toIntOrNull() ?: 0,
                effect = field("effect").orEmpty(),
                text = fields[textIndex]
            )
        }
    }
}

/** `H:MM:SS.cc`, and the `H:MM:SS:cc` some tools write, in milliseconds. */
fun parseTime(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = text.split(':', '.')
    if (parts.size < 3) return null
    val hours: Long
    val minutes: Long
    val seconds: Long
    val hundredths: Long
    if (parts.size >= 4) {
        hours = parts[0].toLongOrNull() ?: return null
        minutes = parts[1].toLongOrNull() ?: return null
        seconds = parts[2].toLongOrNull() ?: return null
        // "5" after the separator means 50 hundredths, not 5.
        hundredths = parts[3].padEnd(2, '0').take(2).toLongOrNull() ?: return null
    } else {
        hours = 0
        minutes = parts[0].toLongOrNull() ?: return null
        seconds = parts[1].toLongOrNull() ?: return null
        hundredths = parts[2].padEnd(2, '0').take(2).toLongOrNull() ?: return null
    }
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + hundredths * 10
}

/**
 * `&HAABBGGRR`, `&HBBGGRR&` or a decimal, as ARGB.
 *
 * ASS stores the channels backwards and treats the alpha byte as transparency,
 * so `&H00FFFFFF` is opaque white.
 */
fun parseColour(value: String?): Int? {
    var text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text = text.removePrefix("&").removePrefix("H").removePrefix("h").removeSuffix("&")
    val number = text.toLongOrNull(16) ?: text.toLongOrNull() ?: return null
    val blue = ((number ushr 16) and 0xFF).toInt()
    val green = ((number ushr 8) and 0xFF).toInt()
    val red = (number and 0xFF).toInt()
    val transparency = ((number ushr 24) and 0xFF).toInt()
    return ((255 - transparency) shl 24) or (red shl 16) or (green shl 8) or blue
}

/** `&HAA&` as an opacity in 0..255. */
fun parseAlpha(value: String?): Int? {
    var text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text = text.removePrefix("&").removePrefix("H").removePrefix("h").removeSuffix("&")
    val number = text.toLongOrNull(16) ?: return null
    return 255 - (number and 0xFF).toInt()
}
