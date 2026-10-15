package app.squeeze.finalize

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.squeeze.di.ApplicationScope
import app.squeeze.work.NotificationHelper
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the UI, the notification and the consent activity use to act on a batch's results. */
@Singleton
class FinalizeManager @Inject constructor(
    private val workManager: WorkManager,
    private val finalizer: Finalizer,
    private val notifications: NotificationHelper,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Saves the results next to the originals in the background. Returns the work id. */
    fun save(batchId: Long): UUID = enqueue(batchId, FinalizeAction.SAVE)

    /** Replaces the originals in the background. The caller must have the user's approval (or media management) first. */
    fun replace(batchId: Long): UUID = enqueue(batchId, FinalizeAction.REPLACE)

    fun discard(batchId: Long) {
        scope.launch {
            finalizer.discard(batchId)
            notifications.cancelResult(batchId)
        }
    }

    private fun enqueue(batchId: Long, action: FinalizeAction): UUID {
        val request = OneTimeWorkRequestBuilder<FinalizeWorker>()
            .setInputData(workDataOf(FinalizeWorker.KEY_BATCH to batchId, FinalizeWorker.KEY_ACTION to action.name))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork("finalize-$batchId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return request.id
    }
}
