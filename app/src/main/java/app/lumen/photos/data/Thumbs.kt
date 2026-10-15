package app.lumen.photos.data

import android.net.Uri
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse

/**
 * Grid thumbnails come from MediaProvider's own thumbnail cache instead of decoding
 * the original. It's what keeps a 20k-photo grid flinging at frame rate.
 */
data class MediaThumb(val uri: Uri, val key: String)

class MediaThumbKeyer : Keyer<MediaThumb> {
    override fun key(data: MediaThumb, options: Options): String = "mt:${data.key}"
}

class MediaThumbFetcher(
    private val data: MediaThumb,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val width = options.size.width.pxOrElse { 384 }
        val height = options.size.height.pxOrElse { 384 }
        val bitmap = options.context.contentResolver.loadThumbnail(data.uri, Size(width, height), null)
        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbFetcher(data, options)
    }
}
