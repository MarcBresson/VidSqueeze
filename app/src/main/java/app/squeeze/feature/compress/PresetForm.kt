package app.squeeze.feature.compress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.squeeze.R
import app.squeeze.engine.AudioCodec
import app.squeeze.engine.CodecContainerMatrix
import app.squeeze.engine.Container
import app.squeeze.engine.DynamicRange
import app.squeeze.engine.EncoderPreset
import app.squeeze.engine.ExportMode
import app.squeeze.engine.FfmpegParams
import app.squeeze.engine.ParamOptions
import app.squeeze.engine.ParamOptions.label
import app.squeeze.engine.PixelFormat
import app.squeeze.engine.QualityHint
import app.squeeze.engine.RateControl
import app.squeeze.engine.RateMode
import app.squeeze.engine.VideoCodec
import app.squeeze.finalize.NameContext
import app.squeeze.finalize.NameTemplate
import java.time.LocalDate

/** Encoding and export settings of a preset. Used by the compression sheet and the preset editor. */
@Composable
fun PresetForm(ui: SheetUi, vm: CompressionSheetViewModel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EncodingSection(ui.draft.params, vm)
        ExportSection(ui, vm)
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun Note(text: String, error: Boolean = false) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) = FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(0.dp),
) { content() }

@Composable
private fun Choice(selected: Boolean, label: String, enabled: Boolean = true, onClick: () -> Unit) =
    FilterChip(selected = selected, enabled = enabled, onClick = onClick, label = { Text(label) })

// ---------------------------------------------------------------------------------------------
// Encoding
// ---------------------------------------------------------------------------------------------

@Composable
private fun EncodingSection(p: FfmpegParams, vm: CompressionSheetViewModel) {
    SectionTitle(stringResource(R.string.sec_encoding))

    // Video codec
    Label(stringResource(R.string.enc_video))
    Chips {
        VideoCodec.entries.forEach { c ->
            Choice(p.videoCodec == c, c.label()) { vm.editParams { ParamOptions.withVideoCodec(it, c) } }
        }
    }
    when (p.videoCodec) {
        VideoCodec.AV1 -> Note(stringResource(R.string.enc_av1_note))
        VideoCodec.COPY -> Note(stringResource(R.string.enc_copy_note))
        else -> Unit
    }

    if (ParamOptions.usesRateControl(p)) {
        RateSection(p, vm)
        Label(stringResource(R.string.enc_speed, p.encoderPreset.ffName))
        Slider(
            value = p.encoderPreset.ordinal.toFloat(),
            onValueChange = { v -> vm.editParams { it.copy(encoderPreset = EncoderPreset.entries[v.toInt()]) } },
            valueRange = 0f..(EncoderPreset.entries.size - 1).toFloat(),
            steps = EncoderPreset.entries.size - 2,
        )
        Note(stringResource(R.string.enc_speed_hint))

        FrameRateAndResolution(p, vm)
    }

    AudioSection(p, vm)
    ContainerSection(p, vm)
    AdvancedSection(p, vm)
}

@Composable
private fun RateSection(p: FfmpegParams, vm: CompressionSheetViewModel) {
    Label(stringResource(R.string.enc_rate))
    val mode = ParamOptions.rateMode(p.rateControl)
    Chips {
        Choice(mode == RateMode.CRF, stringResource(R.string.enc_mode_crf)) { vm.editParams { ParamOptions.withRateMode(it, RateMode.CRF) } }
        Choice(mode == RateMode.BITRATE, stringResource(R.string.enc_mode_bitrate)) { vm.editParams { ParamOptions.withRateMode(it, RateMode.BITRATE) } }
        Choice(mode == RateMode.PERCENT, stringResource(R.string.enc_mode_percent)) { vm.editParams { ParamOptions.withRateMode(it, RateMode.PERCENT) } }
    }
    when (val rate = p.rateControl) {
        is RateControl.Crf -> {
            val range = ParamOptions.crfRange(p.videoCodec)
            val hint = when (ParamOptions.qualityHint(p.videoCodec, rate.value)) {
                QualityHint.NEAR_LOSSLESS -> R.string.enc_hint_near_lossless
                QualityHint.HIGH -> R.string.enc_hint_high
                QualityHint.BALANCED -> R.string.enc_hint_balanced
                QualityHint.SMALL -> R.string.enc_hint_small
                QualityHint.VERY_SMALL -> R.string.enc_hint_very_small
            }
            Text(stringResource(R.string.enc_crf_value, rate.value, stringResource(hint)))
            Slider(
                value = rate.value.toFloat(),
                onValueChange = { v -> vm.editParams { it.copy(rateControl = RateControl.Crf(v.toInt())) } },
                valueRange = range.first.toFloat()..range.last.toFloat(),
            )
        }
        is RateControl.BitrateKbps -> NumberField(
            stringResource(R.string.enc_bitrate_label), rate.kbps, 50..200_000,
        ) { v -> vm.editParams { it.copy(rateControl = RateControl.BitrateKbps(v)) } }
        is RateControl.PercentOfSource -> NumberField(
            stringResource(R.string.enc_percent_label), rate.percent, 5..100,
        ) { v -> vm.editParams { it.copy(rateControl = RateControl.PercentOfSource(v)) } }
        is RateControl.ConstantQuality -> Unit // hardware encoders only, not offered yet
    }
}

@Composable
private fun FrameRateAndResolution(p: FfmpegParams, vm: CompressionSheetViewModel) {
    Label(stringResource(R.string.enc_fps))
    Chips {
        Choice(p.fpsMax == null, stringResource(R.string.enc_fps_keep)) { vm.editParams { it.copy(fpsMax = null) } }
        ParamOptions.FPS_CHOICES.forEach { f ->
            Choice(p.fpsMax == f, stringResource(R.string.enc_fps_value, f)) { vm.editParams { it.copy(fpsMax = f) } }
        }
    }
    Note(stringResource(R.string.enc_fps_note))

    Label(stringResource(R.string.enc_resolution))
    val custom = p.maxShortSide != null && p.maxShortSide !in ParamOptions.RESOLUTIONS
    var customOpen by rememberSaveable { mutableStateOf(custom) }
    Chips {
        Choice(p.maxShortSide == null, stringResource(R.string.enc_resolution_original)) {
            customOpen = false
            vm.editParams { it.copy(maxShortSide = null) }
        }
        ParamOptions.RESOLUTIONS.forEach { r ->
            Choice(p.maxShortSide == r, stringResource(R.string.enc_resolution_value, r)) {
                customOpen = false
                vm.editParams { it.copy(maxShortSide = r) }
            }
        }
        Choice(custom || customOpen, stringResource(R.string.enc_resolution_custom)) {
            customOpen = true
            if (p.maxShortSide == null || p.maxShortSide in ParamOptions.RESOLUTIONS) vm.editParams { it.copy(maxShortSide = 540) }
        }
    }
    if (custom || customOpen) {
        NumberField(stringResource(R.string.enc_resolution_custom), p.maxShortSide ?: 540, 120..4320) { v ->
            vm.editParams { it.copy(maxShortSide = v) }
        }
    }
}

@Composable
private fun AudioSection(p: FfmpegParams, vm: CompressionSheetViewModel) {
    Label(stringResource(R.string.enc_audio))
    Chips {
        AudioCodec.entries.forEach { a ->
            val allowed = a == AudioCodec.COPY || p.container == Container.ORIGINAL ||
                a in CodecContainerMatrix.audioCodecsFor(p.container)
            Choice(p.audioCodec == a, a.label(), enabled = allowed) { vm.editParams { it.copy(audioCodec = a) } }
        }
    }
    if (p.audioCodec != AudioCodec.COPY) {
        Text(stringResource(R.string.enc_audio_bitrate, p.audioBitrateK))
        Chips {
            ParamOptions.AUDIO_BITRATES.forEach { b ->
                Choice(p.audioBitrateK == b, "$b") { vm.editParams { it.copy(audioBitrateK = b) } }
            }
        }
    }
}

@Composable
private fun ContainerSection(p: FfmpegParams, vm: CompressionSheetViewModel) {
    Label(stringResource(R.string.enc_container))
    val reasons = Container.entries.mapNotNull { ParamOptions.containerDisabledReason(it, p) }
    Chips {
        Container.entries.forEach { c ->
            val label = if (c == Container.ORIGINAL) stringResource(R.string.enc_container_original) else c.extension.uppercase()
            Choice(p.container == c, label, enabled = ParamOptions.containerDisabledReason(c, p) == null) {
                vm.editParams { it.copy(container = c) }
            }
        }
    }
    if (reasons.isNotEmpty()) Note(reasons.joinToString(" · "))
}

@Composable
private fun AdvancedSection(p: FfmpegParams, vm: CompressionSheetViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(stringResource(R.string.sec_advanced) + if (open) " ▴" else " ▾") }
    if (!open) return

    Label(stringResource(R.string.enc_dynamic_range))
    Chips {
        Choice(p.dynamicRange == DynamicRange.AUTO_SDR_IF_UNSUPPORTED, stringResource(R.string.enc_hdr_auto)) {
            vm.editParams { it.copy(dynamicRange = DynamicRange.AUTO_SDR_IF_UNSUPPORTED) }
        }
        Choice(p.dynamicRange == DynamicRange.KEEP, stringResource(R.string.enc_hdr_keep)) {
            vm.editParams { it.copy(dynamicRange = DynamicRange.KEEP) }
        }
        Choice(p.dynamicRange == DynamicRange.FORCE_SDR, stringResource(R.string.enc_hdr_sdr)) {
            vm.editParams { it.copy(dynamicRange = DynamicRange.FORCE_SDR) }
        }
    }

    Label(stringResource(R.string.enc_pixel_format))
    Chips {
        PixelFormat.entries.forEach { f ->
            val label = when (f) {
                PixelFormat.AUTO -> stringResource(R.string.enc_pix_auto)
                PixelFormat.KEEP_SOURCE -> stringResource(R.string.enc_pix_keep)
                else -> f.ffName.orEmpty()
            }
            Choice(p.pixelFormat == f, label) { vm.editParams { it.copy(pixelFormat = f) } }
        }
    }

    OutlinedTextField(
        value = p.customArgs,
        onValueChange = { v -> vm.editParams { it.copy(customArgs = v) } },
        label = { Text(stringResource(R.string.enc_custom_args)) },
        supportingText = { Text(stringResource(R.string.enc_custom_args_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(
            checked = p.customArgsReplaceGenerated,
            onCheckedChange = { v -> vm.editParams { it.copy(customArgsReplaceGenerated = v) } },
        )
        Text(stringResource(R.string.enc_custom_replace), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    // Edit as text so the field can be empty while typing; only valid numbers are committed.
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.let { onValue(it.coerceIn(range)) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportSection(ui: SheetUi, vm: CompressionSheetViewModel) {
    val e = ui.draft.export
    SectionTitle(stringResource(R.string.sec_export))

    Label(stringResource(R.string.exp_mode))
    Chips {
        Choice(e.mode == ExportMode.ASK_WHEN_DONE, stringResource(R.string.exp_ask)) { vm.editExport { it.copy(mode = ExportMode.ASK_WHEN_DONE) } }
        Choice(e.mode == ExportMode.REPLACE_ORIGINALS, stringResource(R.string.exp_replace)) { vm.editExport { it.copy(mode = ExportMode.REPLACE_ORIGINALS) } }
        Choice(e.mode == ExportMode.SAVE_COPIES, stringResource(R.string.exp_save)) { vm.editExport { it.copy(mode = ExportMode.SAVE_COPIES) } }
    }

    // Replacing keeps the original name; the others need a name for the new file.
    if (e.mode != ExportMode.REPLACE_ORIGINALS) {
        OutlinedTextField(
            value = e.nameTemplate,
            onValueChange = { v -> vm.editExport { it.copy(nameTemplate = v) } },
            label = { Text(stringResource(R.string.exp_name_template)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NameTemplate.TOKENS.forEach { token ->
                AssistChip(onClick = { vm.editExport { it.copy(nameTemplate = it.nameTemplate + "{$token}") } }, label = { Text("{$token}") })
            }
        }
        val unknown = NameTemplate.unknownTokens(e.nameTemplate)
        if (unknown.isNotEmpty()) {
            Note(stringResource(R.string.exp_unknown_tokens, unknown.joinToString { "{$it}" }), error = true)
        } else {
            Note(stringResource(R.string.exp_name_preview, namePreview(ui)))
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = e.keepExif, onCheckedChange = { v -> vm.editExport { it.copy(keepExif = v) } })
        Text(stringResource(R.string.exp_keep_exif))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(
            checked = e.keepExif && e.keepLocation,
            enabled = e.keepExif,
            onCheckedChange = { v -> vm.editExport { it.copy(keepLocation = v) } },
        )
        Text(stringResource(R.string.exp_keep_location))
    }
}

private fun namePreview(ui: SheetUi): String {
    val p = ui.draft.params
    val source = ui.source
    val container = ui.preview.container
    val resolution = p.maxShortSide?.let { "${it}p" } ?: "original"
    return NameTemplate.render(
        ui.draft.export.nameTemplate,
        NameContext(
            originalFileName = ui.firstFileName ?: "VID_20240101.mp4",
            outputFormat = (container ?: source?.container ?: Container.MP4).extension,
            presetName = ui.draft.name,
            date = LocalDate.now(),
            resolution = resolution,
        ),
    )
}
