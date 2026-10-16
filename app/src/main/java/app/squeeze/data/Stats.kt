package app.squeeze.data

import androidx.room.ColumnInfo

enum class StatsPeriod(val days: Long?) {
    ALL(null),
    YEAR(365),
    MONTH(30),
    WEEK(7),
    ;

    /** Earliest compression time (epoch ms) that still counts, 0 for all time. */
    fun since(now: Long): Long = days?.let { (now - it * DAY_MS).coerceAtLeast(0) } ?: 0

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** One compressed video, as far as statistics are concerned (see [BatchItemDao.statRows]). */
data class StatRow(
    @ColumnInfo(name = "presetId") val presetId: String?,
    @ColumnInfo(name = "presetName") val presetName: String,
    @ColumnInfo(name = "originalSize") val originalSize: Long,
    @ColumnInfo(name = "outputSize") val outputSize: Long?,
    @ColumnInfo(name = "finalizeState") val finalizeState: FinalizeState,
    @ColumnInfo(name = "finishedAt") val finishedAt: Long?,
    @ColumnInfo(name = "startedAt") val startedAt: Long? = null,
)

data class StatTotals(
    val videos: Int = 0,
    val originalBytes: Long = 0,
    val outputBytes: Long = 0,
    /** Part of the saving that really left the device: results that replaced their original. */
    val freedBytes: Long = 0,
    /** Time spent compressing these videos, from the start of each to its end. */
    val durationMs: Long = 0,
) {
    val savedBytes: Long get() = (originalBytes - outputBytes).coerceAtLeast(0)
    val savedPercent: Int get() = if (originalBytes <= 0) 0 else ((savedBytes * 100) / originalBytes).toInt()

    operator fun plus(row: StatRow): StatTotals {
        val output = row.outputSize ?: row.originalSize
        val saved = (row.originalSize - output).coerceAtLeast(0)
        return StatTotals(
            videos = videos + 1,
            originalBytes = originalBytes + row.originalSize,
            outputBytes = outputBytes + output,
            freedBytes = freedBytes + if (row.finalizeState == FinalizeState.REPLACED) saved else 0,
            durationMs = durationMs + elapsed(row),
        )
    }
}

private fun elapsed(row: StatRow): Long {
    val start = row.startedAt ?: return 0
    val end = row.finishedAt ?: return 0
    return (end - start).coerceAtLeast(0)
}

data class PresetStats(val presetId: String?, val presetName: String, val totals: StatTotals)

data class Stats(val total: StatTotals, val perPreset: List<PresetStats>)

/**
 * Statistics over compressed videos. Presets are never merged: two groups are the same only when
 * both the preset id and the name the batch ran under match, so "Share" and "Share (edited)" or a
 * renamed preset stay apart.
 */
object StatsCalculator {
    fun compute(rows: List<StatRow>, period: StatsPeriod, now: Long): Stats {
        val since = period.since(now)
        val counted = rows.filter {
            it.finalizeState != FinalizeState.DISCARDED && (it.finishedAt ?: 0) >= since
        }
        val groups = counted.groupBy { it.presetId to it.presetName }
            .map { (key, group) -> PresetStats(key.first, key.second, group.fold(StatTotals()) { acc, row -> acc + row }) }
            .sortedWith(compareByDescending<PresetStats> { it.totals.savedBytes }.thenBy { it.presetName.lowercase() })
        return Stats(counted.fold(StatTotals()) { acc, row -> acc + row }, groups)
    }
}

/** A finished batch with its items, newest first in the history list. */
data class HistoryEntry(val batch: BatchEntity, val items: List<BatchItemEntity>) {
    val summary: BatchSummary = BatchSummary.of(items)

    /** A single-item batch is shown as the item itself. */
    val single: BatchItemEntity? get() = items.singleOrNull()
}

object HistoryBuilder {
    /** Groups items under their batches; batches with nothing to show (all cancelled) are left out. */
    fun build(batches: List<BatchEntity>, items: List<BatchItemEntity>): List<HistoryEntry> {
        val byBatch = items.groupBy { it.batchId }
        return batches.sortedByDescending { it.createdAt }.mapNotNull { batch ->
            val shown = byBatch[batch.id].orEmpty().filter { it.status != ItemStatus.CANCELLED }.sortedBy { it.position }
            if (shown.isEmpty()) null else HistoryEntry(batch, shown)
        }
    }
}
