package app.squeeze.feature.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.auto.AutoConfig
import app.squeeze.auto.AutoRunResult
import app.squeeze.auto.AutoRunner
import app.squeeze.auto.AutomationLogEntry
import app.squeeze.auto.RunOptions
import app.squeeze.auto.Trigger
import app.squeeze.automation.AutomationService
import app.squeeze.data.AutoSettingsRepository
import app.squeeze.data.PresetRepository
import app.squeeze.engine.Preset
import app.squeeze.finalize.MediaStoreWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The outcome of the last "Dry run" or "Run now", shown under the buttons. */
data class RunUi(val busy: Boolean = false, val result: AutoRunResult? = null, val dryRun: Boolean = false)

data class AutoUi(
    val config: AutoConfig = AutoConfig(),
    val presets: List<Preset> = emptyList(),
    val canManageMedia: Boolean = false,
    val hasMediaAccess: Boolean = false,
    val automationEnabled: Boolean = false,
    val automationToken: String? = null,
    val automationEvents: Boolean = false,
    val log: List<AutomationLogEntry> = emptyList(),
    val run: RunUi = RunUi(),
)

@HiltViewModel
class AutoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AutoSettingsRepository,
    private val runner: AutoRunner,
    presets: PresetRepository,
    private val store: MediaStoreWriter,
) : ViewModel() {
    private val run = MutableStateFlow(RunUi())
    private val access = MutableStateFlow(readAccess())

    val ui: StateFlow<AutoUi> = combine(
        settings.config, presets.observeAll(), settings.automationEnabled, settings.automationToken,
        settings.automationEvents, settings.automationLog, run, access,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        AutoUi(
            config = values[0] as AutoConfig,
            presets = values[1] as List<Preset>,
            canManageMedia = store.canManageMedia(),
            hasMediaAccess = values[7] as Boolean,
            automationEnabled = values[2] as Boolean,
            automationToken = values[3] as String?,
            automationEvents = values[4] as Boolean,
            log = values[5] as List<AutomationLogEntry>,
            run = values[6] as RunUi,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoUi())

    private fun readAccess() =
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    /** Call when the screen comes back, e.g. from a system settings page. */
    fun refreshAccess() {
        access.update { readAccess() }
    }

    fun update(change: (AutoConfig) -> AutoConfig) {
        viewModelScope.launch { settings.update(change) }
    }

    fun dryRun() = launchRun(dry = true)

    fun runNow() = launchRun(dry = false)

    fun stop() {
        viewModelScope.launch { runner.stop() }
    }

    fun clearResult() {
        run.value = RunUi()
    }

    private fun launchRun(dry: Boolean) {
        viewModelScope.launch {
            run.value = RunUi(busy = true, dryRun = dry)
            val result = runner.run(RunOptions(Trigger.MANUAL, dryRun = dry))
            run.value = RunUi(busy = false, result = result, dryRun = dry)
        }
    }

    fun setAutomation(enabled: Boolean) {
        viewModelScope.launch { settings.setAutomationEnabled(enabled, AutomationService::newToken) }
    }

    fun regenerateToken() {
        viewModelScope.launch { settings.regenerateToken(AutomationService.newToken()) }
    }

    fun setEvents(enabled: Boolean) {
        viewModelScope.launch { settings.setAutomationEvents(enabled) }
    }

    fun clearLog() {
        viewModelScope.launch { settings.clearLog() }
    }
}
