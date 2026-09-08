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
    val isWeb: Boolean

    /** True where an in-app player exists (Android today). */
    val hasInternalPlayer: Boolean
}

expect fun defaultDeviceName(): String

expect fun availableExternalPlayers(): List<ExternalPlayerInfo>

expect fun launchExternalPlayer(request: ExternalPlayRequest): ExternalPlaybackHandle?

expect fun openUrl(url: String)

expect fun copyToClipboard(text: String)

/** Simple string key/value persistence backed by whatever the platform offers. */
interface SettingsStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

expect fun createSettingsStore(): SettingsStore

/** The server address baked into the page, used when the web client is served by DAView itself. */
expect fun ambientServerUrl(): String?

/** `?token=` handed over by the server's startup URL, so the web client can self-configure. */
expect fun ambientToken(): String?
