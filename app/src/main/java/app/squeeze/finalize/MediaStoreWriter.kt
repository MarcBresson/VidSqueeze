package app.squeeze.finalize

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What we need to know about an existing video to put its replacement in the same place. */
data class OriginalInfo(val relativePath: String?, val dateTakenMs: Long?)

/**
 * The only class that writes videos into MediaStore: new rows (hidden with `IS_PENDING` until the
 * bytes are in), overwriting an existing row, and deleting one.
 */
@Singleton
class MediaStoreWriter @Inject constructor(@ApplicationContext private val context: Context) {
    private val resolver = context.contentResolver
    private val collection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** True when the user allowed "media management", which lifts the per-file consent dialogs. */
    fun canManageMedia(): Boolean = MediaStore.canManageMedia(context)

    fun itemUri(mediaStoreId: Long): Uri = ContentUris.withAppendedId(collection, mediaStoreId)

    /**
     * Whether we may already write [uri] without asking the user: media management, an earlier
     * grant, or our own file. These checks can say yes while the actual write is still refused
     * (MediaProvider's answer differs for files that no app owns), so [trusted] false skips them
     * and only accepts our own files; use it after a write was refused.
     */
    fun hasWriteAccess(uri: Uri, trusted: Boolean = true): Boolean =
        isOwnedByUs(uri) || (
            trusted && (
                canManageMedia() ||
                    context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
                )
            )

    private fun isOwnedByUs(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use { c ->
            c.moveToFirst() && c.getString(0) == context.packageName
        } ?: false

    /** Consent dialog that grants write access to all [uris] at once. */
    fun writeRequest(uris: Collection<Uri>): IntentSender = MediaStore.createWriteRequest(resolver, uris).intentSender

    /** Consent dialog that deletes all [uris] once approved. */
    fun deleteRequest(uris: Collection<Uri>): IntentSender = MediaStore.createDeleteRequest(resolver, uris).intentSender

    fun originalInfo(mediaStoreId: Long): OriginalInfo? =
        resolver.query(
            itemUri(mediaStoreId),
            arrayOf(MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.DATE_TAKEN),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            OriginalInfo(c.getString(0), if (c.isNull(1)) null else c.getLong(1))
        }

    /**
     * Adds [source] to the gallery as a new video. It goes to [relativePath] (falling back to
     * `Movies/Squeeze` where that folder is not allowed for videos) under [displayName], made
     * unique if taken. Nothing becomes visible until all bytes are written.
     *
     * [dateTakenMs] is set while the row is hidden, but MediaStore re-reads the date from the file
     * when it is published (and cannot be changed afterwards), so the date really comes from the
     * file's own `creation_time`, which "keep metadata" preserves.
     */
    fun createCopy(source: File, displayName: String, relativePath: String?, mimeType: String, dateTakenMs: Long?): Uri {
        val preferred = normalisePath(relativePath ?: FALLBACK_PATH)
        val (uri, _) = try {
            insertPending(displayName, preferred, mimeType, dateTakenMs)
        } catch (e: IllegalArgumentException) {
            // The folder is not allowed for videos (Pictures/, Download/ ...).
            insertPending(displayName, FALLBACK_PATH, mimeType, dateTakenMs)
        }
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot write $uri")
            out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun insertPending(displayName: String, path: String, mimeType: String, dateTakenMs: Long?): Pair<Uri, String> {
        val name = NameTemplate.uniqueName(displayName) { exists(it, path) }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, path)
            put(MediaStore.Video.Media.IS_PENDING, 1)
            dateTakenMs?.let { put(MediaStore.Video.Media.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $name")
        return uri to name
    }

    private fun exists(name: String, path: String): Boolean =
        resolver.query(
            collection, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?",
            arrayOf(name, path), null,
        )?.use { it.count > 0 } ?: false

    /**
     * Replaces the bytes of [uri] with [source]. Throws [SecurityException] before touching anything
     * when we have no write access, and [IOException] when the copy fails (the original may then be
     * damaged: keep [source], the caller can run this again).
     */
    fun overwrite(uri: Uri, source: File) {
        val out = openForOverwrite(uri)
        out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
        val written = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (written != source.length()) throw IOException("Wrote $written bytes instead of ${source.length()}")
    }

    /**
     * Opens [uri] for writing, truncating it. A user grant is tied to the plain URI, media
     * management to its `requireOriginal` variant, so try the plain one first.
     */
    private fun openForOverwrite(uri: Uri): OutputStream {
        try {
            return resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
        } catch (e: SecurityException) {
            if (!canManageMedia()) throw e
        }
        return resolver.openOutputStream(MediaStore.setRequireOriginal(uri), "wt") ?: throw IOException("Cannot write $uri")
    }

    /** Deletes [uri] when we may do so silently. Returns false when the user's approval is needed. */
    fun deleteQuietly(uri: Uri): Boolean = try {
        resolver.delete(uri, null, null) > 0
    } catch (e: SecurityException) {
        if (!canManageMedia()) {
            false
        } else try {
            resolver.delete(MediaStore.setRequireOriginal(uri), null, null) > 0
        } catch (e: SecurityException) {
            false
        }
    }

    private fun normalisePath(path: String) = if (path.endsWith("/")) path else "$path/"

    companion object {
        const val FALLBACK_PATH = "Movies/Squeeze/"
    }
}
