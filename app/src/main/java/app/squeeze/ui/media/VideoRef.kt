package app.squeeze.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import app.squeeze.R
import app.squeeze.engine.MediaKind
import java.io.File

/**
 * A piece of media the UI can show a thumbnail of and open: a content [uri], plus the [file]
 * behind it when it is one of ours, and whether it is a photo ([MediaKind.IMAGE]) or a video.
 */
data class VideoRef(val uri: Uri, val file: File? = null, val kind: MediaKind = MediaKind.VIDEO) {
    companion object {
        /** A staged result, exposed to other apps through the app's FileProvider. */
        fun staged(context: Context, file: File, kind: MediaKind = MediaKind.VIDEO): VideoRef =
            VideoRef(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), file, kind)
    }
}

/** Opens [media] in the user's default viewer, as a video or a photo. */
fun Context.playVideo(media: VideoRef) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(media.uri, if (media.kind == MediaKind.IMAGE) "image/*" else "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.play_no_player, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, R.string.play_failed, Toast.LENGTH_SHORT).show()
    }
}
