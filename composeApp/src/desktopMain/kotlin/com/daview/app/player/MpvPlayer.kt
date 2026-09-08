package com.daview.app.player

import com.sun.jna.Pointer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One playback session, backed by libmpv rendering into a native window.
 *
 * The window is mpv's own: it is given the handle of a heavyweight AWT
 * component and parents a child window to it. That is not a shortcut around
 * Compose — it is the only arrangement in which the NVIDIA features work at
 * all. Both of them live in the D3D11 video processor and end at a D3D11
 * swapchain, and HDR in particular has to be *presented* in a PQ/BT.2020
 * colour space. Routing frames through mpv's render API into Skia would give
 * Compose the pixels and lose the swapchain, and with it the HDR output and the
 * hardware path the RTX filter needs.
 *
 * Which is also why the transport controls are mpv's own on-screen controller
 * rather than Compose widgets: nothing this app draws can appear above that
 * child window.
 */
class MpvPlayer(private val listener: Listener) : AutoCloseable {

    interface Listener {
        /** Every log line mpv emits at verbose level or above. */
        fun onLog(prefix: String, level: String, text: String) {}
        fun onFileLoaded() {}
        /**
         * Playback stopped.
         *
         * [error] is mpv's message when it stopped because it could not go on.
         * [reachedEnd] separates the file actually running out from the viewer
         * quitting mpv, which look identical from here and mean opposite things
         * to the progress that gets written: only one of them is "watched".
         */
        fun onEndFile(error: String?, reachedEnd: Boolean) {}
        fun onShutdown() {}
    }

    private val mpv: MpvLibrary = MpvNative.library() ?: error("libmpv 未加载")
    private var ctx: Pointer? = null
    private val closed = AtomicBoolean(false)
    private val destroyPending = AtomicBoolean(false)

    @Volatile
    private var pump: Thread? = null

    /**
     * Creates the instance and hands it the window to draw into.
     *
     * [surfaceHandle] is the native handle of a *heavyweight* component: an
     * `HWND` on Windows, an `NSView*` on macOS, an X11 window id on Linux.
     */
    fun open(surfaceHandle: Long, config: Config) {
        val handle = mpv.mpv_create() ?: error("mpv_create 失败")
        ctx = handle
        // Past this point there is a native context and, shortly, a thread
        // waiting on it. Neither is reachable from the caller, which only ever
        // sees the exception — so a failure has to clean up after itself, or
        // every failed attempt strands an mpv instance and a live thread.
        // Initialising is where this really happens: forcing the NVIDIA adapter
        // on a machine that has none fails right here.
        try {
            configure(surfaceHandle, config)
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private fun configure(surfaceHandle: Long, config: Config) {
        val handle = ctx ?: return

        // Before the options, not after: an option mpv rejects is reported by
        // mpv itself, and asking for its log afterwards discards exactly the
        // lines that say what went wrong.
        mpv.mpv_request_log_messages(handle, "v")
        startPump(handle)

        // Options that must be in place before initialize; everything else is
        // set as a property afterwards, which is the same thing to mpv but
        // survives being changed while playing.
        option("wid", surfaceHandle.toString())
        option("vo", "gpu-next")
        if (MpvNative.isWindows) {
            // d3d11vpp only attaches to a VO that publishes a D3D11 hwdec
            // device, and it is where both NVIDIA features live.
            option("gpu-api", "d3d11")
            option("hwdec", "d3d11va")
        } else {
            option("hwdec", "auto-safe")
        }
        // The libmpv profile turns all three off — it assumes the embedder
        // draws its own UI. Here mpv draws it, so they go back on.
        option("osc", "yes")
        option("input-default-bindings", "yes")
        option("input-vo-keyboard", "yes")
        option("keep-open", "no")
        // The app resolves and attaches subtitles itself; letting mpv guess
        // siblings of an http URL would only produce failed requests.
        option("sub-auto", "no")
        // The url is this app's own byte pipe. mpv's youtube-dl hook inspects
        // every http address it is given and would spawn yt-dlp against a
        // loopback port for nothing.
        option("ytdl", "no")
        option("audio-client-name", "DAView")
        option("network-timeout", "30")
        option("force-seekable", "yes")
        config.startPositionMs.takeIf { it > 0 }?.let {
            // Applied per file. One file per session, so an option is simpler
            // than threading it through loadfile's positional arguments, whose
            // shape has changed between mpv releases.
            // Locale.ROOT, not the default: mpv parses --start as
            // [[hh:]mm:]ss[.ms] and a comma is not a decimal point there, so on
            // a German or French desktop the resume point is rejected outright
            // and the film starts from zero.
            option("start", String.format(Locale.ROOT, "%.3f", it / 1000.0))
        }
        if (config.enhancement.enabled && MpvNative.isWindows) {
            // A laptop with a discrete GPU usually drives the display from the
            // integrated one, and the NVIDIA extensions simply fail there. The
            // match is a case-insensitive prefix of the adapter description.
            option("d3d11-adapter", "NVIDIA")
        }
        // Lets the swapchain be told it is showing HDR. Without it the frames
        // the filter retags as PQ/BT.2020 get tone-mapped back down.
        option("target-colorspace-hint", "yes")
        option("msg-level", "all=v")

        val rc = mpv.mpv_initialize(handle)
        check(rc >= 0) { "mpv_initialize 失败: ${mpv.mpv_error_string(rc)}" }

        applyEnhancement(config.enhancement)
    }

    data class Config(
        val startPositionMs: Long = 0,
        val enhancement: VideoEnhancement = VideoEnhancement()
    )

    // ------------------------------------------------------------ commands

    fun play(url: String) = command("loadfile", url)

    /**
     * Attaches an external subtitle. mpv numbers these after the embedded ones
     * in the order they are added, which is how the caller maps them back to
     * DAView's own stream indices.
     */
    fun addSubtitle(url: String, title: String, language: String?) =
        command("sub-add", url, "auto", title, language ?: "")

    /**
     * Track selection is deliberately split from switching a track off.
     *
     * One nullable id doing both jobs is how a container this app cannot probe
     * — anything that is not Matroska or MP4 — ended up playing silently: no
     * audio stream in the item means no index to map, and "no index" was being
     * sent to mpv as `aid=no`. Not knowing which track to pick is a reason to
     * leave mpv's own choice alone, which is what the Android player does.
     */
    fun selectAudio(mpvId: Int) = setProperty("aid", mpvId.toString())

    fun selectSubtitle(mpvId: Int) = setProperty("sid", mpvId.toString())

    /** Explicitly off, which only the viewer asks for. */
    fun disableSubtitle() = setProperty("sid", "no")

    fun applyEnhancement(enhancement: VideoEnhancement) {
        // Windows-only: the filter does not exist on the other platforms, and
        // asking for it there fails the whole chain, taking the picture with it.
        if (!MpvNative.isWindows) return
        setProperty("vf", enhancement.filterChain())
    }

    // ------------------------------------------------------------ state

    val positionMs: Long? get() = readPosition()?.also { lastPosition = it }

    /**
     * The last position that could actually be read.
     *
     * Once mpv is shutting down it answers `time-pos` with nothing, and the
     * viewer pressing `q` *is* the shutdown — so without this the only number
     * left to report would be the runtime, and reporting the runtime is how an
     * episode someone abandoned three minutes in gets marked watched.
     */
    @Volatile
    var lastPosition: Long? = null
        private set

    val paused: Boolean get() = property("pause") == "yes"

    private fun readPosition(): Long? =
        property("time-pos")?.toDoubleOrNull()?.let { (it * 1000).toLong() }

    private fun rememberPosition() {
        readPosition()?.let { lastPosition = it }
    }

    // ------------------------------------------------------------ plumbing

    private fun option(name: String, value: String) {
        val handle = ctx ?: return
        val rc = mpv.mpv_set_option_string(handle, name, value)
        if (rc < 0) warn("选项 $name=$value 被拒绝: ${mpv.mpv_error_string(rc)}")
    }

    private fun setProperty(name: String, value: String) {
        val handle = ctx ?: return
        if (closed.get()) return
        val rc = mpv.mpv_set_property_string(handle, name, value)
        if (rc < 0) warn("属性 $name=$value 被拒绝: ${mpv.mpv_error_string(rc)}")
    }

    /**
     * A line this app made up rather than one mpv emitted, routed the same way
     * so that `-Ddaview.mpv.log=1` shows both. Keeping them apart is how a
     * rejected option stayed invisible even with logging turned on.
     */
    private fun warn(text: String) {
        if (echoLog) System.err.println("[mpv/warn] daview: $text")
        runCatching { listener.onLog("daview", "warn", text) }
    }

    private fun property(name: String): String? {
        val handle = ctx ?: return null
        if (closed.get()) return null
        val pointer = mpv.mpv_get_property_string(handle, name) ?: return null
        return try {
            pointer.getString(0)
        } finally {
            mpv.mpv_free(pointer)
        }
    }

    private fun command(vararg args: String) {
        val handle = ctx ?: return
        if (closed.get()) return
        // The array is NULL-terminated, which is what the trailing null is.
        val rc = mpv.mpv_command(handle, arrayOf(*args, null))
        // Commands fail for reasons that look like nothing at all from the
        // outside — `sub-add` before a file is loaded returns -12 and attaches
        // nothing — so a failure has to leave a trace somewhere.
        if (rc < 0) warn("命令 ${args.joinToString(" ")} 失败: ${mpv.mpv_error_string(rc)}")
    }

    private fun startPump(handle: Pointer) {
        val thread = Thread({
            try {
                pumpEvents(handle)
            } finally {
                // Whoever gets here last owns the handle. close() may have given
                // up waiting for this thread, in which case destroying it there
                // would have freed a context this thread was still inside.
                if (destroyPending.compareAndSet(true, false)) {
                    runCatching { mpv.mpv_terminate_destroy(handle) }
                }
            }
        }, "daview-mpv-events")
        thread.isDaemon = true
        pump = thread
        thread.start()
    }

    private fun pumpEvents(handle: Pointer) {
        while (!closed.get()) {
            val event = mpv.mpv_wait_event(handle, WAIT_TIMEOUT_SECONDS) ?: continue
            when (event.getInt(EVENT_ID_OFFSET)) {
                EVENT_NONE -> Unit

                EVENT_SHUTDOWN -> {
                    // mpv can end itself: the default key bindings are on so its
                    // on-screen controller works, and `q` over the video quits.
                    // Nothing else would tell the screen, which would be left on
                    // a dead surface with a live session. Not reported when this
                    // app asked for it — close() is already unwinding.
                    if (!closed.get()) runCatching { listener.onShutdown() }
                    return
                }

                EVENT_LOG_MESSAGE -> {
                    val data = event.getPointer(EVENT_DATA_OFFSET) ?: continue
                    val prefix = data.getPointer(0)?.getString(0).orEmpty()
                    val level = data.getPointer(8)?.getString(0).orEmpty()
                    val text = data.getPointer(16)?.getString(0).orEmpty().trimEnd('\n')
                    // Nothing in the app surfaces mpv's own log, and when
                    // playback misbehaves mpv is the only thing that knows why.
                    // `-Ddaview.mpv.log=1` puts it back on stderr.
                    if (echoLog) System.err.println("[mpv/$level] $prefix: $text")
                    runCatching { listener.onLog(prefix, level, text) }
                }

                EVENT_FILE_LOADED -> runCatching { listener.onFileLoaded() }

                EVENT_END_FILE -> {
                    // struct mpv_event_end_file { int reason; int error; ... }
                    val data = event.getPointer(EVENT_DATA_OFFSET)
                    val reason = data?.getInt(0) ?: END_FILE_EOF
                    val code = data?.getInt(4) ?: 0
                    val error = if (reason == END_FILE_ERROR) mpv.mpv_error_string(code) else null
                    // Reading the position now is the last chance: mpv answers
                    // almost nothing once it has begun shutting down, and a
                    // viewer who pressed `q` is exactly the case where the real
                    // position matters most.
                    rememberPosition()
                    runCatching { listener.onEndFile(error, reason == END_FILE_EOF) }
                }
            }
        }
    }

    /**
     * Asks mpv to quit and gives the handle up, without waiting for it.
     *
     * Tearing mpv down unloads the video output and destroys a D3D11 device,
     * which takes long enough to be visible and is not something to do on the
     * thread that draws the window — this is called from `onDispose`, on the
     * UI thread. So the caller only pays for `quit`, and the waiting happens
     * elsewhere.
     *
     * Destroying a handle another thread is inside `mpv_wait_event` on is
     * undefined — a JVM crash with no Java stack — so the destroy is a claim
     * both sides make and only one wins: whichever gets there once the event
     * thread has actually stopped. If that thread outlives the wait, it
     * destroys the handle itself on the way out rather than having it pulled
     * out from under it; and if it never returns, the handle leaks, which
     * beats freeing memory something is reading.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val handle = ctx ?: return
        ctx = null
        destroyPending.set(true)
        runCatching { mpv.mpv_command(handle, arrayOf("quit", null)) }
        runCatching { mpv.mpv_wakeup(handle) }

        val thread = pump
        Thread({
            runCatching { thread?.join(JOIN_TIMEOUT_MS) }
            if (thread == null || !thread.isAlive) {
                if (destroyPending.compareAndSet(true, false)) {
                    runCatching { mpv.mpv_terminate_destroy(handle) }
                }
            }
        }, "daview-mpv-close").apply { isDaemon = true }.start()
    }

    private companion object {
        val echoLog: Boolean = System.getProperty("daview.mpv.log") != null

        // struct mpv_event { int event_id; int error; uint64_t reply_userdata; void *data; }
        const val EVENT_ID_OFFSET = 0L
        const val EVENT_DATA_OFFSET = 16L

        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_LOG_MESSAGE = 2
        const val EVENT_END_FILE = 7
        const val END_FILE_EOF = 0
        const val END_FILE_ERROR = 4
        const val EVENT_FILE_LOADED = 8

        const val JOIN_TIMEOUT_MS = 2000L
        const val WAIT_TIMEOUT_SECONDS = 0.2
    }
}
