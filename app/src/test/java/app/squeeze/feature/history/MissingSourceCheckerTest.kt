package app.squeeze.feature.history

import app.squeeze.data.BatchItemEntity
import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import app.squeeze.engine.MediaKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProbe(
    val mediaStore: Boolean = true,
    val mediaStoreImages: Boolean = mediaStore,
    val content: Boolean = true,
    val file: Boolean = true,
    val throwOn: String? = null,
) : SourceProbe {
    override fun mediaStoreExists(mediaStoreId: Long, kind: MediaKind): Boolean {
        if (throwOn == "mediaStore") error("boom")
        return if (kind == MediaKind.IMAGE) mediaStoreImages else mediaStore
    }

    override fun contentUriExists(uri: String): Boolean {
        if (throwOn == "content") error("boom")
        return content
    }

    override fun fileExists(path: String): Boolean {
        if (throwOn == "file") error("boom")
        return file
    }
}

/** A minimal item; only the source fields matter to the checker. */
private fun sourceItem(
    sourceUri: String = "file:///data/original.mp4",
    mediaStoreId: Long? = null,
    isImported: Boolean = false,
    kind: MediaKind = MediaKind.VIDEO,
    finalizeState: FinalizeState = FinalizeState.PENDING,
) = BatchItemEntity(
    id = 1,
    batchId = 1,
    position = 0,
    sourceUri = sourceUri,
    mediaStoreId = mediaStoreId,
    isImported = isImported,
    displayName = "video.mp4",
    originalSize = 1000,
    kind = kind,
    finalizeState = finalizeState,
)

/** A finished item; the result fields matter to [MissingSourceChecker.isResultMissing]. */
private fun resultItem(
    status: ItemStatus = ItemStatus.DONE,
    stagedPath: String? = null,
    finalUri: String? = null,
) = sourceItem().copy(status = status, stagedPath = stagedPath, finalUri = finalUri, finalizeState = FinalizeState.SAVED)

class MissingSourceCheckerTest {
    @Test
    fun mediaStoreIdGoneIsMissing() {
        val checker = MissingSourceChecker(FakeProbe(mediaStore = false))
        assertTrue(checker.isMissing(sourceItem(mediaStoreId = 42)))
    }

    @Test
    fun mediaStoreIdMovedIsNotMissing() {
        val checker = MissingSourceChecker(FakeProbe(mediaStore = true))
        assertFalse(checker.isMissing(sourceItem(mediaStoreId = 42)))
    }

    @Test
    fun photoLookedUpInTheImageCollection() {
        // A photo whose id exists in Images but not in Video must not read as missing.
        val checker = MissingSourceChecker(FakeProbe(mediaStore = false, mediaStoreImages = true))
        assertFalse(checker.isMissing(sourceItem(mediaStoreId = 42, kind = MediaKind.IMAGE)))
        val gone = MissingSourceChecker(FakeProbe(mediaStoreImages = false))
        assertTrue(gone.isMissing(sourceItem(mediaStoreId = 42, kind = MediaKind.IMAGE)))
    }

    @Test
    fun fileSourceGoneIsMissing() {
        val gone = MissingSourceChecker(FakeProbe(file = false))
        assertTrue(gone.isMissing(sourceItem(sourceUri = "file:///data/original.mp4")))
        val there = MissingSourceChecker(FakeProbe(file = true))
        assertFalse(there.isMissing(sourceItem(sourceUri = "file:///data/original.mp4")))
    }

    @Test
    fun importedCopyNeverMissing() {
        // The worker deletes the import copy when the item finishes, and the true original lives
        // behind a URI we did not keep — "missing" can't be determined and must not be reported.
        val gone = MissingSourceChecker(FakeProbe(file = false))
        assertFalse(gone.isMissing(sourceItem(sourceUri = "file:///data/copy.jpg", isImported = true, kind = MediaKind.IMAGE)))
        val there = MissingSourceChecker(FakeProbe(file = true))
        assertFalse(there.isMissing(sourceItem(sourceUri = "file:///data/copy.mp4", isImported = true)))
    }

    @Test
    fun replacedOriginalNeverMissing() {
        // A replace the user approved deleted the original by design.
        val checker = MissingSourceChecker(FakeProbe(mediaStore = false))
        assertFalse(checker.isMissing(sourceItem(mediaStoreId = 42, finalizeState = FinalizeState.REPLACED)))
    }

    @Test
    fun contentUriUnresolvableIsMissing() {
        val checker = MissingSourceChecker(FakeProbe(content = false))
        assertTrue(checker.isMissing(sourceItem(sourceUri = "content://media/external/video/9")))
        val ok = MissingSourceChecker(FakeProbe(content = true))
        assertFalse(ok.isMissing(sourceItem(sourceUri = "content://media/external/video/9")))
    }

    @Test
    fun probeThrowingIsMissing() {
        val checker = MissingSourceChecker(FakeProbe(throwOn = "mediaStore"))
        assertTrue(checker.isMissing(sourceItem(mediaStoreId = 42)))
        val file = MissingSourceChecker(FakeProbe(throwOn = "file"))
        assertTrue(file.isMissing(sourceItem(sourceUri = "file:///data/original.mp4")))
        val content = MissingSourceChecker(FakeProbe(throwOn = "content"))
        assertTrue(content.isMissing(sourceItem(sourceUri = "content://media/external/video/9")))
    }

    @Test
    fun savedCopyDeletedFromLibraryIsResultMissing() {
        val checker = MissingSourceChecker(FakeProbe(content = false))
        assertTrue(checker.isResultMissing(resultItem(finalUri = "content://media/external/video/9")))
        val there = MissingSourceChecker(FakeProbe(content = true))
        assertFalse(there.isResultMissing(resultItem(finalUri = "content://media/external/video/9")))
    }

    @Test
    fun stagedFileGoneIsResultMissing() {
        val gone = MissingSourceChecker(FakeProbe(file = false))
        assertTrue(gone.isResultMissing(resultItem(stagedPath = "/data/staged.mp4")))
        val there = MissingSourceChecker(FakeProbe(file = true))
        assertFalse(there.isResultMissing(resultItem(stagedPath = "/data/staged.mp4")))
    }

    @Test
    fun noResultAtAllIsNotResultMissing() {
        val checker = MissingSourceChecker(FakeProbe(content = false))
        assertFalse(checker.isResultMissing(resultItem()))
    }

    @Test
    fun unfinishedItemIsNotResultMissing() {
        // A failed item has nothing to open, whatever stale result fields it carries.
        val checker = MissingSourceChecker(FakeProbe(content = false))
        assertFalse(checker.isResultMissing(resultItem(status = ItemStatus.FAILED, finalUri = "content://media/external/video/9")))
    }

    @Test
    fun probeThrowingIsResultMissing() {
        val checker = MissingSourceChecker(FakeProbe(throwOn = "content"))
        assertTrue(checker.isResultMissing(resultItem(finalUri = "content://media/external/video/9")))
    }
}
