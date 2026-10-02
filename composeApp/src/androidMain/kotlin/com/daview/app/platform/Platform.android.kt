package com.daview.app.platform

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

@SuppressLint("StaticFieldLeak")
object AndroidContextHolder {
    lateinit var context: Context
    val isInitialised: Boolean get() = ::context.isInitialized
}

actual object PlatformInfo {
    actual val name: String = "Android ${Build.VERSION.RELEASE}"
    actual val isDesktop: Boolean = false
    actual val isAndroid: Boolean = true
    actual val hasInternalPlayer: Boolean = true

    // Loads the native libraries on first use; a build made without them
    // answers false and simply plays no DTS or TrueHD.
    actual val hasFfmpegDecoders: Boolean
        get() = runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() }.getOrDefault(false)
}

actual fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

/**
 * Android has no PotPlayer; the useful equivalents are the generic
 * ACTION_VIEW chooser plus the two players most people already have — listed
 * only when they are actually installed, since picking one that was not used
 * to fail with a message only the detail page showed.
 */
actual fun availableExternalPlayers(): List<ExternalPlayerInfo> = listOfNotNull(
    ExternalPlayerInfo("android-chooser", "其他播放器…", viaUrlScheme = true),
    ExternalPlayerInfo("mx", "MX Player", viaUrlScheme = true).takeIf { installedMx() != null },
    ExternalPlayerInfo("vlc", "VLC", viaUrlScheme = true).takeIf { isInstalled(VLC_PACKAGE) }
)

private val MX_PACKAGES = listOf("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro")
private const val VLC_PACKAGE = "org.videolan.vlc"

private fun isInstalled(pkg: String): Boolean =
    AndroidContextHolder.isInitialised && runCatching {
        AndroidContextHolder.context.packageManager.getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(false)

private fun installedMx(): String? = MX_PACKAGES.firstOrNull(::isInstalled)

actual fun launchExternalPlayer(request: ExternalPlayRequest): ExternalPlaybackHandle? {
    if (!AndroidContextHolder.isInitialised) return null
    val context = AndroidContextHolder.context
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(request.streamUrl), "video/*")
        putExtra("title", request.title)
        putExtra("position", request.startPositionMs.toInt())
        // MX Player / VLC resume-position extras.
        putExtra("secure_uri", true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when (request.player.id) {
            "mx" -> setPackage(installedMx() ?: MX_PACKAGES.first())
            "vlc" -> setPackage(VLC_PACKAGE)
        }
    }
    return runCatching {
        context.startActivity(
            if (request.player.id == "android-chooser") Intent.createChooser(intent, "选择播放器")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else intent
        )
        object : ExternalPlaybackHandle {
            override val canObserveExit = false
            override fun isRunning() = false
            override suspend fun awaitExit() = Unit
            override fun stop() = Unit
        }
    }.getOrNull()
}

actual fun openUrl(url: String) {
    if (!AndroidContextHolder.isInitialised) return
    runCatching {
        AndroidContextHolder.context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Storage Access Framework, which is the only way an Android app may read a file the user chose. */
actual suspend fun pickTextFile(): String? = AndroidFilePicker.pick()

actual suspend fun saveTextFile(suggestedName: String, write: (Appendable) -> Unit): String? =
    AndroidFilePicker.save(suggestedName, write)

actual fun copyToClipboard(text: String) {
    if (!AndroidContextHolder.isInitialised) return
    val manager = AndroidContextHolder.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("DAView", text))
}

actual fun onScanStarted() {
    requestNotificationPermission()
    startKeepingAlive { com.daview.app.ScanForegroundService.start(it) }
}

actual fun onDownloadStarted() {
    requestNotificationPermission()
    startKeepingAlive { com.daview.app.DownloadForegroundService.start(it) }
}

/**
 * Starts a foreground service, and survives being told no.
 *
 * Since Android 12 an app that is not in the foreground may not start one:
 * the call throws. These are reached from coroutines — a download queue
 * taken up again at start-up, a scan that begins after a listing — so the
 * person may already have left the app by the time one gets here, and the
 * exception then took the whole app down. Without the service the work
 * simply runs unprotected, which is what it did before there was one.
 */
private fun startKeepingAlive(start: (Context) -> Unit) {
    if (!AndroidContextHolder.isInitialised) return
    runCatching { start(AndroidContextHolder.context) }
}

actual suspend fun pickImageFile(): PickedFile? = AndroidFilePicker.pickBytes(arrayOf("image/*"))

/** Downloads live in the app's own storage on a phone; there is nothing to choose. */
actual suspend fun pickDirectory(title: String, initial: String?): String? = null

/** No file manager to show it in: the files are private to the app. */
actual fun revealInFileManager(path: String) = Unit

/**
 * Wi-Fi, ethernet, or a hotspot the system knows is not metered. Mobile data
 * says no, and so does having no network at all — a download then waits
 * instead of failing.
 */
actual fun isUnmeteredNetwork(): Boolean {
    if (!AndroidContextHolder.isInitialised) return true
    val manager = AndroidContextHolder.context.getSystemService(android.net.ConnectivityManager::class.java)
        ?: return true
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

actual fun requestNotificationPermission() = NotificationPermission.request()

actual fun createSettingsStore(): SettingsStore = object : SettingsStore {
    private val preferences = AndroidContextHolder.context
        .getSharedPreferences("daview", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun putString(key: String, value: String?) {
        preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** The app's own in-process server, unless the user has pointed it elsewhere. */

actual fun updateAssetKey(): String = "android"

actual fun canSelfUpdate(): Boolean =
    AndroidContextHolder.isInitialised &&
        (AndroidContextHolder.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0

/**
 * The package installer takes over from here: the first time it asks to allow
 * installs from this app, then it shows the update. The file is served through
 * the FileProvider declared in the manifest — an installer may not be handed a
 * bare file path since Android 7.
 */
actual fun installUpdate(file: String): InstallOutcome {
    if (!AndroidContextHolder.isInitialised) return InstallOutcome.FAILED
    val context = AndroidContextHolder.context
    return runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", java.io.File(file)
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        InstallOutcome.HANDED_OVER
    }.getOrElse { InstallOutcome.FAILED }
}
