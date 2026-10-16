package app.squeeze.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import app.squeeze.data.QueueRepository
import app.squeeze.finalize.MediaStoreWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the Home screen proposes: the heaviest videos Squeeze has not dealt with yet. */
data class Suggestions(val videos: List<VideoInfo>, val uris: List<Uri>) {
    val totalBytes: Long get() = videos.sumOf { it.sizeBytes }
}

@Singleton
class SuggestionSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: GalleryScanner,
    private val queue: QueueRepository,
    private val store: MediaStoreWriter,
) {
    fun hasAccess() = context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    /** Null while the app may not read the library. */
    suspend fun heavyVideos(limit: Int = LIMIT): Suggestions? {
        if (!hasAccess()) return null
        val videos = HeavyVideoSelector.select(scanner.scan(), queue.skipIds(), limit = limit)
        return Suggestions(videos, videos.map { store.itemUri(it.id) })
    }

    private companion object {
        const val LIMIT = 10
    }
}
