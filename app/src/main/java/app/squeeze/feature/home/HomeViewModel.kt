package app.squeeze.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.auto.AutoRunner
import app.squeeze.auto.SuggestionSource
import app.squeeze.auto.VideoInfo
import app.squeeze.data.AutoSettingsRepository
import app.squeeze.data.HistoryRepository
import app.squeeze.data.PresetRepository
import app.squeeze.data.QueueRepository
import app.squeeze.data.ResultsRepository
import app.squeeze.data.SettingsRepository
import app.squeeze.data.StatTotals
import app.squeeze.data.StatsPeriod
import app.squeeze.engine.Preset
import app.squeeze.engine.SizeEstimator
import app.squeeze.engine.SourceInfo
import app.squeeze.feature.queue.QueueUi
import app.squeeze.work.QueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    queueManager: QueueManager,
    repository: QueueRepository,
    results: ResultsRepository,
    private val history: HistoryRepository,
    private val suggestionSource: SuggestionSource,
    private val autoRunner: AutoRunner,
    private val autoSettings: AutoSettingsRepository,
    private val settings: SettingsRepository,
    private val presets: PresetRepository,
) : ViewModel() {
    val queue: StateFlow<QueueUi> = combine(repository.observeActive(), queueManager.observeWorkerActive(), results.observeAwaiting(), ::QueueUi)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUi())

    /** Null until known, so the screen shows neither the offer nor the stats too early. */
    val autoEnabled: StateFlow<Boolean?> = autoSettings.config.map<_, Boolean?> { it.enabled }.state(null)
    val promoDismissed: StateFlow<Boolean?> = settings.autoPromoDismissed.map<Boolean, Boolean?> { it }.state(null)

    val weekStats: StateFlow<StatTotals?> = totals(StatsPeriod.WEEK)
    val monthStats: StateFlow<StatTotals?> = totals(StatsPeriod.MONTH)
    val allStats: StateFlow<StatTotals?> = totals(StatsPeriod.ALL)

    private val _libraryAccess = MutableStateFlow(suggestionSource.hasAccess())
    val libraryAccess: StateFlow<Boolean> = _libraryAccess

    private val _heavy = MutableStateFlow<List<HeavyVideo>>(emptyList())
    val heavy: StateFlow<List<HeavyVideo>> = _heavy

    private val _promo = MutableStateFlow<AutoPromo?>(null)
    val promo: StateFlow<AutoPromo?> = _promo

    /** Looks at the library again: the heaviest videos, and what auto compression would take on. */
    fun refresh() {
        viewModelScope.launch {
            val access = suggestionSource.hasAccess()
            _libraryAccess.value = access
            if (!access) {
                _heavy.value = emptyList()
                _promo.value = null
                return@launch
            }
            val preset = presets.getOrDefault(settings.defaultPresetId.first())
            _heavy.value = suggestionSource.heavyVideos(limit = HEAVY_COUNT)
                ?.let { found -> found.videos.zip(found.uris) { video, uri -> HeavyVideo(video, uri, estimate(preset, video)) } }
                .orEmpty()
            val config = autoSettings.currentConfig()
            _promo.value = if (config.enabled || settings.autoPromoDismissed.first()) {
                null
            } else {
                val planned = autoRunner.plan(config)
                val current = planned.sumOf { it.video.sizeBytes }
                val estimated = planned.mapNotNull { it.estimatedBytes }
                // A partial estimate would overstate the saving, so it only counts when complete.
                val achievable = if (estimated.size == planned.size) estimated.sum() else null
                AutoPromo(planned.size, current, achievable)
            }
        }
    }

    fun enableAuto() {
        viewModelScope.launch {
            autoSettings.update { it.copy(enabled = true) }
            _promo.value = null
        }
    }

    fun dismissPromo() {
        viewModelScope.launch {
            settings.setAutoPromoDismissed()
            _promo.value = null
        }
    }

    private fun estimate(preset: Preset, video: VideoInfo): Long? =
        SizeEstimator.estimateBytes(preset.params, SourceInfo(durationMs = video.durationMs, width = video.width, height = video.height), video.sizeBytes)

    private fun totals(period: StatsPeriod): StateFlow<StatTotals?> =
        history.observeStats(period).map<_, StatTotals?> { it.total }.state(null)

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    private companion object {
        const val HEAVY_COUNT = 3
    }
}
