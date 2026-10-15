package app.squeeze.finalize

import android.util.Log
import app.squeeze.automation.AutomationEvents
import app.squeeze.data.BatchStatus
import app.squeeze.data.FinishedBatch
import app.squeeze.data.ResultsRepository
import app.squeeze.engine.ExportMode
import app.squeeze.work.NotificationHelper
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * What happens when the last item of a batch is done: apply the batch's export mode when it can
 * be done unattended, and tell the user. Everything that needs a decision or an approval is left
 * to the results notification and the "Ready to use" list.
 */
@Singleton
class BatchCompletion @Inject constructor(
    private val results: ResultsRepository,
    private val finalizer: Finalizer,
    private val store: MediaStoreWriter,
    private val notifications: NotificationHelper,
    private val events: AutomationEvents,
) {
    suspend fun onBatchFinished(finished: FinishedBatch) {
        events.batchFinished(finished, results.batch(finished.batchId)?.source?.name)
        if (finished.status != BatchStatus.AWAITING_ACTION) {
            notifications.postResultIfAny(finished)
            return
        }
        val report = applyExportMode(finished.batchId)
        if (report == null) {
            notifications.postResultIfAny(finished)
        } else {
            notifications.postFinalized(finished.batchId, report, stillPending = results.pending(finished.batchId).isNotEmpty())
        }
    }

    private suspend fun applyExportMode(batchId: Long): FinalizeReport? = try {
        when (results.batch(batchId)?.exportMode) {
            ExportMode.SAVE_COPIES -> finalizer.saveCopies(batchId)
            // Replacing without a prompt is only allowed with "media management"; otherwise the user confirms.
            ExportMode.REPLACE_ORIGINALS -> if (store.canManageMedia()) finalizer.replace(batchId) else null
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Automatic finalize failed for batch $batchId", e)
        null
    }

    private companion object {
        const val TAG = "BatchCompletion"
    }
}
