package app.squeeze.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns URIs handed to us (share intent, picker) into sources that stay readable until the queue
 * gets to them. Temporary grants die with the sending task or our process, so anything that is not
 * a real MediaStore video we may read is copied into app storage first ([QueuedSource.isImported]).
 */
@Singleton
class SourceImporter @Inject constructor(@ApplicationContext private val context: Context) {

    private val importDir get() = File(context.filesDir, "imports")

    suspend fun resolve(uris: List<Uri>): List<QueuedSource> = withContext(Dispatchers.IO) {
        uris.map { resolveOne(it) }
    }

    private fun resolveOne(uri: Uri): QueuedSource {
        val (name, size) = describe(uri)
        val mediaId = mediaStoreVideoId(uri)
        if (mediaId != null && canReadMedia()) {
            return QueuedSource(uri.toString(), mediaId, isImported = false, displayName = name, size = size)
        }
        val copy = copyToImports(uri, name)
        return QueuedSource(Uri.fromFile(copy).toString(), null, isImported = true, displayName = name, size = copy.length())
    }

    private fun canReadMedia() =
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

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
        /** The id of a `content://media/<volume>/video/media/<id>` URI, or null for any other URI. */
        fun mediaStoreVideoId(uri: Uri): Long? {
            if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return null
            val segments = uri.pathSegments
            // [volume, "video", "media", id]; photo-picker URIs ("picker/...") don't match.
            if (segments.size != 4 || segments[1] != "video" || segments[2] != "media") return null
            return segments[3].toLongOrNull()
        }

        private fun String.sanitised() = replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
    }
}
