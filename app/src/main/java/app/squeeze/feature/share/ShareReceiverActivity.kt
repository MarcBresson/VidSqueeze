package app.squeeze.feature.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.squeeze.R
import app.squeeze.data.BatchSource
import app.squeeze.feature.compress.CompressionSheet
import app.squeeze.feature.compress.CompressionSheetViewModel
import app.squeeze.ui.theme.SqueezeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Receives `SEND` / `SEND_MULTIPLE` for video and shows the compression sheet over whatever app
 * the user came from. Start queues the videos; dismissing the sheet changes nothing.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    private val vm: CompressionSheetViewModel by viewModels()

    /** True once Start was pressed: the sheet is gone but the activity stays until the import is done. */
    private var starting by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }
        vm.loadFiles(uris)
        setContent {
            SqueezeTheme {
                if (!starting) {
                    CompressionSheet(vm = vm, onDismiss = ::finish, onStart = ::start)
                }
            }
        }
    }

    private fun start() {
        // The sender's read grants end with this activity, so it stays open (invisible) until the
        // import has copied what it must.
        starting = vm.start(BatchSource.SHARE) { result ->
            runOnUiThread {
                val message = if (result.isSuccess) R.string.share_queued else R.string.queue_import_failed
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(parcelable(intent, Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> parcelableList(intent, Intent.EXTRA_STREAM)
        else -> emptyList()
    }

    private fun parcelable(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(key)

    private fun parcelableList(intent: Intent, key: String): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(key, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(key)).orEmpty()
}
