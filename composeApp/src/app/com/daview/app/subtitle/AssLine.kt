package com.daview.app.subtitle

import kotlin.math.pow

/** A point in script coordinates (`PlayResX` × `PlayResY`). */
data class AssPoint(val x: Float, val y: Float)

/** `\move(x1,y1,x2,y2[,t1,t2])`. */
data class AssMove(
    val from: AssPoint,
    val to: AssPoint,
    val startMs: Int,
    val endMs: Int
) {
    fun at(elapsedMs: Long, durationMs: Long): AssPoint {
        val begin = startMs.toFloat()
        val finish = if (endMs > startMs) endMs.toFloat() else durationMs.toFloat()
        val factor = when {
            finish <= begin -> 1f
            else -> ((elapsedMs - begin) / (finish - begin)).coerceIn(0f, 1f)
        }
        return AssPoint(
            from.x + (to.x - from.x) * factor,
            from.y + (to.y - from.y) * factor
        )
    }
}

/**
 * `\fad(in,out)` expanded to the general `\fade` form: three opacities and the
 * four times between them.
 */
data class AssFade(
    val alpha1: Int,
    val alpha2: Int,
    val alpha3: Int,
    val t1: Int,
    val t2: Int,
    val t3: Int,
    val t4: Int
) {
    /** The opacity multiplier in 0..255 at [elapsedMs] into the event. */
    fun opacityAt(elapsedMs: Long): Int {
        val t = elapsedMs.toInt()
        return when {
            t < t1 -> alpha1
            t < t2 -> interpolate(alpha1, alpha2, t - t1, t2 - t1)
            t < t3 -> alpha2
            t < t4 -> interpolate(alpha2, alpha3, t - t3, t4 - t3)
            else -> alpha3
        }
    }

    private fun interpolate(from: Int, to: Int, elapsed: Int, span: Int): Int =
        if (span <= 0) to else (from + (to - from) * elapsed.toFloat() / span).toInt()

    companion object {
        /** `\fad(in,out)` over an event of [durationMs]. */
        fun simple(fadeInMs: Int, fadeOutMs: Int, durationMs: Long): AssFade = AssFade(
            alpha1 = 0, alpha2 = 255, alpha3 = 0,
            t1 = 0,
            t2 = fadeInMs,
            t3 = (durationMs - fadeOutMs).toInt().coerceAtLeast(fadeInMs),
            t4 = durationMs.toInt().coerceAtLeast(fadeInMs)
        )
    }
}

/** `\clip` / `\iclip`, either a rectangle or a drawing. */
sealed interface AssClip {
    val inverse: Boolean

    data class Rect(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        override val inverse: Boolean
    ) : AssClip

    data class Drawing(
        val commands: String,
        val scale: Int,
        override val inverse: Boolean
    ) : AssClip
}

/** `\k`, `\kf`/`\K` and `\ko`. */
enum class AssKaraokeKind { FILL, SWEEP, OUTLINE }

data class AssKaraoke(
    val kind: AssKaraokeKind,
    /** Milliseconds into the event at which this syllable starts. */
    val startMs: Int,
    val durationMs: Int
)

/**
 * `\t(...)`: a set of tags faded in between two times.
 *
 * The tags arrive already split into name and argument. A script can carry
 * tens of thousands of transitions and the ones on screen are re-evaluated
 * every frame, so the string is taken apart once, when the line is parsed.
 */
data class AssTransition(
    val startMs: Int,
    val endMs: Int,
    val acceleration: Float,
    val tags: List<Pair<String, String>>
) {
    fun factorAt(elapsedMs: Long): Float {
        if (endMs <= startMs) return if (elapsedMs >= startMs) 1f else 0f
        val linear = ((elapsedMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
        return if (acceleration == 1f) linear else linear.pow(acceleration)
    }
}

/**
 * Everything a run of text carries. Sizes and distances are in script
 * coordinates; the renderer scales them to the video rectangle.
 */
data class AssRunStyle(
    val fontName: String,
    val fontSize: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikeOut: Boolean,
    val primaryColour: Int,
    val secondaryColour: Int,
    val outlineColour: Int,
    val backColour: Int,
    val scaleX: Float,
    val scaleY: Float,
    val spacing: Float,
    val angleX: Float,
    val angleY: Float,
    val angleZ: Float,
    val shearX: Float,
    val shearY: Float,
    val borderStyle: Int,
    val outlineX: Float,
    val outlineY: Float,
    val shadowX: Float,
    val shadowY: Float,
    val blur: Float,
    val blurEdges: Int,
    /** `\pN`: 0 for text, otherwise the drawing's coordinate divisor exponent. */
    val drawingScale: Int
) {
    companion object {
        fun of(style: AssStyle): AssRunStyle = AssRunStyle(
            fontName = style.fontName,
            fontSize = style.fontSize,
            bold = style.bold,
            italic = style.italic,
            underline = style.underline,
            strikeOut = style.strikeOut,
            primaryColour = style.primaryColour,
            secondaryColour = style.secondaryColour,
            outlineColour = style.outlineColour,
            backColour = style.backColour,
            scaleX = style.scaleX,
            scaleY = style.scaleY,
            spacing = style.spacing,
            angleX = 0f,
            angleY = 0f,
            angleZ = style.angle,
            shearX = 0f,
            shearY = 0f,
            borderStyle = style.borderStyle,
            outlineX = style.outline,
            outlineY = style.outline,
            shadowX = style.shadow,
            shadowY = style.shadow,
            blur = 0f,
            blurEdges = 0,
            drawingScale = 0
        )
    }
}

/** A stretch of text, or one drawing, under a single set of overrides. */
data class AssRun(
    val text: String,
    val style: AssRunStyle,
    val transitions: List<AssTransition>,
    val karaoke: AssKaraoke?,
    /** Whether a `\N` (or a `\n` under wrap style 2) precedes this run. */
    val breakBefore: Boolean
) {
    val isDrawing: Boolean get() = style.drawingScale > 0
}

/** One `Dialogue:` line with its overrides resolved into runs. */
data class AssParsedLine(
    val event: AssEvent,
    val style: AssStyle,
    val runs: List<AssRun>,
    val alignment: Int,
    val position: AssPoint?,
    val move: AssMove?,
    val origin: AssPoint?,
    val fade: AssFade?,
    val clip: AssClip?,
    val wrapStyle: Int,
    val marginL: Int,
    val marginR: Int,
    val marginV: Int
) {
    val durationMs: Long get() = event.endMs - event.startMs

    /** Where the line sits at [elapsedMs] into the event, if it is positioned at all. */
    fun positionAt(elapsedMs: Long): AssPoint? =
        move?.at(elapsedMs, durationMs) ?: position
}
