package app.squeeze.feature.history

import app.squeeze.data.BatchItemEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProbe(
    val mediaStore: Boolean = true,
    val content: Boolean = true,
    val file: Boolean = true,
    val throwOn: String? = null,
) : SourceProbe {
    override fun mediaStoreExists(mediaStoreId: Long): Boolean {
        if (throwOn == "mediaStore") error("boom")
        return mediaStore
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
) = BatchItemEntity(
    id = 1,
    batchId = 1,
    position = 0,
    sourceUri = sourceUri,
    mediaStoreId = mediaStoreId,
    isImported = isImported,
    displayName = "video.mp4",
    originalSize = 1000,
)

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
    fun fileSourceGoneIsMissing() {
        val gone = MissingSourceChecker(FakeProbe(file = false))
        assertTrue(gone.isMissing(sourceItem(sourceUri = "file:///data/original.mp4")))
        val there = MissingSourceChecker(FakeProbe(file = true))
        assertFalse(there.isMissing(sourceItem(sourceUri = "file:///data/original.mp4")))
    }

    @Test
    fun importedFileCheckedByPath() {
        val gone = MissingSourceChecker(FakeProbe(file = false))
        assertTrue(gone.isMissing(sourceItem(sourceUri = "file:///data/copy.mp4", isImported = true)))
        val there = MissingSourceChecker(FakeProbe(file = true))
        assertFalse(there.isMissing(sourceItem(sourceUri = "file:///data/copy.mp4", isImported = true)))
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
}
