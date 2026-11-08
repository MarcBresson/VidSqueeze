package app.squeeze.feature.history

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.squeeze.data.BatchItemEntity
import app.squeeze.engine.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The read-only source checks [MissingSourceChecker] needs; faked in JVM tests. */
interface SourceProbe {
    fun mediaStoreExists(mediaStoreId: Long, kind: MediaKind): Boolean
    fun contentUriExists(uri: String): Boolean
    fun fileExists(path: String): Boolean
}

/** Detects media whose ORIGINAL file was deleted or moved since compression. Best-effort. */
class MissingSourceChecker(private val probe: SourceProbe) {
    /** True when the item's ORIGINAL can no longer be found at its recorded location. */
    fun isMissing(item: BatchItemEntity): Boolean = try {
        when {
            // A moved item keeps its MediaStore id, so only a gone id counts as missing.
            item.mediaStoreId != null -> !probe.mediaStoreExists(item.mediaStoreId, item.kind)
            item.sourceUri.startsWith("file:") -> !probe.fileExists(item.sourceUri.removePrefix("file://"))
            // Covers isImported copies (file paths) and any other non-content source.
            item.sourceUri.startsWith("content:") -> !probe.contentUriExists(item.sourceUri)
            else -> !probe.fileExists(item.sourceUri)
        }
    } catch (_: Exception) {
        true // unreadable source: treat as missing
    }
}

/** The real probes: synchronous MediaStore / file / content-resolver checks, called from Dispatchers.IO. */
@Singleton
class RealSourceProbe @Inject constructor(@ApplicationContext private val context: Context) : SourceProbe {
    override fun mediaStoreExists(mediaStoreId: Long, kind: MediaKind): Boolean {
        val collection = when (kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(mediaStoreId.toString()),
            null,
        )?.use { cursor -> if (cursor.count > 0) return true }
        return false
    }

    override fun contentUriExists(uri: String): Boolean {
        context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { cursor ->
            if (cursor.count > 0) return true
        }
        return false
    }

    override fun fileExists(path: String): Boolean = File(path).isFile
}
