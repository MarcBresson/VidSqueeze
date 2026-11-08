package app.squeeze.feature.history

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.R
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.HistoryEntry
import app.squeeze.data.FinalizeState
import app.squeeze.data.HistoryRepository
import app.squeeze.data.QueueRepository
import app.squeeze.data.ResultsRepository
import app.squeeze.data.SettingsRepository
import app.squeeze.data.Stats
import app.squeeze.data.StatsCalculator
import app.squeeze.data.StatsPeriod
import app.squeeze.data.StatRow
import app.squeeze.feature.queue.ActiveJobsActions
import app.squeeze.feature.queue.QueueUi
import app.squeeze.finalize.FinalizeManager
import app.squeeze.work.ItemLog
import app.squeeze.work.LogStore
import app.squeeze.work.QueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import app.squeeze.data.ItemStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class HistoryViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    repository: HistoryRepository,
    private val queue: QueueManager,
    private val finalize: FinalizeManager,
    queueRepository: QueueRepository,
    results: ResultsRepository,
    private val logs: LogStore,
    settings: SettingsRepository,
    probe: RealSourceProbe,
) : ViewModel() {
    /** Running and waiting jobs, and results that wait for replace / save / discard. */
    val jobs: StateFlow<QueueUi> = combine(queueRepository.observeActive(), queue.observeWorkerActive(), results.observeAwaiting(), ::QueueUi)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUi())

    val jobActions = ActiveJobsActions(
        onPause = queue::pause,
        onResume = queue::resume,
        onCancelAll = { queue.cancelAll() },
        onRemove = queue::remove,
    )

    /** Batch-level actions on a batch's pending results, shown on the batch header. */
    val batchActions = BatchResultActions(
        onSaveCopies = { batchId ->
            finalize.save(batchId)
            toastSaving()
        },
        onDiscard = finalize::discard,
    )

    /** Item-level actions on one video's pending result, shown on the video's row. */
    val itemActions = ItemResultActions(
        onSaveCopy = { itemId ->
            finalize.saveItem(itemId)
            toastSaving()
        },
        onDiscard = finalize::discardItem,
    )

    /** Null until the first load, so the screen can tell "empty" from "loading". */
    val history: StateFlow<List<HistoryEntry>?> = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _filters = MutableStateFlow(HistoryFilters.DEFAULT)
    val filters: StateFlow<HistoryFilters> = _filters

    /** itemId -> whether the original file is gone; entries not in the map are "unknown yet". */
    private val _missing = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val missing: StateFlow<Map<Long, Boolean>> = _missing

    /** itemId -> whether the compressed RESULT file is gone; entries not in the map are "unknown yet". */
    private val _resultMissing = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val resultMissing: StateFlow<Map<Long, Boolean>> = _resultMissing

    private val checker = MissingSourceChecker(probe)
    private val sizeBucketsFlow = settings.sizeFilterBucketsMb

    /** The resolved size-bucket thresholds, for the filter sheet's chip labels. */
    val sizeBuckets: StateFlow<List<Long>> = sizeBucketsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_BUCKETS_MB)

    /** History entries after filtering. Null while loading, as before. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredHistory: StateFlow<List<HistoryEntry>?> = combine(history, _filters, sizeBucketsFlow, _missing, _resultMissing) { entries, filters, buckets, missing, resultMissing ->
        entries?.filter { entry ->
            val at = entry.batch.finishedAt ?: entry.batch.createdAt
            HistoryFilter.matchesBatch(entry.items.map { it.filterable(at, missing, resultMissing) }, filters, buckets)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Active jobs filtered with the same predicates: status and originals describe finished work, so they rarely match a running item. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredJobs: StateFlow<QueueUi> = combine(jobs, _filters, sizeBucketsFlow) { jobs, filters, buckets ->
        if (filters.isEmpty) {
            jobs
        } else {
            jobs.copy(items = jobs.items.filter { item ->
                HistoryFilter.matchesItem(
                    FilterableItem(item.id, item.originalSize, item.status, item.finalizeState, finishedAt = null, presetId = null, presetName = ""),
                    filters,
                    buckets,
                )
            })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUi())

    /** Stats over the filtered videos, replacing the old per-tab period wiring. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val stats: StateFlow<Stats?> = filteredHistory.map { entries ->
        entries?.let { list ->
            val rows = list.flatMap { entry ->
                val at = entry.batch.finishedAt ?: entry.batch.createdAt
                entry.items.filter { it.status == ItemStatus.DONE }.map { item ->
                    StatRow(
                        presetId = entry.batch.presetId,
                        presetName = entry.batch.presetName,
                        originalSize = item.originalSize,
                        outputSize = item.outputSize,
                        finalizeState = item.finalizeState,
                        kind = item.kind,
                        finishedAt = item.finishedAt ?: at,
                        startedAt = item.startedAt,
                    )
                }
            }
            StatsCalculator.compute(rows, StatsPeriod.ALL, System.currentTimeMillis())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Best-effort missing checks, one per never-checked item, off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            history.collect { entries ->
                val known = _missing.value
                val knownResults = _resultMissing.value
                val updates = mutableMapOf<Long, Boolean>()
                val resultUpdates = mutableMapOf<Long, Boolean>()
                entries?.forEach { entry ->
                    entry.items.forEach { item ->
                        // One check per item with a locatable original; known ids are never re-checked.
                        if (item.id !in known && item.id !in updates &&
                            (item.mediaStoreId != null || item.isImported || item.sourceUri.isNotBlank())
                        ) {
                            updates[item.id] = checker.isMissing(item)
                        }
                        // One check per finished item with a locatable result (saved copy or staged file).
                        if (item.status == ItemStatus.DONE && item.id !in knownResults && item.id !in resultUpdates &&
                            (item.stagedPath != null || item.finalUri != null)
                        ) {
                            resultUpdates[item.id] = checker.isResultMissing(item)
                        }
                    }
                }
                if (updates.isNotEmpty()) _missing.value = known + updates
                if (resultUpdates.isNotEmpty()) _resultMissing.value = knownResults + resultUpdates
            }
        }
    }

    fun setFilters(filters: HistoryFilters) {
        _filters.value = filters
    }

    /** Re-checks one item's original and result, for the row's buttons shown while it is expanded. */
    fun refreshMissing(item: BatchItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            _missing.value = _missing.value + (item.id to checker.isMissing(item))
            if (item.status == ItemStatus.DONE && (item.stagedPath != null || item.finalUri != null)) {
                _resultMissing.value = _resultMissing.value + (item.id to checker.isResultMissing(item))
            }
        }
    }

    fun clearFilters() {
        _filters.value = HistoryFilters.DEFAULT
    }

    private fun update(transform: (HistoryFilters) -> HistoryFilters) {
        _filters.value = transform(_filters.value)
    }

    /** True when ffmpeg's log of the item was kept (items from before logs were saved have none). */
    fun hasLog(itemId: Long): Boolean = logs.exists(itemId)

    suspend fun log(itemId: Long): ItemLog? = withContext(Dispatchers.IO) { logs.read(itemId) }

    private fun app.squeeze.data.BatchItemEntity.filterable(batchFallbackAt: Long, missing: Map<Long, Boolean>, resultMissing: Map<Long, Boolean>) = FilterableItem(
        itemId = id,
        originalSize = originalSize,
        status = status,
        finalizeState = finalizeState,
        finishedAt = finishedAt ?: batchFallbackAt,
        presetId = null,
        presetName = "",
        originalMissing = missing[id] == true,
        // A finished item whose result is neither staged nor saved has nothing locatable left —
        // except DISCARDED, where the app deleted the result on the user's instruction.
        resultMissing = status == ItemStatus.DONE && finalizeState != FinalizeState.DISCARDED &&
            ((stagedPath == null && finalUri == null) || resultMissing[id] == true),
    )

    private fun toastSaving() = Toast.makeText(appContext, R.string.ready_save_started, Toast.LENGTH_SHORT).show()
}

private val DEFAULT_BUCKETS_MB = listOf(50L, 500L)

/** Save and discard for a batch's remaining pending results (Reshare and Replace need an activity). */
class BatchResultActions(val onSaveCopies: (Long) -> Unit, val onDiscard: (Long) -> Unit)

/** Save and discard for one video's pending result (Reshare and Replace need an activity). */
class ItemResultActions(val onSaveCopy: (Long) -> Unit, val onDiscard: (Long) -> Unit)
