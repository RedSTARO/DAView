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
}

actual fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

/**
 * Android has no PotPlayer; the useful equivalents are the generic
 * `ACTION_VIEW` chooser plus the two players most people already have.
 */
actual fun availableExternalPlayers(): List<ExternalPlayerInfo> = listOf(
    ExternalPlayerInfo("android-chooser", "其他播放器…", viaUrlScheme = true),
    ExternalPlayerInfo("mx", "MX Player", viaUrlScheme = true),
    ExternalPlayerInfo("vlc", "VLC", viaUrlScheme = true)
)

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
            "mx" -> setPackage("com.mxtech.videoplayer.ad")
            "vlc" -> setPackage("org.videolan.vlc")
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

actual fun copyToClipboard(text: String) {
    if (!AndroidContextHolder.isInitialised) return
    val manager = AndroidContextHolder.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("DAView", text))
}

actual fun createSettingsStore(): SettingsStore = object : SettingsStore {
    private val preferences = AndroidContextHolder.context
        .getSharedPreferences("daview", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun putString(key: String, value: String?) {
        preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** The app's own in-process server, unless the user has pointed it elsewhere. */
actual fun ambientServerUrl(): String? = com.daview.app.EmbeddedServer.url

actual fun ambientToken(): String? = com.daview.app.EmbeddedServer.token
