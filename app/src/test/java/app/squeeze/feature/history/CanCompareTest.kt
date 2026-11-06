package app.squeeze.feature.history

import app.squeeze.data.BatchItemEntity
import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gate for the Compare button: saved library copies, and results still waiting for a decision. */
class CanCompareTest {
    private fun item(
        status: ItemStatus = ItemStatus.DONE,
        state: FinalizeState = FinalizeState.SAVED,
        finalUri: String? = "content://media/external/video/media/8",
        stagedPath: String? = null,
        mediaStoreId: Long? = 9,
        isImported: Boolean = false,
    ) = BatchItemEntity(
        batchId = 1, position = 1, sourceUri = "content://media/external/video/media/9", displayName = "v.mp4",
        originalSize = 1, status = status, finalizeState = state, finalUri = finalUri, stagedPath = stagedPath,
        mediaStoreId = mediaStoreId, isImported = isImported,
    )

    @Test
    fun savedLibraryCopy() {
        assertTrue(canCompare(item()))
    }

    @Test
    fun savedCopyNeedsFinalUriAndLibraryOriginal() {
        assertFalse(canCompare(item(finalUri = null)))
        assertFalse(canCompare(item(mediaStoreId = null)))
        assertFalse(canCompare(item(isImported = true)))
    }

    @Test
    fun pendingResultWithStagedFileEvenImported() {
        assertTrue(canCompare(item(state = FinalizeState.PENDING, finalUri = null, stagedPath = "/tmp/v.mp4", mediaStoreId = null, isImported = true)))
        assertFalse(canCompare(item(state = FinalizeState.PENDING, finalUri = null, stagedPath = null)))
    }

    @Test
    fun nothingElseCompares() {
        FinalizeState.entries.filter { it != FinalizeState.SAVED && it != FinalizeState.PENDING }.forEach { state ->
            assertFalse("state=$state", canCompare(item(state = state)))
        }
        assertFalse(canCompare(item(status = ItemStatus.QUEUED)))
        assertFalse(canCompare(item(status = ItemStatus.FAILED)))
    }
}
