package app.squeeze.feature.queue

import app.squeeze.data.AwaitingBatch
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.ItemStatus

data class QueueUi(
    val items: List<BatchItemEntity> = emptyList(),
    val workerActive: Boolean = false,
    /** Finished batches whose compressed files wait for replace / save / discard. */
    val ready: List<AwaitingBatch> = emptyList(),
) {
    /** Items are waiting but nothing will run them: the user paused. */
    val paused get() = items.isNotEmpty() && !workerActive

    /** The video being compressed right now, if any. */
    val running: BatchItemEntity? get() = items.firstOrNull { it.status == ItemStatus.RUNNING }
}
