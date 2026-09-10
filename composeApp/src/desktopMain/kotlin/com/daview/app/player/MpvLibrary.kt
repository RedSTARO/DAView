package com.daview.app.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * The slice of libmpv's C API this app uses.
 *
 * Deliberately small: everything the player needs is expressible as options,
 * properties and commands, all of which are strings, so no mpv struct has to be
 * mapped except the event header — and that is read by offset in [MpvPlayer]
 * rather than through a JNA `Structure`.
 *
 * See `include/mpv/client.h` in the mpv source tree.
 */
internal interface MpvLibrary : Library {
    fun mpv_client_api_version(): Long

    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)

    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int

    /** Returns a string mpv allocated; it has to go back through [mpv_free]. */
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)

    /** `args` is a NULL-terminated array, so the last element must be null. */
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int

    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
    fun mpv_wakeup(ctx: Pointer)

    fun mpv_error_string(error: Int): String
}

/**
 * Finds and loads libmpv, once per process.
 *
 * The library is not a build dependency: it is ~115 MB, it is GPL, and on Linux
 * and macOS it is normally already installed. So it is looked up at run time and
 * its absence is a supported state — [available] false simply means the desktop
 * build has no in-app player and the app hands playback to PotPlayer, VLC or mpv
 * the way it always did.
 */
object MpvNative {

    /** Where the user pointed us, when the bundled and installed copies are wrong. */
    @Volatile
    var overridePath: String? = null
        set(value) {
            val next = value?.takeIf { it.isNotBlank() }
            if (next == field) return
            field = next
            // A new path deserves another attempt, including after a failure.
            // Only a *new* one: this is written on every availability check, and
            // resetting unconditionally would re-run the search each time.
            synchronized(this) { resolved = null; attempted = false }
        }

    /**
     * Every string mpv takes or returns is UTF-8, and JNA would otherwise use
     * the platform default — GBK on a Chinese Windows, and something else
     * again elsewhere. It is not a detail that shows up in testing on an
     * English machine: ASCII survives either way, so a URL and a track id look
     * fine while every title, track name and OSD line arrives as mojibake.
     */
    private val UTF8 = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")

    private var resolved: MpvLibrary? = null
    private var attempted = false

    /** Non-null once a load has failed, so settings can say why. */
    @Volatile
    var loadError: String? = null
        private set

    /** The file that was actually loaded. */
    @Volatile
    var loadedFrom: String? = null
        private set

    @Synchronized
    internal fun library(): MpvLibrary? {
        if (attempted) return resolved
        attempted = true
        val candidates = searchPath()
        for (candidate in candidates) {
            val loaded = runCatching { Native.load(candidate, MpvLibrary::class.java, UTF8) }
                .onFailure { loadError = "${it.javaClass.simpleName}: ${it.message}" }
                .getOrNull() ?: continue
            // Reaching a symbol is the only proof the file is the right library;
            // loading alone succeeds for anything with the expected name.
            val version = runCatching { loaded.mpv_client_api_version() }.getOrNull()
            if (version == null) {
                loadError = "$candidate 不是可用的 libmpv"
                continue
            }
            resolved = loaded
            loadedFrom = candidate
            loadError = null
            return loaded
        }
        if (loadError == null) loadError = "未找到 ${libraryFileName()}"
        return null
    }

    val available: Boolean get() = library() != null

    /** mpv's own API version, as `major.minor`, once loaded. */
    val apiVersion: String?
        get() = library()?.mpv_client_api_version()?.let { "${it shr 16}.${it and 0xFFFF}" }

    private fun libraryFileName(): String = when {
        isWindows -> "libmpv-2.dll"
        isMac -> "libmpv.2.dylib"
        else -> "libmpv.so.2"
    }

    /**
     * Absolute paths first, bare name last.
     *
     * The order matters on Windows for a reason that is not about preference:
     * JNA opens an absolute path with `LOAD_WITH_ALTERED_SEARCH_PATH`, which
     * puts the DLL's own directory on the dependency search path. The installed
     * app keeps the library in `app/resources`, which is not next to the
     * launcher and therefore not on the loader's path by default.
     */
    private fun searchPath(): List<String> = buildList {
        val fileName = libraryFileName()

        overridePath?.let { path ->
            val file = File(path)
            add(if (file.isDirectory) File(file, fileName).absolutePath else file.absolutePath)
        }

        // Where jpackage puts appResourcesRootDir, and where `./gradlew run`
        // points the same property.
        System.getProperty("compose.application.resources.dir")?.let {
            add(File(it, fileName).absolutePath)
        }
        // Running from an IDE there is no such property, so the source tree it
        // would have been built from is worth a look.
        add(File("composeApp/nativeResources/$osDirectory/$fileName").absolutePath)

        if (isWindows) {
            add("C:/Program Files/mpv/$fileName")
            add("C:/Program Files/mpv-dev/$fileName")
            System.getenv("LOCALAPPDATA")?.let { add("$it/Programs/mpv/$fileName") }
        } else {
            add("/usr/lib/$fileName")
            add("/usr/local/lib/$fileName")
            add("/opt/homebrew/lib/$fileName")
        }

        // Whatever the platform loader can find on its own.
        add(if (isWindows) "libmpv-2" else "mpv")
    }.distinct().filter { candidate ->
        // A bare name has no file to check — it is a question for the platform
        // loader. A path that is not there would only produce a load error that
        // then masks the real reason in [loadError].
        val isPath = candidate.contains('/') || candidate.contains(File.separatorChar)
        !isPath || File(candidate).isFile
    }

    private val osName: String get() = System.getProperty("os.name").orEmpty()
    val isWindows: Boolean get() = osName.startsWith("Windows", ignoreCase = true)
    private val isMac: Boolean get() = osName.startsWith("Mac", ignoreCase = true)

    private val osDirectory: String get() = when {
        isWindows -> "windows"
        isMac -> "macos"
        else -> "linux"
    }
}
