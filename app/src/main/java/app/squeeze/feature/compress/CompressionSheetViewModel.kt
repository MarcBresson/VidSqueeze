package app.squeeze.feature.compress

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.data.BatchSource
import app.squeeze.data.PresetRepository
import app.squeeze.data.SettingsRepository
import app.squeeze.data.SourceImporter
import app.squeeze.engine.BuiltInPresets
import app.squeeze.engine.CommandPreview
import app.squeeze.engine.ExportOptions
import app.squeeze.engine.FfmpegKitEngine
import app.squeeze.engine.FfmpegParams
import app.squeeze.engine.Preset
import app.squeeze.engine.SizeEstimator
import app.squeeze.engine.SourceInfo
import app.squeeze.work.QueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-shot feedback for the sheet; the UI maps each to a string. */
sealed interface SheetMessage {
    data object Saved : SheetMessage
    data object Updated : SheetMessage
    data object Renamed : SheetMessage
    data object Deleted : SheetMessage
    data class Imported(val count: Int) : SheetMessage
    data class ImportFailed(val reason: String) : SheetMessage
}

data class SheetUi(
    val presets: List<Preset> = BuiltInPresets.all,
    /** The selected preset as stored. */
    val base: Preset = BuiltInPresets.share,
    /** The working copy the user is editing; equals [base] until something is changed. */
    val draft: Preset = BuiltInPresets.share,
    val defaultPresetId: String = BuiltInPresets.SHARE_ID,
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val firstFileName: String? = null,
    val source: SourceInfo? = null,
    val estimatedBytes: Long? = null,
    val preview: CommandPreview = CommandPreview.of(BuiltInPresets.share, null),
    val message: SheetMessage? = null,
) {
    val dirty: Boolean get() = draft.params != base.params || draft.export != base.export
    val canStart: Boolean get() = !preview.hasErrors
}

/**
 * State behind the compression sheet and the preset editor: the preset list, the working copy of
 * the selected preset, the files about to be compressed and everything derived from them
 * (command preview, size estimate).
 */
@HiltViewModel
class CompressionSheetViewModel @Inject constructor(
    private val presets: PresetRepository,
    private val settings: SettingsRepository,
    private val queue: QueueManager,
    private val importer: SourceImporter,
    private val engine: FfmpegKitEngine,
) : ViewModel() {
    private val _ui = MutableStateFlow(SheetUi())
    val ui: StateFlow<SheetUi> = _ui.asStateFlow()

    private var uris: List<Uri> = emptyList()
    private var firstFileBytes = 0L
    private var probeJob: Job? = null

    /** Set once the user (or a screen) has chosen a preset, so the initial lookup does not override it. */
    private var selectionMade = false

    init {
        viewModelScope.launch { presets.observeAll().collect { list -> onPresetsChanged(list) } }
        viewModelScope.launch { settings.defaultPresetId.collect { id -> _ui.update { it.copy(defaultPresetId = id) } } }
        viewModelScope.launch {
            val start = presets.getOrDefault(settings.startPresetId())
            if (!selectionMade) select(start)
        }
    }

    /** Points the sheet at the files to compress. Safe to call again with the same or a new selection. */
    fun loadFiles(files: List<Uri>) {
        if (files == uris) return
        uris = files
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            val described = files.map { importer.describe(it) }
            firstFileBytes = described.firstOrNull()?.second ?: 0L
            _ui.update {
                it.copy(
                    fileCount = files.size,
                    totalBytes = described.sumOf { d -> d.second },
                    firstFileName = described.firstOrNull()?.first,
                    source = null,
                )
            }
            recompute()
            val first = files.firstOrNull() ?: return@launch
            val source = try {
                engine.probe(engine.inputFor(first), described.first().first.substringAfterLast('.', ""))
            } catch (e: Exception) {
                Log.w(TAG, "Could not probe $first", e)
                null
            }
            _ui.update { it.copy(source = source) }
            recompute()
        }
    }

    fun selectPreset(id: String) {
        viewModelScope.launch { presets.get(id)?.let { select(it) } }
    }

    fun editParams(change: (FfmpegParams) -> FfmpegParams) {
        _ui.update { it.copy(draft = it.draft.copy(params = change(it.draft.params))) }
        recompute()
    }

    fun editExport(change: (ExportOptions) -> ExportOptions) {
        _ui.update { it.copy(draft = it.draft.copy(export = change(it.draft.export))) }
        recompute()
    }

    fun revert() {
        _ui.update { it.copy(draft = it.base) }
        recompute()
    }

    /** Stores the working copy as a new preset and selects it. */
    fun saveAs(name: String) {
        viewModelScope.launch {
            val d = _ui.value.draft
            select(presets.create(name, d.params, d.export))
            _ui.update { it.copy(message = SheetMessage.Saved) }
        }
    }

    /** Writes the working copy over the selected user preset. */
    fun updateSelected() {
        viewModelScope.launch {
            val ui = _ui.value
            if (ui.base.builtIn) return@launch
            val updated = ui.draft.copy(id = ui.base.id, name = ui.base.name)
            if (presets.update(updated)) {
                select(updated)
                _ui.update { it.copy(message = SheetMessage.Updated) }
            }
        }
    }

    fun rename(name: String) {
        viewModelScope.launch {
            val base = _ui.value.base
            if (!presets.rename(base.id, name)) return@launch
            val renamed = presets.get(base.id) ?: return@launch
            // Keep unsaved edits; only the name changes.
            _ui.update { it.copy(base = renamed, draft = it.draft.copy(name = renamed.name), message = SheetMessage.Renamed) }
        }
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ui = _ui.value
            if (ui.base.builtIn) return@launch
            presets.delete(ui.base.id)
            if (ui.defaultPresetId == ui.base.id) settings.setDefaultPreset(BuiltInPresets.SHARE_ID)
            select(presets.getOrDefault(null))
            _ui.update { it.copy(message = SheetMessage.Deleted) }
        }
    }

    fun duplicateSelected() {
        viewModelScope.launch {
            val copy = presets.duplicate(_ui.value.base.id) ?: return@launch
            select(copy)
            _ui.update { it.copy(message = SheetMessage.Saved) }
        }
    }

    fun setDefault(id: String = _ui.value.base.id) {
        viewModelScope.launch { settings.setDefaultPreset(id) }
    }

    fun messageShown() = _ui.update { it.copy(message = null) }

    /**
     * Queues the files with the working copy. Returns false when nothing was started (no files, or
     * the settings have errors). [onResult] runs on a background thread once the files are imported.
     */
    fun start(source: BatchSource, onResult: (Result<Long>) -> Unit = {}): Boolean {
        val ui = _ui.value
        if (uris.isEmpty() || !ui.canStart) return false
        // A modified copy is recorded under an honest name so history does not blame the saved preset.
        val snapshot = if (ui.dirty) ui.draft.copy(name = "${ui.base.name} (edited)") else ui.draft
        viewModelScope.launch { settings.setLastUsedPreset(ui.base.id) }
        queue.enqueueInBackground(uris, snapshot, source, onResult)
        return true
    }

    private suspend fun onPresetsChanged(list: List<Preset>) {
        val selected = _ui.value.base
        _ui.update { it.copy(presets = list) }
        // The selected preset may have been deleted or replaced elsewhere (an import, say).
        if (list.none { it.id == selected.id }) select(presets.getOrDefault(null))
    }

    private fun select(preset: Preset) {
        selectionMade = true
        _ui.update { it.copy(base = preset, draft = preset) }
        recompute()
    }

    private fun recompute() {
        _ui.update { ui ->
            val source = ui.source
            val estimate = source?.let { s ->
                val perFile = SizeEstimator.estimateBytes(ui.draft.params, s, firstFileBytes)
                if (perFile != null && firstFileBytes > 0) (perFile.toDouble() / firstFileBytes * ui.totalBytes).toLong() else null
            }
            ui.copy(preview = CommandPreview.of(ui.draft, source), estimatedBytes = estimate)
        }
    }

    private companion object {
        const val TAG = "CompressionSheetVM"
    }
}
