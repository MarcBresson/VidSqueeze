package app.squeeze.finalize

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.squeeze.data.ResultsRepository
import app.squeeze.work.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Saves or replaces a batch's staged results in the background, under a foreground notification. */
@HiltWorker
class FinalizeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val finalizer: Finalizer,
    private val results: ResultsRepository,
    private val notifications: NotificationHelper,
) : CoroutineWorker(context, params) {

    private val batchId get() = inputData.getLong(KEY_BATCH, -1)
    private val action get() = FinalizeAction.valueOf(inputData.getString(KEY_ACTION) ?: FinalizeAction.SAVE.name)

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.finalizeForegroundInfo(action)

    override suspend fun doWork(): Result {
        notifications.createChannels()
        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not start the foreground service", e)
        }
        return try {
            val report = when (action) {
                FinalizeAction.REPLACE -> finalizer.replace(batchId)
                else -> finalizer.saveCopies(batchId)
            }
            notifications.postFinalized(batchId, report, stillPending = results.pending(batchId).isNotEmpty())
            Result.success(
                workDataOf(
                    KEY_ORIGINALS_TO_DELETE to report.originalsToDelete.take(MAX_URIS).toTypedArray(),
                    KEY_NEEDS_CONSENT to report.needsConsent,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Finalize failed for batch $batchId", e)
            Result.failure()
        }
    }

    companion object {
        const val KEY_BATCH = "batch"
        const val KEY_ACTION = "action"
        /** Output: URIs of originals that still have to be deleted with the user's approval. */
        const val KEY_ORIGINALS_TO_DELETE = "originals_to_delete"
        /** Output: how many originals refused the write because we had no approval. */
        const val KEY_NEEDS_CONSENT = "needs_consent"
        private const val MAX_URIS = 100
        private const val TAG = "FinalizeWorker"
    }
}
