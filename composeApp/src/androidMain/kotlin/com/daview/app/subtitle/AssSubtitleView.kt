package com.daview.app.subtitle

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View

/**
 * Draws ASS subtitles over the video.
 *
 * It sits on top of the player's own surface and paints itself from the
 * playback clock rather than from a stream of cues, because these scripts are
 * animated: the reference episode changes what is on screen every frame of its
 * opening, and a cue-driven view would only be told about it at event
 * boundaries.
 */
class AssSubtitleView(context: Context) : View(context) {

    private val renderer = AssRenderer(AssFonts(assFontDirectory(context)))
    private val video = RectF()

    /** Where the current playback position comes from. */
    var position: (() -> Long)? = null

    /**
     * Whether to keep asking for frames. Off while paused, when one repaint on
     * demand is enough and a 60 Hz redraw of a still picture is not.
     */
    var driving: Boolean = false
        set(value) {
            val changed = field != value
            field = value
            if (value && changed) postInvalidateOnAnimation()
        }

    /** The video's own size, so the subtitle lands on the picture and not the letterbox. */
    var videoWidth: Int = 0
    var videoHeight: Int = 0

    var script: AssScript?
        get() = renderer.script
        set(value) {
            renderer.script = value
            invalidate()
        }

    var delayMs: Long
        get() = renderer.delayMs
        set(value) {
            renderer.delayMs = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val time = position?.invoke()
        if (time != null && renderer.hasContent()) {
            fitVideo()
            renderer.draw(canvas, video, time)
        }
        if (driving) postInvalidateOnAnimation()
    }

    /**
     * The rectangle the picture occupies inside this view.
     *
     * media3's PlayerView fits the video inside its bounds and letterboxes the
     * rest, and script coordinates are relative to the picture. Without this the
     * subtitles of a 16:9 film on a 20:9 phone would sit in the black bars.
     */
    private fun fitVideo() {
        val width = width.toFloat()
        val height = height.toFloat()
        if (videoWidth <= 0 || videoHeight <= 0) {
            video.set(0f, 0f, width, height)
            return
        }
        val scale = minOf(width / videoWidth, height / videoHeight)
        val fittedWidth = videoWidth * scale
        val fittedHeight = videoHeight * scale
        val left = (width - fittedWidth) / 2f
        val top = (height - fittedHeight) / 2f
        video.set(left, top, left + fittedWidth, top + fittedHeight)
    }
}
