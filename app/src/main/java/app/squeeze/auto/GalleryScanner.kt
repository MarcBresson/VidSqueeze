package app.squeeze.auto

import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lists the videos MediaStore knows about. Needs `READ_MEDIA_VIDEO`; returns nothing without it. */
@Singleton
class GalleryScanner @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun scan(): List<VideoInfo> = withContext(Dispatchers.IO) {
        val out = mutableListOf<VideoInfo>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED,
        )
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), projection, null, null, "${MediaStore.Video.Media._ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += VideoInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "video",
                        relativePath = c.getString(2).orEmpty(),
                        sizeBytes = if (c.isNull(3)) 0 else c.getLong(3),
                        durationMs = if (c.isNull(4)) 0 else c.getLong(4),
                        width = if (c.isNull(5)) 0 else c.getInt(5),
                        height = if (c.isNull(6)) 0 else c.getInt(6),
                        dateTakenMs = if (c.isNull(7)) null else c.getLong(7).takeIf { it > 0 },
                        dateAddedMs = if (c.isNull(8)) null else c.getLong(8).takeIf { it > 0 }?.times(1000),
                    )
                }
            }
        } catch (e: SecurityException) {
            // No media permission: nothing to list.
        }
        out
    }
}
