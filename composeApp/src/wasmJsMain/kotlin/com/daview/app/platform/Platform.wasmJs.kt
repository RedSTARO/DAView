@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.daview.app.platform

// Browser interop is done with small `js(...)` bridges so the client does not
// need an extra browser-API dependency on top of Compose.
private fun jsLocalStorageGet(key: String): String =
    js("(function(){ try { return localStorage.getItem(key) || '' } catch (e) { return '' } })()")

private fun jsLocalStorageSet(key: String, value: String) {
    js("try { localStorage.setItem(key, value) } catch (e) {}")
}

private fun jsLocalStorageRemove(key: String) {
    js("try { localStorage.removeItem(key) } catch (e) {}")
}

private fun jsOrigin(): String = js("window.location.origin")

private fun jsQueryParam(name: String): String =
    js("(new URLSearchParams(window.location.search)).get(name) || ''")

private fun jsOpen(url: String) {
    js("window.open(url, '_blank')")
}

private fun jsNavigate(url: String) {
    js("window.location.href = url")
}

private fun jsCopy(text: String) {
    js(
        "(function(){ if (navigator.clipboard) { navigator.clipboard.writeText(text) } else { " +
            "var a=document.createElement('textarea'); a.value=text; document.body.appendChild(a); " +
            "a.select(); document.execCommand('copy'); document.body.removeChild(a) } })()"
    )
}

private fun jsUserAgent(): String = js("navigator.userAgent")

actual object PlatformInfo {
    actual val name: String = "Web"
    actual val isDesktop: Boolean = false
    actual val isAndroid: Boolean = false
    actual val isWeb: Boolean = true

    /**
     * No in-canvas player: browsers cannot decode Matroska, and the useful
     * fallbacks are the native tab player and the PotPlayer URL scheme.
     */
    actual val hasInternalPlayer: Boolean = false
}

actual fun defaultDeviceName(): String {
    val agent = jsUserAgent()
    val browser = when {
        agent.contains("Edg/") -> "Edge"
        agent.contains("Chrome/") -> "Chrome"
        agent.contains("Firefox/") -> "Firefox"
        agent.contains("Safari/") -> "Safari"
        else -> "浏览器"
    }
    return "Web · $browser"
}

/**
 * A browser cannot start a local process, but PotPlayer's installer registers a
 * `potplayer://` URL scheme, so handing it the DAView stream URL works — and
 * because that URL points back at the server, playback progress is still
 * tracked from the byte ranges PotPlayer requests.
 */
actual fun availableExternalPlayers(): List<ExternalPlayerInfo> = listOf(
    ExternalPlayerInfo("potplayer", "PotPlayer", viaUrlScheme = true),
    ExternalPlayerInfo("vlc", "VLC", viaUrlScheme = true),
    ExternalPlayerInfo("copy", "复制播放地址", viaUrlScheme = true)
)

actual fun launchExternalPlayer(request: ExternalPlayRequest): ExternalPlaybackHandle? {
    when (request.player.id) {
        "potplayer" -> jsNavigate("potplayer://" + request.streamUrl)
        "vlc" -> jsNavigate("vlc://" + request.streamUrl)
        else -> copyToClipboard(request.streamUrl)
    }
    return object : ExternalPlaybackHandle {
        override val canObserveExit = false
        override fun isRunning() = false
        override suspend fun awaitExit() = Unit
        override fun stop() = Unit
    }
}

actual fun openUrl(url: String) = jsOpen(url)

actual fun copyToClipboard(text: String) = jsCopy(text)

actual fun createSettingsStore(): SettingsStore = object : SettingsStore {
    override fun getString(key: String): String? = jsLocalStorageGet("daview.$key").takeIf { it.isNotEmpty() }
    override fun putString(key: String, value: String?) {
        if (value == null) jsLocalStorageRemove("daview.$key") else jsLocalStorageSet("daview.$key", value)
    }
}

actual fun ambientServerUrl(): String? = jsOrigin().takeIf { it.isNotBlank() && it != "null" }

actual fun ambientToken(): String? = jsQueryParam("token").takeIf { it.isNotEmpty() }
