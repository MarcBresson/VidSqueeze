package app.squeeze.feature.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.auto.SuggestionSource
import app.squeeze.auto.Suggestions
import app.squeeze.data.AwaitingBatch
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.QueueRepository
import app.squeeze.data.ResultsRepository
import app.squeeze.finalize.FinalizeManager
import app.squeeze.work.QueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QueueUi(
    val items: List<BatchItemEntity> = emptyList(),
    val workerActive: Boolean = false,
    /** Finished batches whose compressed files wait for replace / save / discard. */
    val ready: List<AwaitingBatch> = emptyList(),
) {
    /** Items are waiting but nothing will run them: the user paused. */
    val paused get() = items.isNotEmpty() && !workerActive
}

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val queue: QueueManager,
    private val finalize: FinalizeManager,
    private val suggestionSource: SuggestionSource,
    repository: QueueRepository,
    results: ResultsRepository,
) : ViewModel() {
    val ui: StateFlow<QueueUi> = combine(repository.observeActive(), queue.observeWorkerActive(), results.observeAwaiting(), ::QueueUi)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUi())

    /** Heavy videos worth compressing; null until known or without access to the library. */
    private val _suggestions = MutableStateFlow<Suggestions?>(null)
    val suggestions: StateFlow<Suggestions?> = _suggestions

    private val _libraryAccess = MutableStateFlow(suggestionSource.hasAccess())
    val libraryAccess: StateFlow<Boolean> = _libraryAccess

    fun refreshSuggestions() {
        viewModelScope.launch {
            _libraryAccess.value = suggestionSource.hasAccess()
            _suggestions.value = suggestionSource.heavyVideos()?.takeIf { it.videos.isNotEmpty() }
        }
    }

    fun pause() = queue.pause()
    fun resume() = queue.resume()
    fun cancelAll() = queue.cancelAll()
    fun remove(itemId: Long) = queue.remove(itemId)
    fun saveCopies(batchId: Long) = finalize.save(batchId)
    fun discard(batchId: Long) = finalize.discard(batchId)
}
