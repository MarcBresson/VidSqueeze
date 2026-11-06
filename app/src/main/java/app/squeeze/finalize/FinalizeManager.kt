package app.squeeze.finalize

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.squeeze.data.ResultsRepository
import app.squeeze.di.ApplicationScope
import app.squeeze.work.NotificationHelper
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the UI, the notification and the consent activity use to act on results. */
@Singleton
class FinalizeManager @Inject constructor(
    private val workManager: WorkManager,
    private val finalizer: Finalizer,
    private val results: ResultsRepository,
    private val notifications: NotificationHelper,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Saves the results next to the originals in the background. Returns the work id. */
    fun save(batchId: Long): UUID = enqueue(batchId, null, FinalizeAction.SAVE)

    /** Replaces the originals in the background. The caller must have the user's approval (or media management) first. */
    fun replace(batchId: Long): UUID = enqueue(batchId, null, FinalizeAction.REPLACE)

    /** Saves one item's result as a copy in the background. Returns the work id. */
    fun saveItem(itemId: Long): UUID = enqueue(null, itemId, FinalizeAction.SAVE)

    /** Replaces one item's original in the background; the caller must have approval first. */
    fun replaceItem(itemId: Long): UUID = enqueue(null, itemId, FinalizeAction.REPLACE)

    fun discard(batchId: Long) {
        scope.launch {
            finalizer.discard(batchId)
            notifications.cancelResult(batchId)
        }
    }

    /** Throws one item's pending result away. */
    fun discardItem(itemId: Long) {
        scope.launch {
            finalizer.discardItem(itemId)
            val batchId = results.item(itemId)?.batchId ?: return@launch
            if (results.pending(batchId).isEmpty()) notifications.cancelResult(batchId)
        }
    }

    private fun enqueue(batchId: Long?, itemId: Long?, action: FinalizeAction): UUID {
        val data = when {
            batchId != null -> workDataOf(FinalizeWorker.KEY_BATCH to batchId, FinalizeWorker.KEY_ACTION to action.name)
            else -> workDataOf(FinalizeWorker.KEY_ITEM to requireNotNull(itemId), FinalizeWorker.KEY_ACTION to action.name)
        }
        val request = OneTimeWorkRequestBuilder<FinalizeWorker>()
            .setInputData(data)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        val name = if (batchId != null) "finalize-batch-$batchId" else "finalize-item-$itemId"
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return request.id
    }
}
