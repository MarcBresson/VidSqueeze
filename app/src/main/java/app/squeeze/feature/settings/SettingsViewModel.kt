package app.squeeze.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.data.PresetRepository
import app.squeeze.data.SettingsRepository
import app.squeeze.data.ThemeMode
import app.squeeze.engine.Preset
import app.squeeze.finalize.Finalizer
import app.squeeze.work.ItemProcessor
import app.squeeze.work.NotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUi(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val retentionDays: Int = 14,
    val replaceKeepsOriginalName: Boolean = true,
    val defaultPresetId: String = "",
    val presets: List<Preset> = emptyList(),
    /** Bytes of compressed results (and leftovers) in the app's staging folder; null while measuring. */
    val stagingBytes: Long? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    presets: PresetRepository,
    private val processor: ItemProcessor,
    private val finalizer: Finalizer,
    private val notifications: NotificationHelper,
) : ViewModel() {
    private val staging = MutableStateFlow<Long?>(null)

    val ui: StateFlow<SettingsUi> = combine(
        settings.themeMode, settings.dynamicColor, settings.stagedRetentionDays,
        settings.defaultPresetId, presets.observeAll(), staging, settings.replaceKeepsOriginalName,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SettingsUi(
            themeMode = values[0] as ThemeMode,
            dynamicColor = values[1] as Boolean,
            retentionDays = values[2] as Int,
            defaultPresetId = values[3] as String,
            presets = values[4] as List<Preset>,
            stagingBytes = values[5] as Long?,
            replaceKeepsOriginalName = values[6] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    init {
        measureStaging()
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setDynamicColor(on: Boolean) = viewModelScope.launch { settings.setDynamicColor(on) }

    fun setRetentionDays(days: Int) = viewModelScope.launch { settings.setStagedRetentionDays(days) }

    fun setReplaceKeepsOriginalName(on: Boolean) = viewModelScope.launch { settings.setReplaceKeepsOriginalName(on) }

    fun setDefaultPreset(id: String) = viewModelScope.launch { settings.setDefaultPreset(id) }

    /** Throws away every compressed result nobody used yet. Originals are not touched. */
    fun clearStaging() {
        viewModelScope.launch {
            finalizer.discardAllPending()
            finalizer.pruneEmptyStagingDirs(processor.stagingDir)
            measureStaging()
        }
    }

    /** Channels only exist after the first run; make sure the system settings page has something to show. */
    fun ensureChannels() = notifications.createChannels()

    private fun measureStaging() {
        viewModelScope.launch {
            staging.value = withContext(Dispatchers.IO) { folderSize(processor.stagingDir) }
        }
    }

    private fun folderSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
