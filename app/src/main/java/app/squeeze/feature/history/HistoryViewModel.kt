package app.squeeze.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.data.HistoryEntry
import app.squeeze.data.HistoryRepository
import app.squeeze.data.Stats
import app.squeeze.data.StatsPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HistoryViewModel @Inject constructor(repository: HistoryRepository) : ViewModel() {
    /** Null until the first load, so the screen can tell "empty" from "loading". */
    val history: StateFlow<List<HistoryEntry>?> = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _period = MutableStateFlow(StatsPeriod.ALL)
    val period: StateFlow<StatsPeriod> = _period

    @OptIn(ExperimentalCoroutinesApi::class)
    val stats: StateFlow<Stats?> = _period.flatMapLatest { repository.observeStats(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPeriod(period: StatsPeriod) {
        _period.value = period
    }
}
