package app.squeeze.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.squeeze.engine.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns URIs handed to us (share intent, picker) into sources that stay readable until the queue
 * gets to them. Temporary grants die with the sending task or our process, so anything that is not
 * a real MediaStore row we may read is copied into app storage first ([QueuedSource.isImported]).
 */
@Singleton
class SourceImporter @Inject constructor(@ApplicationContext private val context: Context) {

    private val importDir get() = File(context.filesDir, "imports")

    suspend fun resolve(uris: List<Uri>): List<QueuedSource> = withContext(Dispatchers.IO) {
        uris.map { resolveOne(it) }
    }

    private fun resolveOne(uri: Uri): QueuedSource {
        val (name, size) = describe(uri)
        val store = mediaStoreId(uri)
        val kind = store?.second ?: kindFromExtension(name)
        val mediaId = store?.first
        if (mediaId != null && canReadMedia(kind)) {
            return QueuedSource(uri.toString(), mediaId, isImported = false, displayName = name, size = size, kind = kind)
        }
        val copy = copyToImports(uri, name)
        return QueuedSource(Uri.fromFile(copy).toString(), null, isImported = true, displayName = name, size = copy.length(), kind = kind)
    }

    private fun canReadMedia(kind: MediaKind) =
        context.checkSelfPermission(when (kind) {
            MediaKind.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
            else -> Manifest.permission.READ_MEDIA_VIDEO
        }) == PackageManager.PERMISSION_GRANTED

    private fun copyToImports(uri: Uri, name: String): File {
        importDir.mkdirs()
        val target = File(importDir, "${System.nanoTime()}-${name.sanitised()}")
        try {
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open $uri" }
            input.use { src -> target.outputStream().use { src.copyTo(it) } }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return target
    }

    /** Display name and size in bytes (0 when unknown) of a content or file URI. */
    fun describe(uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: uri.lastPathSegment ?: "video"
                return name to (if (c.isNull(1)) 0L else c.getLong(1))
            }
        }
        return (uri.lastPathSegment ?: "video") to 0L
    }

    companion object {
        /** The id and kind of a `content://media/<volume>/video|image/media/<id>` URI, or null for any other URI. */
        fun mediaStoreId(uri: Uri): Pair<Long, MediaKind>? = mediaStoreId(uri.toString())

        /** String form of [mediaStoreId]; plain JVM tests can exercise it without Android's Uri. */
        fun mediaStoreId(uri: String): Pair<Long, MediaKind>? {
            val s = uri.split('/').filter { it.isNotEmpty() }
            // content://media/<volume>/"video"|"image"/"media"/<id>; photo-picker URIs don't match.
            if (s.size != 6 || s[0] != "content:" || s[1] != "media" || s[4] != "media") return null
            val id = s[5].toLongOrNull() ?: return null
            return when (s[3]) {
                "video" -> id to MediaKind.VIDEO
                "image" -> id to MediaKind.IMAGE
                else -> null
            }
        }

        /** File-name based kind for URIs that are not MediaStore rows (shares, imports). */
        fun kindFromExtension(name: String): MediaKind =
            if (name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) MediaKind.IMAGE else MediaKind.VIDEO

        private fun String.sanitised() = replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif", "jxl", "heic", "heif", "gif", "bmp", "tiff", "tif")
    }
}
