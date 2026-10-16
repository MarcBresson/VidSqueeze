package app.squeeze.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import app.squeeze.R
import java.io.File

/** A video the UI can show a thumbnail of and play: a content [uri], plus the [file] behind it when it is one of ours. */
data class VideoRef(val uri: Uri, val file: File? = null) {
    companion object {
        /** A staged result, exposed to the player through the app's FileProvider. */
        fun staged(context: Context, file: File): VideoRef =
            VideoRef(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), file)
    }
}

/** Opens [video] in the user's default video player. */
fun Context.playVideo(video: VideoRef) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(video.uri, "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.play_no_player, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, R.string.play_failed, Toast.LENGTH_SHORT).show()
    }
}
