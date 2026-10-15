package app.squeeze.data

/** Per-batch totals shown in the completion notification and, later, in history. */
data class BatchSummary(
    val done: Int,
    val failed: Int,
    val skippedNoGain: Int,
    val cancelled: Int,
    /** Sizes of the items that actually got smaller. */
    val originalBytes: Long,
    val outputBytes: Long,
) {
    val savedBytes: Long get() = (originalBytes - outputBytes).coerceAtLeast(0)

    /** Reduction in whole percent (0 when nothing was compressed). */
    val savedPercent: Int
        get() = if (originalBytes <= 0) 0 else ((savedBytes * 100) / originalBytes).toInt()

    companion object {
        fun of(items: List<BatchItemEntity>): BatchSummary {
            val done = items.filter { it.status == ItemStatus.DONE }
            return BatchSummary(
                done = done.size,
                failed = items.count { it.status == ItemStatus.FAILED },
                skippedNoGain = items.count { it.status == ItemStatus.SKIPPED_NO_GAIN },
                cancelled = items.count { it.status == ItemStatus.CANCELLED },
                originalBytes = done.sumOf { it.originalSize },
                outputBytes = done.sumOf { it.outputSize ?: it.originalSize },
            )
        }

        /**
         * A batch with at least one result waits for the user to pick what to do with the staged
         * files. Otherwise it is over: cancelled, partial (something failed) or done (all no-gain).
         */
        fun statusFor(items: List<ItemStatus>): BatchStatus = when {
            items.any { it == ItemStatus.QUEUED || it == ItemStatus.RUNNING } -> BatchStatus.RUNNING
            items.any { it == ItemStatus.DONE } -> BatchStatus.AWAITING_ACTION
            items.isNotEmpty() && items.all { it == ItemStatus.CANCELLED } -> BatchStatus.CANCELLED
            items.any { it == ItemStatus.FAILED } -> BatchStatus.PARTIAL
            else -> BatchStatus.DONE
        }
    }
}
