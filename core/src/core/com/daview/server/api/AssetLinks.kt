package com.daview.server.api

/**
 * How the bytes behind an item are addressed for whoever is going to fetch them.
 *
 * An app holding the core in its own process needs no server: artwork gets a
 * scheme its image loader resolves against the local cache, and video gets an
 * address on the playback pipe. The interface dates from when a browser was
 * the other consumer and needed absolute URLs carrying an access token; it is
 * kept because it is what keeps [MediaFacade] from knowing how the app
 * addresses its bytes.
 */
interface AssetLinks {

    /**
     * [remoteUrl] is the provider's own artwork URL. It is passed so the
     * address can carry a version derived from it: the endpoint is otherwise
     * stable per item, and re-identifying one would leave every client showing
     * the previous poster out of its own cache.
     */
    fun image(itemId: String, type: String, remoteUrl: String): String

    /**
     * Where the player should ask for the media.
     *
     * [proxy] asks for the bytes to pass through so a player that reports
     * nothing can still be followed by its byte ranges; otherwise the consumer
     * is free to send it straight to the storage.
     */
    fun stream(itemId: String, fileName: String, sessionId: String, proxy: Boolean): String

    /**
     * [sessionId] is the playback session this belongs to. It is what tells a
     * local pipe when the address stops being needed; an HTTP front end has no
     * use for it.
     */
    fun subtitle(itemId: String, index: Int, sessionId: String): String
}
