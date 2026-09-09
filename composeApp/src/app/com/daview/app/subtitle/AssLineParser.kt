package com.daview.app.subtitle

/**
 * Turns a `Dialogue:` line's text into runs with their overrides resolved.
 *
 * The tag set covered here is the one this library actually uses, measured
 * across a sample of forty scripts from forty different releases rather than
 * guessed: position and movement, alignment, fades, per-tag colours and
 * alphas, font name and size, scale, spacing, rotation on all three axes,
 * shear, border and shadow with their per-axis forms, blur, clipping, style
 * resets, animated transitions, karaoke, and vector drawings. Tags outside that
 * set are skipped rather than shown, which is also what libass does with a tag
 * it does not know.
 */
object AssLineParser {

    fun parse(event: AssEvent, script: AssScript): AssParsedLine {
        val style = script.styleFor(event.styleName)
        val base = AssRunStyle.of(style)

        var current = base
        var alignment = style.alignment
        var wrapStyle = script.wrapStyle
        var position: AssPoint? = null
        var move: AssMove? = null
        var origin: AssPoint? = null
        var fade: AssFade? = null
        var clip: AssClip? = null
        val transitions = ArrayList<AssTransition>()
        var karaokeCursorMs = 0
        var karaoke: AssKaraoke? = null

        val runs = ArrayList<AssRun>()
        val buffer = StringBuilder()
        var breakPending = false

        fun flush() {
            if (buffer.isEmpty()) return
            runs.add(
                AssRun(
                    text = buffer.toString(),
                    style = current,
                    transitions = transitions.toList(),
                    karaoke = karaoke,
                    breakBefore = breakPending
                )
            )
            buffer.setLength(0)
            breakPending = false
        }

        val text = event.text
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '{' -> {
                    val close = text.indexOf('}', index + 1)
                    if (close < 0) {
                        // An unbalanced brace is literal text, not the start of
                        // an override that swallows the rest of the line.
                        buffer.append(ch)
                        index++
                    } else {
                        flush()
                        for ((name, argument) in splitTags(text.substring(index + 1, close))) {
                            when (name) {
                                "pos" -> parsePoint(argument)?.let { position = it }
                                "move" -> parseMove(argument)?.let { move = it }
                                "org" -> parsePoint(argument)?.let { origin = it }
                                "fad" -> parseFad(argument, event.endMs - event.startMs)
                                    ?.let { fade = it }

                                "fade" -> parseFade(argument)?.let { fade = it }
                                "clip" -> parseClip(argument, inverse = false)?.let { clip = it }
                                "iclip" -> parseClip(argument, inverse = true)?.let { clip = it }
                                "an" -> argument.trim().toIntOrNull()
                                    ?.takeIf { it in 1..9 }?.let { alignment = it }

                                "a" -> argument.trim().toIntOrNull()
                                    ?.let { legacyAlignment(it) }
                                    ?.takeIf { it in 1..9 }?.let { alignment = it }

                                "q" -> argument.trim().toIntOrNull()
                                    ?.takeIf { it in 0..3 }?.let { wrapStyle = it }

                                "t" -> parseTransition(argument, event.endMs - event.startMs)
                                    ?.let { transitions.add(it) }

                                "k", "kf", "K", "ko" -> {
                                    val centiseconds = argument.trim().toFloatOrNull() ?: 0f
                                    val duration = (centiseconds * 10).toInt()
                                    karaoke = AssKaraoke(
                                        kind = when (name) {
                                            "k" -> AssKaraokeKind.FILL
                                            "ko" -> AssKaraokeKind.OUTLINE
                                            else -> AssKaraokeKind.SWEEP
                                        },
                                        startMs = karaokeCursorMs,
                                        durationMs = duration
                                    )
                                    karaokeCursorMs += duration
                                }

                                "r" -> {
                                    val target = argument.trim()
                                    current = AssRunStyle.of(
                                        if (target.isEmpty()) style else script.styleFor(target)
                                    )
                                    alignment = (if (target.isEmpty()) style
                                    else script.styleFor(target)).alignment
                                }

                                else -> current = applyTag(current, name, argument, 1f, style)
                            }
                        }
                        index = close + 1
                    }
                }

                ch == '\\' && index + 1 < text.length -> {
                    when (text[index + 1]) {
                        'N' -> {
                            flush()
                            breakPending = true
                        }
                        // A `\n` is a space unless the script asked for no word
                        // wrapping at all, where it is the only break there is.
                        'n' -> if (wrapStyle == 2) {
                            flush()
                            breakPending = true
                        } else {
                            buffer.append(' ')
                        }

                        'h' -> buffer.append(' ')
                        else -> {
                            buffer.append(ch)
                            index--   // the escaped character is ordinary text
                        }
                    }
                    index += 2
                }

                else -> {
                    buffer.append(ch)
                    index++
                }
            }
        }
        flush()
        // A line whose text was nothing but a break still has to occupy its
        // slot, or the ones stacked above it move.
        if (runs.isEmpty() && breakPending) {
            runs.add(AssRun("", current, transitions.toList(), karaoke, false))
        }

        return AssParsedLine(
            event = event,
            style = style,
            runs = runs,
            alignment = alignment,
            position = position,
            move = move,
            origin = origin,
            fade = fade,
            clip = clip,
            wrapStyle = wrapStyle,
            // A zero margin on the event means "use the style's".
            marginL = if (event.marginL != 0) event.marginL else style.marginL,
            marginR = if (event.marginR != 0) event.marginR else style.marginR,
            marginV = if (event.marginV != 0) event.marginV else style.marginV
        )
    }

    /**
     * The run's style at [elapsedMs] into the event, with every `\t` that is
     * under way applied.
     */
    fun styleAt(run: AssRun, elapsedMs: Long, eventStyle: AssStyle): AssRunStyle {
        if (run.transitions.isEmpty()) return run.style
        var style = run.style
        for (transition in run.transitions) {
            val factor = transition.factorAt(elapsedMs)
            if (factor <= 0f) continue
            for ((name, argument) in transition.tags) {
                style = applyTag(style, name, argument, factor, eventStyle)
            }
        }
        return style
    }

    /**
     * Splits an override block into its tags.
     *
     * A tag runs from its backslash to the next one that is not inside
     * parentheses, which is what keeps `\t(0,500,\fscx120\fscy120)` in one
     * piece.
     */
    fun splitTags(block: String): List<Pair<String, String>> {
        val tags = ArrayList<Pair<String, String>>()
        var index = 0
        while (index < block.length) {
            if (block[index] != '\\') {
                index++
                continue
            }
            var end = index + 1
            var depth = 0
            while (end < block.length) {
                val ch = block[end]
                if (ch == '(') depth++
                else if (ch == ')') depth--
                else if (ch == '\\' && depth <= 0) break
                end++
            }
            val chunk = block.substring(index + 1, end)
            val name = nameOf(chunk)
            if (name != null) {
                val argument = chunk.substring(name.length).trim()
                // Only `\t`, `\pos` and their kin take parentheses; stripping
                // them here saves every reader from doing it.
                tags.add(name to argument.removeSurrounding("(", ")").trim())
            }
            index = end
        }
        return tags
    }

    /**
     * Tag names, longest first: `\be` must not be read as `\b` with an argument
     * of "e", and `\fscx` must not be read as `\fs`.
     */
    private val TAG_NAMES = listOf(
        "iclip", "clip", "alpha", "xbord", "ybord", "xshad", "yshad",
        "fscx", "fscy", "fsp", "fade", "fad", "frx", "fry", "frz", "fax", "fay",
        "bord", "blur", "shad", "move", "org", "pos", "pbo",
        "1c", "2c", "3c", "4c", "1a", "2a", "3a", "4a",
        "be", "kf", "ko", "fn", "fs", "fr", "an",
        "b", "i", "u", "s", "p", "c", "a", "k", "K", "q", "r", "t"
    )

    private fun nameOf(chunk: String): String? =
        TAG_NAMES.firstOrNull { chunk.startsWith(it) }

    private fun applyTag(
        style: AssRunStyle,
        name: String,
        argument: String,
        factor: Float,
        eventStyle: AssStyle
    ): AssRunStyle {
        val animating = factor < 1f
        fun number(fallback: Float): Float =
            argument.toFloatOrNull() ?: fallback

        fun lerp(from: Float, to: Float): Float = from + (to - from) * factor

        return when (name) {
            "fs" -> style.copy(
                fontSize = lerp(style.fontSize, number(eventStyle.fontSize))
            )

            "fsp" -> style.copy(spacing = lerp(style.spacing, number(eventStyle.spacing)))
            "fscx" -> style.copy(scaleX = lerp(style.scaleX, number(eventStyle.scaleX)))
            "fscy" -> style.copy(scaleY = lerp(style.scaleY, number(eventStyle.scaleY)))
            "frx" -> style.copy(angleX = lerp(style.angleX, number(0f)))
            "fry" -> style.copy(angleY = lerp(style.angleY, number(0f)))
            "frz", "fr" -> style.copy(angleZ = lerp(style.angleZ, number(eventStyle.angle)))
            "fax" -> style.copy(shearX = lerp(style.shearX, number(0f)))
            "fay" -> style.copy(shearY = lerp(style.shearY, number(0f)))
            "bord" -> number(eventStyle.outline).let {
                style.copy(
                    outlineX = lerp(style.outlineX, it),
                    outlineY = lerp(style.outlineY, it)
                )
            }

            "xbord" -> style.copy(outlineX = lerp(style.outlineX, number(eventStyle.outline)))
            "ybord" -> style.copy(outlineY = lerp(style.outlineY, number(eventStyle.outline)))
            "shad" -> number(eventStyle.shadow).let {
                style.copy(
                    shadowX = lerp(style.shadowX, it),
                    shadowY = lerp(style.shadowY, it)
                )
            }

            "xshad" -> style.copy(shadowX = lerp(style.shadowX, number(eventStyle.shadow)))
            "yshad" -> style.copy(shadowY = lerp(style.shadowY, number(eventStyle.shadow)))
            "blur" -> style.copy(blur = lerp(style.blur, number(0f)))
            "be" -> style.copy(blurEdges = lerp(style.blurEdges.toFloat(), number(0f)).toInt())

            "c", "1c" -> style.copy(
                primaryColour = lerpColour(
                    style.primaryColour,
                    parseColour(argument) ?: eventStyle.primaryColour,
                    factor
                )
            )

            "2c" -> style.copy(
                secondaryColour = lerpColour(
                    style.secondaryColour,
                    parseColour(argument) ?: eventStyle.secondaryColour,
                    factor
                )
            )

            "3c" -> style.copy(
                outlineColour = lerpColour(
                    style.outlineColour,
                    parseColour(argument) ?: eventStyle.outlineColour,
                    factor
                )
            )

            "4c" -> style.copy(
                backColour = lerpColour(
                    style.backColour,
                    parseColour(argument) ?: eventStyle.backColour,
                    factor
                )
            )

            // `\alpha` sets every layer's opacity at once.
            "alpha" -> (parseAlpha(argument) ?: 255).let { opacity ->
                style.copy(
                    primaryColour = lerpAlpha(style.primaryColour, opacity, factor),
                    secondaryColour = lerpAlpha(style.secondaryColour, opacity, factor),
                    outlineColour = lerpAlpha(style.outlineColour, opacity, factor),
                    backColour = lerpAlpha(style.backColour, opacity, factor)
                )
            }

            "1a" -> style.copy(
                primaryColour = lerpAlpha(
                    style.primaryColour,
                    parseAlpha(argument) ?: alphaOf(eventStyle.primaryColour), factor
                )
            )

            "2a" -> style.copy(
                secondaryColour = lerpAlpha(
                    style.secondaryColour,
                    parseAlpha(argument) ?: alphaOf(eventStyle.secondaryColour), factor
                )
            )

            "3a" -> style.copy(
                outlineColour = lerpAlpha(
                    style.outlineColour,
                    parseAlpha(argument) ?: alphaOf(eventStyle.outlineColour), factor
                )
            )

            "4a" -> style.copy(
                backColour = lerpAlpha(
                    style.backColour,
                    parseAlpha(argument) ?: alphaOf(eventStyle.backColour), factor
                )
            )

            // Everything below cannot be interpolated, so inside a `\t` it does
            // nothing at all rather than snapping halfway through.
            "fn" -> if (animating) style else style.copy(
                fontName = argument.removePrefix("@").ifEmpty { eventStyle.fontName }
            )

            "b" -> if (animating) style else style.copy(
                // `\b1` is bold and `\b0` is not; a weight such as `\b700` is
                // a request for a specific face, which reads as bold here.
                bold = argument.trim().toIntOrNull()?.let { it == 1 || it >= 400 }
                    ?: eventStyle.bold
            )

            "i" -> if (animating) style else style.copy(
                italic = argument.trim().toIntOrNull()?.let { it != 0 } ?: eventStyle.italic
            )

            "u" -> if (animating) style else style.copy(
                underline = argument.trim().toIntOrNull()?.let { it != 0 } ?: eventStyle.underline
            )

            "s" -> if (animating) style else style.copy(
                strikeOut = argument.trim().toIntOrNull()?.let { it != 0 } ?: eventStyle.strikeOut
            )

            "p" -> if (animating) style else style.copy(
                drawingScale = argument.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            )

            else -> style
        }
    }

    private fun alphaOf(colour: Int): Int = (colour ushr 24) and 0xFF

    private fun lerpAlpha(colour: Int, opacity: Int, factor: Float): Int {
        val from = alphaOf(colour)
        val blended = (from + (opacity - from) * factor).toInt().coerceIn(0, 255)
        return (blended shl 24) or (colour and 0x00FFFFFF)
    }

    private fun lerpColour(from: Int, to: Int, factor: Float): Int {
        if (factor >= 1f) {
            // The alpha of a `\c` is not the alpha of the run: only `\1a` and
            // friends move that, so the existing one is kept.
            return (from and 0xFF000000.toInt()) or (to and 0x00FFFFFF)
        }
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * factor).toInt().coerceIn(0, 255) shl shift
        }
        return (from and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0)
    }

    private fun legacyAlignment(value: Int): Int {
        val horizontal = value and 0x3
        return when {
            value and 0x8 != 0 -> 3 + horizontal
            value and 0x4 != 0 -> 6 + horizontal
            else -> horizontal
        }
    }

    private fun parsePoint(argument: String): AssPoint? {
        val parts = argument.split(',')
        if (parts.size < 2) return null
        val x = parts[0].trim().toFloatOrNull() ?: return null
        val y = parts[1].trim().toFloatOrNull() ?: return null
        return AssPoint(x, y)
    }

    private fun parseMove(argument: String): AssMove? {
        val parts = argument.split(',').map { it.trim() }
        if (parts.size < 4) return null
        val values = parts.map { it.toFloatOrNull() }
        if (values.take(4).any { it == null }) return null
        return AssMove(
            from = AssPoint(values[0]!!, values[1]!!),
            to = AssPoint(values[2]!!, values[3]!!),
            startMs = values.getOrNull(4)?.toInt() ?: 0,
            endMs = values.getOrNull(5)?.toInt() ?: 0
        )
    }

    private fun parseFad(argument: String, durationMs: Long): AssFade? {
        val parts = argument.split(',')
        if (parts.size < 2) return null
        val fadeIn = parts[0].trim().toFloatOrNull()?.toInt() ?: return null
        val fadeOut = parts[1].trim().toFloatOrNull()?.toInt() ?: return null
        return AssFade.simple(fadeIn, fadeOut, durationMs)
    }

    private fun parseFade(argument: String): AssFade? {
        val parts = argument.split(',').map { it.trim().toFloatOrNull()?.toInt() }
        if (parts.size < 7 || parts.any { it == null }) return null
        // `\fade` gives transparency where everything downstream wants opacity.
        return AssFade(
            alpha1 = 255 - parts[0]!!.coerceIn(0, 255),
            alpha2 = 255 - parts[1]!!.coerceIn(0, 255),
            alpha3 = 255 - parts[2]!!.coerceIn(0, 255),
            t1 = parts[3]!!, t2 = parts[4]!!, t3 = parts[5]!!, t4 = parts[6]!!
        )
    }

    private fun parseClip(argument: String, inverse: Boolean): AssClip? {
        val parts = splitTopLevel(argument)
        return when {
            parts.size >= 4 && parts.all { it.trim().toFloatOrNull() != null } -> {
                val values = parts.map { it.trim().toFloat() }
                AssClip.Rect(
                    minOf(values[0], values[2]), minOf(values[1], values[3]),
                    maxOf(values[0], values[2]), maxOf(values[1], values[3]),
                    inverse
                )
            }

            parts.size == 2 -> AssClip.Drawing(
                commands = parts[1].trim(),
                scale = parts[0].trim().toIntOrNull()?.coerceAtLeast(1) ?: 1,
                inverse = inverse
            )

            parts.size == 1 && parts[0].isNotBlank() ->
                AssClip.Drawing(parts[0].trim(), 1, inverse)

            else -> null
        }
    }

    private fun parseTransition(argument: String, durationMs: Long): AssTransition? {
        // The tags start at the first backslash; everything before it is the
        // optional timing, in one of three shapes.
        val split = argument.indexOf('\\')
        if (split < 0) return null
        val head = argument.substring(0, split).split(',')
            .mapNotNull { it.trim().takeIf { part -> part.isNotEmpty() }?.toFloatOrNull() }
        val tags = splitTags(argument.substring(split))
        if (tags.isEmpty()) return null
        return when (head.size) {
            0 -> AssTransition(0, durationMs.toInt(), 1f, tags)
            1 -> AssTransition(0, durationMs.toInt(), head[0], tags)
            2 -> AssTransition(head[0].toInt(), head[1].toInt(), 1f, tags)
            else -> AssTransition(head[0].toInt(), head[1].toInt(), head[2], tags)
        }
    }

    /** Splits on commas that are not inside parentheses. */
    private fun splitTopLevel(argument: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for (i in argument.indices) {
            when (argument[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(argument.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(argument.substring(start))
        return parts
    }
}
