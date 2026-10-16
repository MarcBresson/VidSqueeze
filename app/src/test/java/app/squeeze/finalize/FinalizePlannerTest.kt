package app.squeeze.finalize

import app.squeeze.data.BatchItemEntity
import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import app.squeeze.engine.BuiltInPresets
import app.squeeze.engine.ExportOptions
import app.squeeze.engine.SourceInfo
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalizePlannerTest {
    private fun item(
        id: Long = 1,
        name: String = "clip.mp4",
        staged: String? = "/files/staging/1/$id.mp4",
        mediaId: Long? = 10,
        imported: Boolean = false,
        status: ItemStatus = ItemStatus.DONE,
        state: FinalizeState = FinalizeState.PENDING,
    ) = BatchItemEntity(
        id = id, batchId = 1, position = id, sourceUri = "content://media/external/video/media/10", mediaStoreId = mediaId,
        isImported = imported, displayName = name, originalSize = 1000, status = status, stagedPath = staged, finalizeState = state,
    )

    @Test
    fun onlyDoneResultsStillWaitingArePending() {
        val items = listOf(
            item(1),
            item(2, status = ItemStatus.FAILED, staged = null),
            item(3, state = FinalizeState.SAVED),
            item(4, status = ItemStatus.SKIPPED_NO_GAIN, staged = null),
            item(5, staged = null),
        )
        assertEquals(listOf(1L), FinalizePlanner.pending(items).map { it.id })
    }

    @Test
    fun sameContainerIsOverwrittenInPlace() {
        val plan = FinalizePlanner.replacePlan(listOf(item(1, name = "IMG_1.MP4")))
        assertEquals(ReplaceKind.IN_PLACE, plan.items.single().second)
    }

    @Test
    fun differentContainerIsSwapped() {
        val plan = FinalizePlanner.replacePlan(listOf(item(1, name = "clip.mov")))
        assertEquals(ReplaceKind.SWAP, plan.items.single().second)
    }

    @Test
    fun importedOrUnknownSourcesAreSavedAsCopies() {
        val plan = FinalizePlanner.replacePlan(listOf(item(1, imported = true, mediaId = null), item(2, mediaId = null)))
        assertTrue(plan.items.all { it.second == ReplaceKind.SAVE_COPY })
        assertEquals(2, plan.itemsOf(ReplaceKind.SAVE_COPY).size)
        assertNull(plan.kindOf(item(9)))
    }

    @Test
    fun swappedNameKeepsTheStem() {
        assertEquals("holiday.mp4", FinalizePlanner.swappedName("holiday.mov", "mp4"))
        assertEquals("a.b.mkv", FinalizePlanner.swappedName("a.b.mp4", ".mkv"))
    }

    @Test
    fun copyNameFollowsTheTemplate() {
        val preset = BuiltInPresets.share.copy(export = ExportOptions(nameTemplate = "{original_file_name}_{resolution}.{output_format}"))
        assertEquals("IMG_1_720p.mp4", FinalizePlanner.copyName(preset, "IMG_1.mp4", "mp4", LocalDate.of(2026, 1, 2)))
        assertEquals("original", FinalizePlanner.resolutionLabel(null))
    }

    @Test
    fun mimeTypes() {
        assertEquals("video/mp4", FinalizePlanner.mimeType("MP4"))
        assertEquals("video/x-matroska", FinalizePlanner.mimeType(".mkv"))
        assertEquals("video/quicktime", FinalizePlanner.mimeType("mov"))
    }

    private val good = SourceInfo(width = 1280, height = 720, durationMs = 10_000, videoCodec = "h264")

    @Test
    fun verifierAcceptsAFaithfulCopy() {
        assertNull(OutputVerifier.problem(10_400, good))
        assertNull(OutputVerifier.problem(0, good))
    }

    @Test
    fun verifierRejectsBrokenOutput() {
        assertNotNull(OutputVerifier.problem(10_000, null))
        assertNotNull(OutputVerifier.problem(10_000, good.copy(videoCodec = null)))
        assertNotNull(OutputVerifier.problem(10_000, good.copy(width = 0, height = 0)))
        assertNotNull(OutputVerifier.problem(20_000, good))
        assertNotNull(OutputVerifier.problem(10_000, good.copy(durationMs = 0)))
    }

    @Test
    fun reportTotals() {
        val r = FinalizeReport(replaced = 2, savedAsCopy = 1, originalBytes = 900, outputBytes = 300, needsConsent = 1)
        assertEquals(3, r.handled)
        assertEquals(600, r.savedBytes)
        assertTrue(r.hasLeftovers)
        assertFalse(FinalizeReport(replaced = 1).hasLeftovers)
    }

    @Test
    fun replacementKeepsTheOriginalNameWithTheCopysExtension() {
        assertEquals("trip.mp4", FinalizePlanner.replacementName("trip.mp4", "trip - compressed.mp4", keepOriginalName = true))
        assertEquals("trip.mkv", FinalizePlanner.replacementName("trip.mp4", "trip - compressed.mkv", keepOriginalName = true))
    }

    @Test
    fun replacementKeepsTheCopysNameWhenAsked() {
        assertEquals("trip - compressed.mp4", FinalizePlanner.replacementName("trip.mov", "trip - compressed.mp4", keepOriginalName = false))
    }
}
