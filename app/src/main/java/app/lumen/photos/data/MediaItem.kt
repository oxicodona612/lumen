package app.lumen.photos.data

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.io.File

enum class Origin { Store, Vault, External }

@Immutable
data class MediaItem(
    val key: String,
    val id: Long,
    val uri: Uri,
    val origin: Origin,
    val isVideo: Boolean,
    val mime: String,
    val name: String,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val isFavorite: Boolean,
    val expiresAt: Long = 0L,
    val file: File? = null,
) {
    val aspect: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height else 1f

    val isScreenshot: Boolean
        get() = relativePath.contains("Screenshot", ignoreCase = true) ||
            bucketName.contains("Screenshot", ignoreCase = true)

    /** Shared by the grid and the viewer so a full-screen open paints the cached thumb instantly. */
    val thumbCacheKey: String get() = "thumb:$key"

    fun thumbData(): Any = when (origin) {
        Origin.Store -> MediaThumb(uri, key)
        Origin.Vault -> file!!
        Origin.External -> uri
    }

    fun fullData(): Any = file ?: uri
}

@Immutable
data class Album(
    val bucketId: Long,
    val name: String,
    val count: Int,
    val cover: MediaItem,
)

sealed interface Source {
    data object Library : Source
    data class Bucket(val id: Long, val name: String) : Source
    data object Videos : Source
    data object Screenshots : Source
    data object Favorites : Source
    data object Hidden : Source
    data object Trash : Source
    data class External(val item: MediaItem) : Source
}

enum class ActionSet { Library, Hidden, Trash, External }

val Source.actionSet: ActionSet
    get() = when (this) {
        Source.Hidden -> ActionSet.Hidden
        Source.Trash -> ActionSet.Trash
        is Source.External -> ActionSet.External
        else -> ActionSet.Library
    }

fun Source.title(): String = when (this) {
    Source.Library -> "Library"
    is Source.Bucket -> name
    Source.Videos -> "Videos"
    Source.Screenshots -> "Screenshots"
    Source.Favorites -> "Favorites"
    Source.Hidden -> "Hidden"
    Source.Trash -> "Recently Deleted"
    is Source.External -> item.name
}

fun buildAlbums(items: List<MediaItem>): List<Album> =
    items.groupBy { it.bucketId }
        .map { (id, list) ->
            Album(
                bucketId = id,
                name = list.first().bucketName.ifBlank { "Untitled" },
                count = list.size,
                cover = list.last(),
            )
        }
        .sortedWith(
            compareBy<Album> {
                when (it.name.lowercase()) {
                    "camera" -> 0
                    "screenshots" -> 1
                    else -> 2
                }
            }.thenByDescending { it.cover.dateTaken },
        )
