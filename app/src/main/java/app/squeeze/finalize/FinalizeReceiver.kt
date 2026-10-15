package app.squeeze.finalize

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** "Save alongside" on the results notification. Not exported. */
@AndroidEntryPoint
class FinalizeReceiver : BroadcastReceiver() {
    @Inject lateinit var manager: FinalizeManager

    override fun onReceive(context: Context, intent: Intent) {
        val batchId = intent.getLongExtra(EXTRA_BATCH, -1)
        if (batchId < 0) return
        if (FinalizeAction.valueOf(intent.getStringExtra(EXTRA_ACTION) ?: return) == FinalizeAction.SAVE) manager.save(batchId)
    }

    companion object {
        private const val EXTRA_BATCH = "batch"
        private const val EXTRA_ACTION = "action"

        fun intent(context: Context, batchId: Long, action: FinalizeAction) =
            Intent(context, FinalizeReceiver::class.java)
                .setAction("app.squeeze.action.FINALIZE_${action.name}")
                .putExtra(EXTRA_BATCH, batchId)
                .putExtra(EXTRA_ACTION, action.name)
    }
}
