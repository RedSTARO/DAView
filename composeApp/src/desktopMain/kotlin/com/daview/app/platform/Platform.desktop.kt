package com.daview.app.platform

import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.util.prefs.Preferences
import com.daview.app.player.MpvNative
import com.daview.app.player.PlayerPreferences

actual object PlatformInfo {
    actual val name: String = System.getProperty("os.name") ?: "Desktop"
    actual val isDesktop: Boolean = true
    actual val isAndroid: Boolean = false

    /**
     * There is an in-app player exactly when libmpv could be loaded. It is not
     * a build-time fact: the library is looked up on disk, the user can point
     * at another copy from settings, and where it is missing the app falls back
     * to handing the stream to PotPlayer, VLC or mpv as it always did.
     *
     * A getter rather than a stored value, so pointing at a different libmpv
     * takes effect without a restart.
     */
    actual val hasInternalPlayer: Boolean
        get() {
            PlayerPreferences.install()
            return MpvNative.available
        }
}

actual fun defaultDeviceName(): String =
    (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "Desktop") + " · ${PlatformInfo.name}"

private val windowsCandidates = listOf(
    Triple("potplayer", "PotPlayer", listOf(
        "C:/Program Files/DAUM/PotPlayer/PotPlayerMini64.exe",
        "C:/Program Files (x86)/DAUM/PotPlayer/PotPlayerMini.exe",
        "C:/Program Files/PotPlayer/PotPlayerMini64.exe"
    )),
    Triple("vlc", "VLC", listOf(
        "C:/Program Files/VideoLAN/VLC/vlc.exe",
        "C:/Program Files (x86)/VideoLAN/VLC/vlc.exe"
    )),
    Triple("mpv", "mpv", listOf(
        "C:/Program Files/mpv/mpv.exe",
        "C:/ProgramData/chocolatey/bin/mpv.exe"
    ))
)

private val unixCandidates = listOf(
    Triple("vlc", "VLC", listOf("/usr/bin/vlc", "/snap/bin/vlc", "/Applications/VLC.app/Contents/MacOS/VLC")),
    Triple("mpv", "mpv", listOf("/usr/bin/mpv", "/usr/local/bin/mpv", "/opt/homebrew/bin/mpv")),
    Triple("iina", "IINA", listOf("/Applications/IINA.app/Contents/MacOS/IINA"))
)

actual fun availableExternalPlayers(): List<ExternalPlayerInfo> {
    val isWindows = PlatformInfo.name.startsWith("Windows", ignoreCase = true)
    val candidates = if (isWindows) windowsCandidates else unixCandidates
    val found = candidates.mapNotNull { (id, label, paths) ->
        val path = paths.firstOrNull { File(it).canExecute() } ?: return@mapNotNull null
        ExternalPlayerInfo(id, label, path)
    }
    // Only once it points somewhere. It used to be listed unconditionally, with
    // nothing in the app able to set the path it reads, so it was a menu entry
    // that failed every single time it was chosen.
    val custom = customPlayerPath()?.takeIf { File(it).canExecute() }
    return found + listOfNotNull(custom?.let { ExternalPlayerInfo("custom", "自定义播放器", it) })
}

fun customPlayerPath(): String? =
    Preferences.userRoot().node("com/daview/app").get("customPlayerPath", null)

fun setCustomPlayerPath(path: String?) {
    val node = Preferences.userRoot().node("com/daview/app")
    if (path.isNullOrBlank()) node.remove("customPlayerPath") else node.put("customPlayerPath", path)
}

/**
 * Builds the command line for the player.
 *
 * PotPlayer takes `/seek=hh:mm:ss` to resume and `/sub=` for an external
 * subtitle; VLC and mpv use their own flags. Passing the DAView stream URL (not
 * the CDN link) keeps the server in the loop so it can follow the byte offsets
 * the player requests and turn them into a playback position.
 */
private fun buildCommand(request: ExternalPlayRequest, executable: String): List<String> {
    val seconds = request.startPositionMs / 1000
    return when (request.player.id) {
        "potplayer" -> buildList {
            add(executable)
            add(request.streamUrl)
            if (seconds > 0) add("/seek=%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60))
            request.subtitleUrl?.let { add("/sub=$it") }
            add("/title=${request.title}")
        }
        "vlc" -> buildList {
            add(executable)
            add(request.streamUrl)
            if (seconds > 0) add("--start-time=$seconds")
            request.subtitleUrl?.let { add("--sub-file=$it") }
            // VLC counts tracks from the file, so the container's own index is
            // what it wants here.
            request.audioTrack?.let { add("--audio-track=$it") }
            request.subtitleTrack?.let { add("--sub-track=$it") }
            add("--meta-title=${request.title}")
        }
        "mpv", "iina" -> buildList {
            add(executable)
            add(request.streamUrl)
            if (seconds > 0) add("--start=$seconds")
            request.subtitleUrl?.let { add("--sub-file=$it") }
            request.audioTrack?.let { add("--aid=$it") }
            request.subtitleTrack?.let { add("--sid=$it") }
            add("--force-media-title=${request.title}")
        }
        else -> listOf(executable, request.streamUrl)
    }
}

actual fun launchExternalPlayer(request: ExternalPlayRequest): ExternalPlaybackHandle? {
    val executable = request.player.executablePath ?: return null
    if (!File(executable).canExecute()) return null
    val process = ProcessBuilder(buildCommand(request, executable))
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()

    return object : ExternalPlaybackHandle {
        override val canObserveExit = true
        override fun isRunning() = process.isAlive
        override suspend fun awaitExit() {
            withContext(Dispatchers.IO) {
                while (process.isAlive) delay(500)
            }
        }
        override fun stop() {
            process.destroy()
        }
    }
}

actual fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url))
        }
    }
}

/**
 * AWT's own dialog rather than Swing's JFileChooser: it is the platform chooser
 * on Windows and macOS, and it needs no look-and-feel setup.
 */
actual suspend fun pickTextFile(): String? = withContext(Dispatchers.Main) {
    val dialog = FileDialog(null as Frame?, "选择备份文件", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
        isVisible = true
    }
    val directory = dialog.directory
    val file = dialog.file ?: return@withContext null
    withContext(Dispatchers.IO) {
        runCatching { File(directory, file).readText() }.getOrNull()
    }
}

actual suspend fun saveTextFile(suggestedName: String, write: (Appendable) -> Unit): String? =
    withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, "保存到", FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val directory = dialog.directory
        val chosen = dialog.file ?: return@withContext null
        withContext(Dispatchers.IO) {
            val target = File(directory, chosen)
            runCatching {
                target.bufferedWriter().use { write(it) }
                target.absolutePath
            }.getOrNull()
        }
    }

actual fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/** Nothing to do: a desktop process is not reclaimed for being in the background. */
actual fun onScanStarted() = Unit

actual fun onDownloadStarted() = Unit

actual fun createSettingsStore(): SettingsStore = object : SettingsStore {
    private val node = Preferences.userRoot().node("com/daview/app")
    override fun getString(key: String): String? = node.get(key, null)
    override fun putString(key: String, value: String?) {
        if (value == null) node.remove(key) else node.put(key, value)
    }
}

