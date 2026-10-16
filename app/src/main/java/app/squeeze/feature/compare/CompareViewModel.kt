package app.squeeze.feature.compare

import android.content.IntentSender
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import app.squeeze.data.ResultsRepository
import app.squeeze.data.SettingsRepository
import app.squeeze.finalize.FinalizePlanner
import app.squeeze.finalize.Finalizer
import app.squeeze.finalize.MediaStoreWriter
import app.squeeze.finalize.ReplaceAfterSaveResult
import app.squeeze.ui.media.VideoRef
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CompareUi {
    data object Loading : CompareUi

    /** The original or the saved copy can no longer be played, or this item has nothing to replace. */
    data object Unavailable : CompareUi

    data class Ready(
        val name: String,
        val original: VideoRef,
        val compressed: VideoRef,
        val originalSize: Long,
        val compressedSize: Long,
        /** The file name the compressed video will have once it replaces the original. */
        val finalName: String,
        val replacing: Boolean = false,
    ) : CompareUi
}

sealed interface CompareEvent {
    data object Replaced : CompareEvent
    data class AskToDelete(val request: IntentSender) : CompareEvent
    data object Failed : CompareEvent
}

/** Loads one saved result of the history for [CompareScreen] and runs "replace the original" on it. */
@HiltViewModel
class CompareViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val results: ResultsRepository,
    private val finalizer: Finalizer,
    private val store: MediaStoreWriter,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val itemId: Long = checkNotNull(savedState["itemId"])

    private val _ui = MutableStateFlow<CompareUi>(CompareUi.Loading)
    val ui: StateFlow<CompareUi> = _ui

    private val _events = Channel<CompareEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _ui.value = withContext(Dispatchers.IO) {
                val item = results.item(itemId)
                val copy = item?.finalUri?.toUri()
                val original = item?.sourceUri?.toUri()
                if (item == null || copy == null || original == null ||
                    item.status != ItemStatus.DONE || item.finalizeState != FinalizeState.SAVED ||
                    item.isImported || item.mediaStoreId == null ||
                    store.length(copy) <= 0 || store.length(original) <= 0
                ) {
                    CompareUi.Unavailable
                } else {
                    val keep = settings.replaceKeepsOriginalName.first()
                    CompareUi.Ready(
                        name = item.displayName,
                        original = VideoRef(original),
                        compressed = VideoRef(copy),
                        originalSize = item.originalSize,
                        compressedSize = item.outputSize ?: store.length(copy),
                        finalName = FinalizePlanner.replacementName(item.displayName, store.displayName(copy) ?: item.displayName, keep),
                    )
                }
            }
        }
    }

    /** Deletes the original and puts the saved copy in its place; asks for the user's approval first when the system needs it. */
    fun replace() {
        val current = _ui.value as? CompareUi.Ready ?: return
        if (current.replacing) return
        _ui.value = current.copy(replacing = true)
        viewModelScope.launch {
            val keep = settings.replaceKeepsOriginalName.first()
            when (val result = finalizer.validateReplacement(itemId, keep)) {
                is ReplaceAfterSaveResult.Done -> _events.send(CompareEvent.Replaced)
                is ReplaceAfterSaveResult.NeedsDeleteConsent -> {
                    _ui.value = current
                    _events.send(CompareEvent.AskToDelete(store.deleteRequest(listOf(result.original))))
                }
                is ReplaceAfterSaveResult.Failed, ReplaceAfterSaveResult.Unavailable -> {
                    _ui.value = current
                    _events.send(CompareEvent.Failed)
                }
            }
        }
    }
}
