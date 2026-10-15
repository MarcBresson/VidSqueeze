package app.squeeze.finalize

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import app.squeeze.R
import app.squeeze.ui.theme.SqueezeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Invisible trampoline for the results actions. Reshare opens the share sheet; Replace first asks
 * the system for permission to change the originals (one dialog for the whole batch, none when the
 * user allowed media management), runs the replace, and asks once more when originals have to be
 * deleted because the container changed.
 */
@AndroidEntryPoint
class FinalizeActivity : ComponentActivity() {
    @Inject lateinit var finalizer: Finalizer
    @Inject lateinit var manager: FinalizeManager
    @Inject lateinit var store: MediaStoreWriter
    @Inject lateinit var workManager: WorkManager

    private var batchId = -1L
    private var working by mutableStateOf(false)

    /** Set once a replace was refused for lack of approval, so we ask the user instead of trusting the checks that said yes. */
    private var askedAfterRefusal = false

    private val writeConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) runReplace() else toastAndFinish(R.string.finalize_cancelled)
    }
    private val deleteConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        toastAndFinish(if (it.resultCode == RESULT_OK) R.string.finalize_originals_removed else R.string.finalize_originals_kept)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        batchId = intent.getLongExtra(EXTRA_BATCH, -1)
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { runCatching { FinalizeAction.valueOf(it) }.getOrNull() }
        if (batchId < 0 || action == null) {
            finish()
            return
        }
        setContent {
            SqueezeTheme {
                if (working) {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                                Text(stringResource(R.string.finalize_working_replace))
                            }
                        },
                    )
                }
            }
        }
        when (action) {
            FinalizeAction.SHARE -> share()
            FinalizeAction.SAVE -> {
                manager.save(batchId)
                finish()
            }
            FinalizeAction.REPLACE -> askThenReplace()
        }
    }

    private fun share() {
        lifecycleScope.launch {
            val chooser = finalizer.shareIntent(batchId)
            if (chooser == null) {
                toastAndFinish(R.string.finalize_nothing)
            } else {
                startActivity(chooser)
                finish()
            }
        }
    }

    private fun askThenReplace(trusted: Boolean = true) {
        lifecycleScope.launch {
            try {
                val needed = finalizer.needsWriteConsent(batchId, trusted)
                Log.d(TAG, "batch $batchId: ${needed.size} originals need approval (trusted=$trusted)")
                if (needed.isEmpty()) runReplace() else writeConsent.launch(IntentSenderRequest.Builder(store.writeRequest(needed)).build())
            } catch (e: Exception) {
                Log.e(TAG, "Could not ask for write access", e)
                toastAndFinish(R.string.finalize_failed)
            }
        }
    }

    private fun runReplace() {
        working = true
        lifecycleScope.launch {
            val id = manager.replace(batchId)
            val info = workManager.getWorkInfoByIdFlow(id).first { it?.state?.isFinished == true }
            val toDelete = info?.outputData?.getStringArray(FinalizeWorker.KEY_ORIGINALS_TO_DELETE).orEmpty().map(Uri::parse)
            working = false
            val refused = info?.outputData?.getInt(FinalizeWorker.KEY_NEEDS_CONSENT, 0) ?: 0
            if (refused > 0 && !askedAfterRefusal) {
                // The write was refused although we thought we had access (for example media
                // management does not cover this file): ask the user for these originals.
                askedAfterRefusal = true
                askThenReplace(trusted = false)
                return@launch
            }
            if (toDelete.isEmpty()) {
                finish()
                return@launch
            }
            try {
                deleteConsent.launch(IntentSenderRequest.Builder(store.deleteRequest(toDelete)).build())
            } catch (e: Exception) {
                Log.e(TAG, "Could not ask to delete the originals", e)
                toastAndFinish(R.string.finalize_originals_kept)
            }
        }
    }

    private fun toastAndFinish(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val EXTRA_BATCH = "batch"
        private const val EXTRA_ACTION = "action"
        private const val TAG = "FinalizeActivity"

        fun intent(context: Context, batchId: Long, action: FinalizeAction) =
            Intent(context, FinalizeActivity::class.java)
                .putExtra(EXTRA_BATCH, batchId)
                .putExtra(EXTRA_ACTION, action.name)
    }
}
