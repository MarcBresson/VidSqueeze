package app.squeeze.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class HistoryRepository @Inject constructor(
    private val batches: BatchDao,
    private val items: BatchItemDao,
) {
    /** Finished batches, newest first. Batches still queued or running belong to the queue screen. */
    fun observeHistory(): Flow<List<HistoryEntry>> =
        combine(batches.observeFinished(), items.observeFinished(), HistoryBuilder::build)

    fun observeStats(period: StatsPeriod, now: () -> Long = System::currentTimeMillis): Flow<Stats> =
        items.observeStatRows(period.since(now())).map { StatsCalculator.compute(it, period, now()) }
}
