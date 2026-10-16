package app.squeeze.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.squeeze.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val THUMBNAIL_SIZE = Size(256, 256)

/** Decoded thumbnails, sized by bytes (about 16 MB) so a long list never grows the heap. */
private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

private fun load(context: Context, video: VideoRef): Bitmap? = try {
    video.file?.let { ThumbnailUtils.createVideoThumbnail(it, THUMBNAIL_SIZE, null) }
        ?: context.contentResolver.loadThumbnail(video.uri, THUMBNAIL_SIZE, null)
} catch (_: Exception) {
    null
}

/** A rounded square frame of the video; a movie icon stands in while it loads or when the file is gone. */
@Composable
fun VideoThumbnail(video: VideoRef?, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = video?.let { cache.get(it.uri.toString()) }, video) {
        val ref = video ?: return@produceState
        val key = ref.uri.toString()
        value = cache.get(key) ?: withContext(Dispatchers.IO) { load(context, ref) }?.also { cache.put(key, it) }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val frame = bitmap
        if (frame != null) {
            Image(frame.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Opens the video in the default player. */
@Composable
fun PlayButton(video: VideoRef, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    IconButton(onClick = { context.playVideo(video) }, modifier = modifier.size(36.dp)) {
        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play_video), tint = MaterialTheme.colorScheme.primary)
    }
}
