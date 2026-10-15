package app.squeeze.data

import app.squeeze.finalize.FinalizePlanner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A finished batch whose compressed files still wait for "replace", "save" or "discard". */
data class AwaitingBatch(val batch: BatchEntity, val items: List<BatchItemEntity>) {
    val summary: BatchSummary = BatchSummary.of(items)
}

/** Bookkeeping for what happens after compression: staged results and their final state. */
@Singleton
class ResultsRepository @Inject constructor(
    private val batches: BatchDao,
    private val items: BatchItemDao,
) {
    fun observeAwaiting(): Flow<List<AwaitingBatch>> =
        combine(batches.observeAwaiting(), items.observePendingResults()) { awaiting, pending ->
            val byBatch = pending.groupBy { it.batchId }
            awaiting.mapNotNull { batch -> byBatch[batch.id]?.let { AwaitingBatch(batch, it) } }
        }

    suspend fun batch(batchId: Long): BatchEntity? = batches.get(batchId)

    suspend fun items(batchId: Long): List<BatchItemEntity> = items.forBatch(batchId)

    /** Results of [batchId] that still wait for a decision. */
    suspend fun pending(batchId: Long): List<BatchItemEntity> = FinalizePlanner.pending(items.forBatch(batchId))

    /** Records the outcome for [item]; the staged file is gone from here on, so the path is cleared. */
    suspend fun markFinalized(item: BatchItemEntity, state: FinalizeState, finalUri: String?) {
        items.update(item.copy(finalizeState = state, finalUri = finalUri, stagedPath = null))
        refreshStatus(item.batchId)
    }

    /** A batch with nothing left to decide is done. */
    suspend fun refreshStatus(batchId: Long) {
        val batch = batches.get(batchId) ?: return
        if (batch.status == BatchStatus.AWAITING_ACTION && pending(batchId).isEmpty()) {
            batches.setStatus(batchId, BatchStatus.DONE)
        }
    }

    /** Batches that have waited since before [cutoff]. */
    suspend fun awaitingBefore(cutoff: Long): List<BatchEntity> = batches.awaitingBefore(cutoff)
}
