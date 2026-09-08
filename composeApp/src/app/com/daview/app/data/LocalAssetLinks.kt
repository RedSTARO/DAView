package com.daview.app.data

import com.daview.server.ServerContext
import com.daview.server.api.AssetLinks

/**
 * How this app addresses its own bytes now that nothing serves them over HTTP.
 *
 * Artwork gets a scheme the image loader resolves against the on-disk cache —
 * the file is already on this device, and round-tripping it through a socket
 * to read it back was most of what the local server was doing. Video gets an
 * address only when something outside the process has to fetch it, and then it
 * is the pipe, which exists only for as long as that player does.
 */
class LocalAssetLinks(private val context: ServerContext) : AssetLinks {

    /**
     * The version is part of the address so re-identifying an item invalidates
     * what the loader already cached under it; it comes from the provider URL,
     * which changes exactly when the artwork does.
     */
    override fun image(itemId: String, type: String, remoteUrl: String): String =
        "$IMAGE_SCHEME://image/$itemId/$type?v=${remoteUrl.hashCode().toUInt().toString(16)}"

    override fun stream(itemId: String, fileName: String, sessionId: String, proxy: Boolean): String =
        context.pipe.urlFor(sessionId, itemId, fileName, redirect = !proxy)

    override fun subtitle(itemId: String, index: Int): String =
        context.pipe.subtitleUrlFor(SUBTITLE_SESSION, itemId, index)

    companion object {
        const val IMAGE_SCHEME = "daview"

        /**
         * Subtitles are asked for outside any one playback session — the detail
         * page lists them before anything starts — so they hang off a session
         * of their own rather than keeping a player's session alive.
         */
        const val SUBTITLE_SESSION = "subtitles"

        /** Splits `daview://image/<itemId>/<type>` back into its two parts. */
        fun parseImage(uri: String): Pair<String, String>? {
            val path = uri.removePrefix("$IMAGE_SCHEME://image/").substringBefore('?')
            val parts = path.split('/')
            return if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0] to parts[1] else null
        }
    }
}
