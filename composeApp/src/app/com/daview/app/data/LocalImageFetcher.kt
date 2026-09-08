package com.daview.app.data

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.daview.server.api.MediaFacade
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Loads artwork from the cache on this device instead of over HTTP.
 *
 * The file is already here — the scraper downloaded it — so the old path was
 * Coil asking a socket on this same machine to read a local file back to it and
 * decode the result. This hands Coil the file.
 *
 * The first request for an image the cache has not seen still goes out to the
 * provider, inside [MediaFacade.imageFile]; the difference is that it happens
 * once, and not through a server.
 */
class LocalImageFetcher(
    private val itemId: String,
    private val type: String,
    private val library: MediaFacade
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val file = library.imageFile(itemId, type) ?: return null
        return SourceFetchResult(
            source = ImageSource(file = file.toString().toPath(), fileSystem = FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val library: MediaFacade) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != LocalAssetLinks.IMAGE_SCHEME) return null
            val (itemId, type) = LocalAssetLinks.parseImage(data.toString()) ?: return null
            return LocalImageFetcher(itemId, type, library)
        }
    }
}
