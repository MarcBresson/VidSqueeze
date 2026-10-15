package app.squeeze.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchSummaryTest {
    private fun item(status: ItemStatus, original: Long = 0, output: Long? = null) = BatchItemEntity(
        batchId = 1, position = 1, sourceUri = "content://x", displayName = "a.mp4",
        originalSize = original, outputSize = output, status = status,
    )

    @Test
    fun `summary counts only compressed items towards savings`() {
        val summary = BatchSummary.of(
            listOf(
                item(ItemStatus.DONE, 1000, 400),
                item(ItemStatus.DONE, 3000, 1600),
                item(ItemStatus.SKIPPED_NO_GAIN, 500, 600),
                item(ItemStatus.FAILED),
                item(ItemStatus.CANCELLED),
            ),
        )
        assertEquals(2, summary.done)
        assertEquals(1, summary.skippedNoGain)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.cancelled)
        assertEquals(4000, summary.originalBytes)
        assertEquals(2000, summary.outputBytes)
        assertEquals(2000, summary.savedBytes)
        assertEquals(50, summary.savedPercent)
    }

    @Test
    fun `empty summary saves nothing`() {
        val summary = BatchSummary.of(emptyList())
        assertEquals(0, summary.savedBytes)
        assertEquals(0, summary.savedPercent)
    }

    @Test
    fun `batch stays running while items are active`() {
        assertEquals(BatchStatus.RUNNING, BatchSummary.statusFor(listOf(ItemStatus.DONE, ItemStatus.QUEUED)))
        assertEquals(BatchStatus.RUNNING, BatchSummary.statusFor(listOf(ItemStatus.RUNNING)))
    }

    @Test
    fun `batch with a result waits for the user even if something failed`() {
        assertEquals(BatchStatus.AWAITING_ACTION, BatchSummary.statusFor(listOf(ItemStatus.DONE, ItemStatus.FAILED)))
        assertEquals(BatchStatus.AWAITING_ACTION, BatchSummary.statusFor(listOf(ItemStatus.DONE, ItemStatus.CANCELLED)))
    }

    @Test
    fun `batch without results is cancelled partial or done`() {
        assertEquals(BatchStatus.CANCELLED, BatchSummary.statusFor(listOf(ItemStatus.CANCELLED, ItemStatus.CANCELLED)))
        assertEquals(BatchStatus.PARTIAL, BatchSummary.statusFor(listOf(ItemStatus.FAILED, ItemStatus.CANCELLED)))
        assertEquals(BatchStatus.DONE, BatchSummary.statusFor(listOf(ItemStatus.SKIPPED_NO_GAIN)))
    }
}
