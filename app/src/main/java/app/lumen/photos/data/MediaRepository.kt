package app.lumen.photos.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import android.provider.OpenableColumns
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class MediaRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val imagesUri: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val videoUri: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        FileColumns._ID,
        FileColumns.MEDIA_TYPE,
        MediaColumns.MIME_TYPE,
        MediaColumns.DISPLAY_NAME,
        MediaColumns.DATE_TAKEN,
        MediaColumns.DATE_MODIFIED,
        MediaColumns.DATE_ADDED,
        MediaColumns.WIDTH,
        MediaColumns.HEIGHT,
        MediaColumns.ORIENTATION,
        MediaColumns.DURATION,
        MediaColumns.SIZE,
        MediaColumns.BUCKET_ID,
        MediaColumns.BUCKET_DISPLAY_NAME,
        MediaColumns.RELATIVE_PATH,
        MediaColumns.IS_FAVORITE,
        MediaColumns.DATE_EXPIRES,
    )

    /** Oldest first, like iOS: the grid opens pinned to the newest row at the bottom. */
    fun load(trashed: Boolean = false): List<MediaItem> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${FileColumns.MEDIA_TYPE} IN (?, ?)")
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString(), FileColumns.MEDIA_TYPE_VIDEO.toString()),
            )
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val out = ArrayList<MediaItem>(4096)
        resolver.query(filesUri, projection, args, null)?.use { c ->
            val ix = Columns(c)
            while (c.moveToNext()) out += read(c, ix)
        }
        out.sortWith(compareBy<MediaItem> { it.dateTaken }.thenBy { it.id })
        return out
    }

    private class Columns(c: Cursor) {
        val id = c.getColumnIndexOrThrow(FileColumns._ID)
        val type = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        val mime = c.getColumnIndexOrThrow(MediaColumns.MIME_TYPE)
        val name = c.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
        val taken = c.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
        val modified = c.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
        val added = c.getColumnIndexOrThrow(MediaColumns.DATE_ADDED)
        val width = c.getColumnIndexOrThrow(MediaColumns.WIDTH)
        val height = c.getColumnIndexOrThrow(MediaColumns.HEIGHT)
        val orientation = c.getColumnIndexOrThrow(MediaColumns.ORIENTATION)
        val duration = c.getColumnIndexOrThrow(MediaColumns.DURATION)
        val size = c.getColumnIndexOrThrow(MediaColumns.SIZE)
        val bucketId = c.getColumnIndexOrThrow(MediaColumns.BUCKET_ID)
        val bucketName = c.getColumnIndexOrThrow(MediaColumns.BUCKET_DISPLAY_NAME)
        val path = c.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
        val favorite = c.getColumnIndexOrThrow(MediaColumns.IS_FAVORITE)
        val expires = c.getColumnIndexOrThrow(MediaColumns.DATE_EXPIRES)
    }

    private fun read(c: Cursor, ix: Columns): MediaItem {
        val id = c.getLong(ix.id)
        val isVideo = c.getInt(ix.type) == FileColumns.MEDIA_TYPE_VIDEO
        val taken = c.getLong(ix.taken).takeIf { it > 0 }
            ?: c.getLong(ix.modified).takeIf { it > 0 }?.times(1000)
            ?: c.getLong(ix.added) * 1000
        val rotated = c.getInt(ix.orientation).let { it == 90 || it == 270 }
        val w = c.getInt(ix.width)
        val h = c.getInt(ix.height)
        return MediaItem(
            key = "m$id",
            id = id,
            uri = ContentUris.withAppendedId(if (isVideo) videoUri else imagesUri, id),
            origin = Origin.Store,
            isVideo = isVideo,
            mime = c.getString(ix.mime) ?: if (isVideo) "video/mp4" else "image/jpeg",
            name = c.getString(ix.name) ?: "",
            dateTaken = taken,
            width = if (rotated) h else w,
            height = if (rotated) w else h,
            durationMs = c.getLong(ix.duration),
            sizeBytes = c.getLong(ix.size),
            bucketId = c.getLong(ix.bucketId),
            bucketName = c.getString(ix.bucketName) ?: "",
            relativePath = c.getString(ix.path) ?: "",
            isFavorite = c.getInt(ix.favorite) == 1,
            expiresAt = c.getLong(ix.expires) * 1000,
        )
    }

    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(imagesUri, true, observer)
        resolver.registerContentObserver(videoUri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    fun trashRequest(items: List<MediaItem>, trash: Boolean): IntentSender =
        MediaStore.createTrashRequest(resolver, items.map { it.uri }, trash).intentSender

    fun deleteRequest(items: List<MediaItem>): IntentSender =
        MediaStore.createDeleteRequest(resolver, items.map { it.uri }).intentSender

    fun favoriteRequest(items: List<MediaItem>, favorite: Boolean): IntentSender =
        MediaStore.createFavoriteRequest(resolver, items.map { it.uri }, favorite).intentSender

    /** Builds a viewer item for a URI handed to us by another app. */
    fun describe(uri: Uri, mimeHint: String?): MediaItem {
        val mime = mimeHint?.takeIf { it.contains('/') && !it.endsWith("/*") }
            ?: resolver.getType(uri)
            ?: "image/jpeg"
        var name = uri.lastPathSegment ?: "Photo"
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        size = c.getLong(1)
                    }
                }
        }
        val isVideo = mime.startsWith("video/")
        var w = 0
        var h = 0
        var duration = 0L
        runCatching {
            if (isVideo) {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) w = h.also { h = w }
                }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                w = opts.outWidth
                h = opts.outHeight
                val rotation = resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
                if (rotation == ExifInterface.ORIENTATION_ROTATE_90 || rotation == ExifInterface.ORIENTATION_ROTATE_270) {
                    w = h.also { h = w }
                }
            }
        }
        return MediaItem(
            key = "x:$uri",
            id = -1,
            uri = uri,
            origin = Origin.External,
            isVideo = isVideo,
            mime = mime,
            name = name,
            dateTaken = System.currentTimeMillis(),
            width = w,
            height = h,
            durationMs = duration,
            sizeBytes = size,
            bucketId = 0,
            bucketName = "",
            relativePath = "",
            isFavorite = false,
        )
    }
}
