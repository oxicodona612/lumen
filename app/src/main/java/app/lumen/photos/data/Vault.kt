package app.lumen.photos.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Hidden photos live in app-private internal storage. Nothing outside Lumen —
 * other galleries, file managers, MediaStore queries — can see that directory.
 */
class Vault(private val context: Context) {

    data class Entry(
        val file: String,
        val name: String,
        val mime: String,
        val relativePath: String,
        val dateTaken: Long,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val sizeBytes: Long,
        val hiddenAt: Long,
    )

    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val lock = Mutex()
    private val _entries = MutableStateFlow(readIndex())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    init {
        // Leftovers from a hide that died mid-copy.
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun fileOf(entry: Entry) = File(dir, entry.file)

    fun toItem(entry: Entry): MediaItem {
        val file = fileOf(entry)
        return MediaItem(
            key = "v:${entry.file}",
            id = entry.hiddenAt,
            uri = Uri.fromFile(file),
            origin = Origin.Vault,
            isVideo = entry.mime.startsWith("video/"),
            mime = entry.mime,
            name = entry.name,
            dateTaken = entry.dateTaken,
            width = entry.width,
            height = entry.height,
            durationMs = entry.durationMs,
            sizeBytes = entry.sizeBytes,
            bucketId = 0,
            bucketName = "Hidden",
            relativePath = entry.relativePath,
            isFavorite = false,
            file = file,
        )
    }

    fun entryFor(item: MediaItem): Entry? =
        _entries.value.firstOrNull { "v:${it.file}" == item.key }

    /** Copies the original into the vault. Not visible in the Hidden album until [commit]. */
    suspend fun stage(item: MediaItem): Entry = withContext(Dispatchers.IO) {
        val ext = item.name.substringAfterLast('.', "").ifEmpty {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mime) ?: "bin"
        }.lowercase()
        val fileName = "${UUID.randomUUID()}.$ext"
        val partial = File(dir, "$fileName.part")
        val input = context.contentResolver.openInputStream(item.uri)
            ?: error("Can't open ${item.uri}")
        input.use { src -> partial.outputStream().use { src.copyTo(it, 256 * 1024) } }
        check(partial.length() > 0) { "Empty copy for ${item.name}" }
        check(partial.renameTo(File(dir, fileName))) { "Rename failed for ${item.name}" }
        Entry(
            file = fileName,
            name = item.name,
            mime = item.mime,
            relativePath = item.relativePath,
            dateTaken = item.dateTaken,
            width = item.width,
            height = item.height,
            durationMs = item.durationMs,
            sizeBytes = item.sizeBytes,
            hiddenAt = System.currentTimeMillis(),
        )
    }

    suspend fun commit(staged: List<Entry>) = lock.withLock {
        _entries.value = _entries.value + staged
        writeIndex(_entries.value)
    }

    suspend fun discard(staged: List<Entry>) = withContext(Dispatchers.IO) {
        staged.forEach { fileOf(it).delete() }
    }

    /** Puts entries back into the shared library at their original folder. Returns how many made it. */
    suspend fun restore(targets: List<Entry>): Int = withContext(Dispatchers.IO) {
        val restored = targets.filter { publish(it) }
        lock.withLock {
            val gone = restored.map { it.file }.toSet()
            _entries.value = _entries.value.filterNot { it.file in gone }
            writeIndex(_entries.value)
        }
        restored.forEach { fileOf(it).delete() }
        restored.size
    }

    suspend fun delete(targets: List<Entry>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val gone = targets.map { it.file }.toSet()
            _entries.value = _entries.value.filterNot { it.file in gone }
            writeIndex(_entries.value)
        }
        targets.forEach { fileOf(it).delete() }
    }

    private fun publish(entry: Entry): Boolean {
        val resolver = context.contentResolver
        val isVideo = entry.mime.startsWith("video/")
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        // MediaStore rejects some original folders for some types (e.g. Download/ for images).
        val candidates = listOf(entry.relativePath, if (isVideo) "Movies/Lumen/" else "Pictures/Lumen/")
            .filter { it.isNotBlank() }
            .distinct()
        for (path in candidates) {
            val values = ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, entry.name)
                put(MediaColumns.MIME_TYPE, entry.mime)
                put(MediaColumns.RELATIVE_PATH, path)
                put(MediaColumns.IS_PENDING, 1)
                if (entry.dateTaken > 0) put(MediaColumns.DATE_TAKEN, entry.dateTaken)
            }
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: continue
            val copied = runCatching {
                val out = resolver.openOutputStream(uri) ?: error("No output stream")
                out.use { dst -> fileOf(entry).inputStream().use { it.copyTo(dst, 256 * 1024) } }
                // The publish-time scan rewrites DATE_TAKEN from EXIF (null for EXIF-less files)
                // and DATE_MODIFIED from mtime. Restoring mtime first keeps the photo in its spot.
                if (entry.dateTaken > 0) restoreMtime(uri, entry.dateTaken)
                resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
                if (entry.dateTaken > 0) {
                    runCatching {
                        resolver.update(uri, ContentValues().apply { put(MediaColumns.DATE_TAKEN, entry.dateTaken) }, null, null)
                    }
                }
            }.isSuccess
            if (copied) return true
            runCatching { resolver.delete(uri, null, null) }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun restoreMtime(uri: Uri, millis: Long) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { File(it).setLastModified(millis) }
            }
        }
    }

    private fun readIndex(): List<Entry> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    file = o.getString("file"),
                    name = o.optString("name"),
                    mime = o.optString("mime", "image/jpeg"),
                    relativePath = o.optString("path"),
                    dateTaken = o.optLong("taken"),
                    width = o.optInt("w"),
                    height = o.optInt("h"),
                    durationMs = o.optLong("dur"),
                    sizeBytes = o.optLong("size"),
                    hiddenAt = o.optLong("hiddenAt"),
                )
            }.filter { File(dir, it.file).exists() }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("file", e.file)
                    .put("name", e.name)
                    .put("mime", e.mime)
                    .put("path", e.relativePath)
                    .put("taken", e.dateTaken)
                    .put("w", e.width)
                    .put("h", e.height)
                    .put("dur", e.durationMs)
                    .put("size", e.sizeBytes)
                    .put("hiddenAt", e.hiddenAt),
            )
        }
        // Write-then-rename so a crash never leaves a truncated index.
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile)
    }
}
