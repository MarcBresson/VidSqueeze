package app.squeeze.feature.presets

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.data.PresetRepository
import app.squeeze.data.SettingsRepository
import app.squeeze.engine.BuiltInPresets
import app.squeeze.engine.Preset
import app.squeeze.feature.compress.SheetMessage
import app.squeeze.presets.ImportResult
import app.squeeze.presets.PresetTransfer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PresetsUi(
    val presets: List<Preset> = BuiltInPresets.all,
    val defaultPresetId: String = BuiltInPresets.SHARE_ID,
    val message: SheetMessage? = null,
)

@HiltViewModel
class PresetsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PresetRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val message = MutableStateFlow<SheetMessage?>(null)

    val ui: StateFlow<PresetsUi> = combine(repository.observeAll(), settings.defaultPresetId, message) { list, default, msg ->
        PresetsUi(list, default, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PresetsUi())

    fun setDefault(id: String) {
        viewModelScope.launch { settings.setDefaultPreset(id) }
    }

    fun messageShown() = message.update { null }

    /** Writes the user's presets (or all of them when [includeBuiltIn]) to a file and returns a shareable URI. */
    suspend fun exportFile(includeBuiltIn: Boolean): Uri? = withContext(Dispatchers.IO) {
        val chosen = repository.all().filter { includeBuiltIn || !it.builtIn }
        if (chosen.isEmpty()) return@withContext null
        val dir = File(context.cacheDir, "presets").apply { mkdirs() }
        val file = File(dir, "squeeze-presets.json").apply { writeText(PresetTransfer.export(chosen)) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            if (text == null) {
                message.value = SheetMessage.ImportFailed(context.getString(app.squeeze.R.string.queue_import_failed))
                return@launch
            }
            val names = repository.all().map { it.name }
            when (val result = PresetTransfer.import(text, names) { "user.${UUID.randomUUID()}" }) {
                is ImportResult.Ok -> {
                    repository.importAll(result.presets)
                    message.value = SheetMessage.Imported(result.presets.size)
                }
                is ImportResult.Invalid -> message.value = SheetMessage.ImportFailed(result.reason)
            }
        }
    }
}
