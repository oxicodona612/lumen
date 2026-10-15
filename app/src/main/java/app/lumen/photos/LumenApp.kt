package app.lumen.photos

import android.app.Application
import app.lumen.photos.data.MediaThumbFetcher
import app.lumen.photos.data.MediaThumbKeyer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder

class LumenApp : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(MediaThumbKeyer())
                add(MediaThumbFetcher.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.30)
                    .build()
            }
            // Grid cells pop in instantly, the way iOS does; fades read as lag at fling speed.
            .crossfade(false)
            .build()
}
