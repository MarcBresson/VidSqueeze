package app.squeeze.feature.history

import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MB in bytes. */
private const val MB = 1024L * 1024

private fun item(
    sizeMb: Long = 100,
    status: ItemStatus = ItemStatus.DONE,
    finalizeState: FinalizeState = FinalizeState.REPLACED,
    finishedAt: Long? = 1_000_000L,
    missing: Boolean = false,
) = FilterableItem(
    itemId = 1,
    originalSize = sizeMb * MB,
    status = status,
    finalizeState = finalizeState,
    finishedAt = finishedAt,
    presetId = "p",
    presetName = "Preset",
    originalMissing = missing,
)

class HistoryFilterTest {
    private val buckets = listOf(50L, 500L)

    @Test
    fun emptyFiltersMatchEverything() {
        val filters = HistoryFilters.DEFAULT
        assertTrue(filters.isEmpty)
        assertTrue(HistoryFilter.matchesItem(item(status = ItemStatus.QUEUED), filters, buckets))
        assertTrue(HistoryFilter.matchesItem(item(status = ItemStatus.SKIPPED_NO_GAIN), filters, buckets))
        assertTrue(HistoryFilter.matchesBatch(emptyList(), filters, buckets))
    }

    @Test
    fun sizeBucketBoundariesAreInclusive() {
        val under = HistoryFilters(sizeBucketIndex = 0)
        val middle = HistoryFilters(sizeBucketIndex = 1)
        val over = HistoryFilters(sizeBucketIndex = 2)
        // 49.9 MB: only the "under 50" bucket.
        assertTrue(HistoryFilter.matchesItem(item(sizeMb = 49), under, buckets))
        assertFalse(HistoryFilter.matchesItem(item(sizeMb = 49), middle, buckets))
        // 50 MB: inclusive lower bound of the middle bucket and inclusive upper bound of the first.
        assertTrue(HistoryFilter.matchesItem(item(sizeMb = 50), middle, buckets))
        assertTrue(HistoryFilter.matchesItem(item(sizeMb = 50), under, buckets))
        // 500 MB: inclusive on both neighbouring buckets.
        assertTrue(HistoryFilter.matchesItem(item(sizeMb = 500), middle, buckets))
        assertTrue(HistoryFilter.matchesItem(item(sizeMb = 500), over, buckets))
        // 500.1 MB: only "over 500".
        assertTrue(HistoryFilter.matchesItem(item(sizeMb = 501), over, buckets))
        assertFalse(HistoryFilter.matchesItem(item(sizeMb = 501), middle, buckets))
        assertEquals(Pair<Long?, Long?>(null, 50L), HistoryFilter.sizeLabelRange(0, buckets))
        assertEquals(Pair<Long?, Long?>(50L, 500L), HistoryFilter.sizeLabelRange(1, buckets))
        assertEquals(Pair<Long?, Long?>(500L, null), HistoryFilter.sizeLabelRange(2, buckets))
    }

    @Test
    fun customSizeOpenEnded() {
        val minOnly = HistoryFilters(sizeBucketIndex = HistoryFilters.CUSTOM_INDEX, customSizeMinMb = 100)
        assertTrue(HistoryFilter.matchesItem(item(sizeMb = 1000), minOnly, buckets))
        assertFalse(HistoryFilter.matchesItem(item(sizeMb = 99), minOnly, buckets))
        val maxOnly = HistoryFilters(sizeBucketIndex = HistoryFilters.CUSTOM_INDEX, customSizeMaxMb = 100)
        assertTrue(HistoryFilter.matchesItem(item(sizeMb = 1), maxOnly, buckets))
        assertFalse(HistoryFilter.matchesItem(item(sizeMb = 101), maxOnly, buckets))
        // Both empty: the custom option is ignored (treated as deselected).
        val bothEmpty = HistoryFilters(sizeBucketIndex = HistoryFilters.CUSTOM_INDEX)
        assertTrue(HistoryFilter.matchesItem(item(sizeMb = 1000), bothEmpty, buckets))
    }

    @Test
    fun customSizeSwapsMinAndMax() {
        val swapped = HistoryFilters(
            sizeBucketIndex = HistoryFilters.CUSTOM_INDEX,
            customSizeMinMb = 500,
            customSizeMaxMb = 100,
        )
        assertTrue(HistoryFilter.matchesItem(item(sizeMb = 200), swapped, buckets))
        assertFalse(HistoryFilter.matchesItem(item(sizeMb = 50), swapped, buckets))
        assertFalse(HistoryFilter.matchesItem(item(sizeMb = 600), swapped, buckets))
    }

    @Test
    fun statusAwaitingActionMatchesPendingDoneItem() {
        val f = HistoryFilters(statuses = setOf(VideoStatusFilter.AWAITING_ACTION))
        assertTrue(HistoryFilter.matchesItem(item(finalizeState = FinalizeState.PENDING), f, buckets))
        assertFalse(HistoryFilter.matchesItem(item(finalizeState = FinalizeState.SAVED), f, buckets))
        assertFalse(HistoryFilter.matchesItem(item(status = ItemStatus.FAILED), f, buckets))
    }

    @Test
    fun statusDoneExcludesPending() {
        val f = HistoryFilters(statuses = setOf(VideoStatusFilter.DONE))
        for (state in listOf(FinalizeState.REPLACED, FinalizeState.SAVED, FinalizeState.SHARED_ONLY, FinalizeState.DISCARDED)) {
            assertTrue(HistoryFilter.matchesItem(item(finalizeState = state), f, buckets))
        }
        assertFalse(HistoryFilter.matchesItem(item(finalizeState = FinalizeState.PENDING), f, buckets))
        assertFalse(HistoryFilter.matchesItem(item(status = ItemStatus.FAILED), f, buckets))
    }

    @Test
    fun statusFailedAndCancelled() {
        val failed = HistoryFilters(statuses = setOf(VideoStatusFilter.FAILED))
        assertTrue(HistoryFilter.matchesItem(item(status = ItemStatus.FAILED), failed, buckets))
        assertFalse(HistoryFilter.matchesItem(item(status = ItemStatus.CANCELLED), failed, buckets))
        val cancelled = HistoryFilters(statuses = setOf(VideoStatusFilter.CANCELLED))
        assertTrue(HistoryFilter.matchesItem(item(status = ItemStatus.CANCELLED), cancelled, buckets))
        assertFalse(HistoryFilter.matchesItem(item(status = ItemStatus.FAILED), cancelled, buckets))
    }

    @Test
    fun multipleStatusesMatchAnyOfThem() {
        val f = HistoryFilters(statuses = setOf(VideoStatusFilter.FAILED, VideoStatusFilter.CANCELLED))
        assertTrue(HistoryFilter.matchesItem(item(status = ItemStatus.FAILED), f, buckets))
        assertTrue(HistoryFilter.matchesItem(item(status = ItemStatus.CANCELLED), f, buckets))
        assertFalse(HistoryFilter.matchesItem(item(status = ItemStatus.DONE, finalizeState = FinalizeState.SAVED), f, buckets))
        // A selected status plus awaiting action widens rather than replaces.
        val withPending = HistoryFilters(statuses = setOf(VideoStatusFilter.DONE, VideoStatusFilter.AWAITING_ACTION))
        assertTrue(HistoryFilter.matchesItem(item(finalizeState = FinalizeState.PENDING), withPending, buckets))
        assertTrue(HistoryFilter.matchesItem(item(finalizeState = FinalizeState.SAVED), withPending, buckets))
    }

    @Test
    fun dateFixedPeriodsUseStatsPeriodSince() {
        val now = 400L * 24 * 60 * 60 * 1000 // 400 days in ms
        val day = 24L * 60 * 60 * 1000
        val week = HistoryFilters(dateKind = DateFilterKind.WEEK)
        assertTrue(HistoryFilter.matchesItem(item(finishedAt = now - 6 * day), week, buckets, now = { now }))
        assertFalse(HistoryFilter.matchesItem(item(finishedAt = now - 8 * day), week, buckets, now = { now }))
        val month = HistoryFilters(dateKind = DateFilterKind.MONTH)
        assertTrue(HistoryFilter.matchesItem(item(finishedAt = now - 29 * day), month, buckets, now = { now }))
        assertFalse(HistoryFilter.matchesItem(item(finishedAt = now - 31 * day), month, buckets, now = { now }))
        val year = HistoryFilters(dateKind = DateFilterKind.YEAR)
        assertTrue(HistoryFilter.matchesItem(item(finishedAt = now - 300 * day), year, buckets, now = { now }))
        assertFalse(HistoryFilter.matchesItem(item(finishedAt = now - 400 * day), year, buckets, now = { now }))
    }

    @Test
    fun dateRangeInclusiveEndOfDay() {
        val start = 10L * 24 * 60 * 60 * 1000
        val end = 20L * 24 * 60 * 60 * 1000 + (23 * 60 + 59) * 60 * 1000 + 59_999 // inclusive end-of-day
        val range = HistoryFilters(dateKind = DateFilterKind.RANGE, dateStartMs = start, dateEndMs = end)
        assertTrue(HistoryFilter.matchesItem(item(finishedAt = start), range, buckets))
        assertTrue(HistoryFilter.matchesItem(item(finishedAt = end), range, buckets))
        assertFalse(HistoryFilter.matchesItem(item(finishedAt = start - 1), range, buckets))
        assertFalse(HistoryFilter.matchesItem(item(finishedAt = end + 1), range, buckets))
    }

    @Test
    fun originalsMissingAndPresentAreOpposites() {
        val missing = HistoryFilters(originals = OriginalsFilter.MISSING)
        assertTrue(HistoryFilter.matchesItem(item(missing = true), missing, buckets))
        assertFalse(HistoryFilter.matchesItem(item(missing = false), missing, buckets))
        val present = HistoryFilters(originals = OriginalsFilter.PRESENT)
        assertTrue(HistoryFilter.matchesItem(item(missing = false), present, buckets))
        assertFalse(HistoryFilter.matchesItem(item(missing = true), present, buckets))
    }

    @Test
    fun batchMatchesWhenAnyItemMatches() {
        val failed = HistoryFilters(statuses = setOf(VideoStatusFilter.FAILED))
        val batch = listOf(
            item(status = ItemStatus.DONE, finalizeState = FinalizeState.SAVED),
            item(status = ItemStatus.FAILED),
        )
        assertTrue(HistoryFilter.matchesBatch(batch, failed, buckets))
        val allDone = listOf(
            item(status = ItemStatus.DONE, finalizeState = FinalizeState.SAVED),
            item(status = ItemStatus.DONE, finalizeState = FinalizeState.REPLACED),
        )
        assertFalse(HistoryFilter.matchesBatch(allDone, failed, buckets))
    }

    @Test
    fun skippedNoGainMatchesNoStatusChip() {
        val skipped = item(status = ItemStatus.SKIPPED_NO_GAIN)
        for (status in VideoStatusFilter.entries) {
            assertFalse(HistoryFilter.matchesItem(skipped, HistoryFilters(statuses = setOf(status)), buckets))
        }
        // With the status category off, the item is not hidden by status.
        assertTrue(HistoryFilter.matchesItem(skipped, HistoryFilters(), buckets))
    }
}
