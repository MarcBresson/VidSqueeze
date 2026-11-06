package app.squeeze.feature.history

import app.squeeze.data.BatchItemEntity
import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The gate for the Play-original button: finished, and not an item whose replace deleted its original. */
class CanPlayOriginalTest {
    private fun item(status: ItemStatus, state: FinalizeState = FinalizeState.PENDING) = BatchItemEntity(
        batchId = 1, position = 1, sourceUri = "content://media/external/video/media/9", displayName = "v.mp4",
        originalSize = 1, status = status, finalizeState = state,
    )

    @Test
    fun anyFinishedStatusAnyFinalizeStateExceptReplaced() {
        val finished = ItemStatus.entries.filter { it != ItemStatus.QUEUED && it != ItemStatus.RUNNING }
        finished.forEach { status ->
            FinalizeState.entries.forEach { state ->
                assertEquals("status=$status state=$state", state != FinalizeState.REPLACED, canPlayOriginal(item(status, state)))
            }
        }
    }

    @Test
    fun queuedAndRunningNever() {
        assertFalse(canPlayOriginal(item(ItemStatus.QUEUED)))
        assertFalse(canPlayOriginal(item(ItemStatus.RUNNING)))
    }
}
