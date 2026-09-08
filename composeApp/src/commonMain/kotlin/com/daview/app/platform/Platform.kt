package com.daview.app.platform

/** One external player DAView knows how to hand a URL to. */
data class ExternalPlayerInfo(
    val id: String,
    val label: String,
    /** Present when the executable was found on disk; web targets use schemes. */
    val executablePath: String? = null,
    val viaUrlScheme: Boolean = false
)

data class ExternalPlayRequest(
    val player: ExternalPlayerInfo,
    val streamUrl: String,
    val title: String,
    val startPositionMs: Long,
    val subtitleUrl: String? = null
)

/**
 * A launched external player. Desktop can watch the real process; the web can
 * only fire the URL scheme and hope, which is why [canObserveExit] exists.
 */
interface ExternalPlaybackHandle {
    val canObserveExit: Boolean
    fun isRunning(): Boolean
    suspend fun awaitExit()
    fun stop()
}

expect object PlatformInfo {
    val name: String
    val isDesktop: Boolean
    val isAndroid: Boolean

    /** True where an in-app player exists (Android today). */
    val hasInternalPlayer: Boolean
}

expect fun defaultDeviceName(): String

expect fun availableExternalPlayers(): List<ExternalPlayerInfo>

expect fun launchExternalPlayer(request: ExternalPlayRequest): ExternalPlaybackHandle?

expect fun openUrl(url: String)

expect fun copyToClipboard(text: String)

/**
 * Opens the platform's file chooser and returns the file's text, or null when
 * the user backed out.
 *
 * Needed because "put the file somewhere and press import" has no meaning on
 * Android: the app's data directory sits under `filesDir`, where the user
 * cannot put anything without root.
 */
expect suspend fun pickTextFile(): String?

/**
 * Opens the platform's save dialog and writes the file through [write], or
 * returns null when the user backed out.
 *
 * Takes a writer rather than a string because a full catalogue export is a few
 * megabytes assembled a page at a time; there is no reason to hold all of it in
 * memory on the way to a file the user chose.
 */
expect suspend fun saveTextFile(suggestedName: String, write: (Appendable) -> Unit): String?

/** Simple string key/value persistence backed by whatever the platform offers. */
interface SettingsStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

expect fun createSettingsStore(): SettingsStore

/**
 * Tells the platform a scan has started, where being backgrounded would
 * otherwise kill it. A no-op on desktop, where a window losing focus does not
 * end the process.
 */
expect fun onScanStarted()

