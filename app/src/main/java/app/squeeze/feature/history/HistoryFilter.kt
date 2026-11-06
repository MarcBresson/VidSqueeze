package app.squeeze.feature.history

import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import app.squeeze.data.StatsPeriod

/** One filterable video status. */
enum class VideoStatusFilter { AWAITING_ACTION, FAILED, DONE, CANCELLED }

enum class DateFilterKind { ALL, WEEK, MONTH, YEAR, RANGE }

/**
 * The History screen's filter state, one single-select per category, AND across categories.
 * A null/`ALL`/false field means the category is off.
 */
data class HistoryFilters(
    /** Index into the resolved size buckets, or [HistoryFilters.CUSTOM_INDEX] for the custom range. */
    val sizeBucketIndex: Int? = null,
    /** Used only when sizeBucketIndex == [HistoryFilters.CUSTOM_INDEX]. */
    val customSizeMinMb: Long? = null,
    val customSizeMaxMb: Long? = null,
    /** Null = status category off. */
    val status: VideoStatusFilter? = null,
    val dateKind: DateFilterKind = DateFilterKind.ALL,
    /** RANGE only, inclusive. */
    val dateStartMs: Long? = null,
    /** RANGE only, inclusive end-of-day. */
    val dateEndMs: Long? = null,
    /** Same predicate as status == [VideoStatusFilter.AWAITING_ACTION]; the chips toggle together. */
    val onlyPending: Boolean = false,
    val missingOnly: Boolean = false,
) {
    /** True when no category restricts anything: everything matches. */
    val isEmpty: Boolean
        get() = sizeBucketIndex == null && status == null && dateKind == DateFilterKind.ALL &&
            !onlyPending && !missingOnly

    /** How many filter categories are active — shown as the badge on the filter icon. */
    fun activeCategoryCount(): Int = listOf(
        sizeBucketIndex != null,
        status != null,
        dateKind != DateFilterKind.ALL,
        onlyPending,
        missingOnly,
    ).count { it }

    companion object {
        /** sizeBucketIndex value that selects the user-entered min / max range instead of a bucket. */
        const val CUSTOM_INDEX = -1
        val DEFAULT = HistoryFilters()
    }
}

/** The narrow item view the pure predicates need; built by the caller from any item source. */
data class FilterableItem(
    val itemId: Long,
    val originalSize: Long,
    val status: ItemStatus,
    val finalizeState: FinalizeState,
    /** Batch-level `finishedAt ?: createdAt` is passed in here by the caller. */
    val finishedAt: Long?,
    val presetId: String?,
    val presetName: String,
    val originalMissing: Boolean = false,
)

object HistoryFilter {
    private const val MB = 1024L * 1024

    /**
     * A video matches when every active category matches. Running/queued items are mapped by the
     * caller before they get here (see the plan's active-jobs mapping).
     */
    fun matchesItem(item: FilterableItem, filters: HistoryFilters, buckets: List<Long>, now: () -> Long = System::currentTimeMillis): Boolean {
        if (filters.isEmpty) return true
        if (!sizeMatches(item, filters, buckets)) return false
        if (!statusMatches(item, filters)) return false
        if (!dateMatches(item, filters, now)) return false
        if (filters.missingOnly && !item.originalMissing) return false
        return true
    }

    /** A batch card is shown when at least one of its videos matches. */
    fun matchesBatch(items: List<FilterableItem>, filters: HistoryFilters, buckets: List<Long>, now: () -> Long = System::currentTimeMillis): Boolean =
        filters.isEmpty || items.any { matchesItem(it, filters, buckets, now) }

    /**
     * The MB range a bucket chip stands for: `(null, 50)` means "under 50 MB", `(50, 500)` the
     * middle bucket, `(500, null)` "over 500 MB". Bounds are inclusive, in MB.
     */
    fun sizeLabelRange(index: Int, buckets: List<Long>): Pair<Long?, Long?> = when {
        index <= 0 -> Pair(null, buckets.first())
        index >= buckets.size -> Pair(buckets.last(), null)
        else -> Pair(buckets[index - 1], buckets[index])
    }

    private fun sizeMatches(item: FilterableItem, filters: HistoryFilters, buckets: List<Long>): Boolean {
        val index = filters.sizeBucketIndex ?: return true
        val (minMb, maxMb) = if (index == HistoryFilters.CUSTOM_INDEX) {
            // Empty min is 0, empty max unbounded, both empty means the option is deselected,
            // and min > max is normalized by swapping.
            var min = filters.customSizeMinMb
            var max = filters.customSizeMaxMb
            if (min == null && max == null) return true
            if (min != null && max != null && min > max) { val t = min; min = max; max = t }
            Pair(min, max)
        } else {
            sizeLabelRange(index, buckets)
        }
        val sizeMb = item.originalSize / MB
        if (minMb != null && sizeMb < minMb) return false
        if (maxMb != null && sizeMb > maxMb) return false
        return true
    }

    /** The pending chip and the AWAITING_ACTION chip are one predicate on purpose. */
    private fun isPendingDone(item: FilterableItem): Boolean =
        item.status == ItemStatus.DONE && item.finalizeState == FinalizeState.PENDING

    private fun statusMatches(item: FilterableItem, filters: HistoryFilters): Boolean {
        if (filters.onlyPending && !isPendingDone(item)) return false
        val status = filters.status ?: return true
        return when (status) {
            VideoStatusFilter.AWAITING_ACTION -> isPendingDone(item)
            VideoStatusFilter.FAILED -> item.status == ItemStatus.FAILED
            VideoStatusFilter.DONE -> item.status == ItemStatus.DONE && item.finalizeState != FinalizeState.PENDING
            VideoStatusFilter.CANCELLED -> item.status == ItemStatus.CANCELLED
        }
    }

    private fun dateMatches(item: FilterableItem, filters: HistoryFilters, now: () -> Long): Boolean {
        val at = item.finishedAt
        return when (filters.dateKind) {
            DateFilterKind.ALL -> true
            DateFilterKind.WEEK -> at != null && at >= StatsPeriod.WEEK.since(now())
            DateFilterKind.MONTH -> at != null && at >= StatsPeriod.MONTH.since(now())
            DateFilterKind.YEAR -> at != null && at >= StatsPeriod.YEAR.since(now())
            DateFilterKind.RANGE -> {
                val start = filters.dateStartMs
                val end = filters.dateEndMs
                if (at == null || start == null || end == null) return start == null && end == null
                at in start..end
            }
        }
    }
}
