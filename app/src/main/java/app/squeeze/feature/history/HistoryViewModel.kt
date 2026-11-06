package app.squeeze.feature.history

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.R
import app.squeeze.data.HistoryEntry
import app.squeeze.data.HistoryRepository
import app.squeeze.data.QueueRepository
import app.squeeze.data.ResultsRepository
import app.squeeze.feature.queue.ActiveJobsActions
import app.squeeze.feature.queue.QueueUi
import app.squeeze.finalize.FinalizeManager
import app.squeeze.work.ItemLog
import app.squeeze.work.LogStore
import app.squeeze.work.QueueManager
import app.squeeze.data.Stats
import app.squeeze.data.StatsPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
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

    private val _period = MutableStateFlow(StatsPeriod.ALL)
    val period: StateFlow<StatsPeriod> = _period

    @OptIn(ExperimentalCoroutinesApi::class)
    val stats: StateFlow<Stats?> = _period.flatMapLatest { repository.observeStats(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True when ffmpeg's log of the item was kept (items from before logs were saved have none). */
    fun hasLog(itemId: Long): Boolean = logs.exists(itemId)

    suspend fun log(itemId: Long): ItemLog? = withContext(Dispatchers.IO) { logs.read(itemId) }

    fun setPeriod(period: StatsPeriod) {
        _period.value = period
    }

    private fun toastSaving() = Toast.makeText(appContext, R.string.ready_save_started, Toast.LENGTH_SHORT).show()
}

/** Save and discard for a batch's remaining pending results (Reshare and Replace need an activity). */
class BatchResultActions(val onSaveCopies: (Long) -> Unit, val onDiscard: (Long) -> Unit)

/** Save and discard for one video's pending result (Reshare and Replace need an activity). */
class ItemResultActions(val onSaveCopy: (Long) -> Unit, val onDiscard: (Long) -> Unit)
