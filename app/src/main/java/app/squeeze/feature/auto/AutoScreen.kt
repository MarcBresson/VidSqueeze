package app.squeeze.feature.auto

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.R
import app.squeeze.auto.AgeTier
import app.squeeze.auto.AutoConfig
import app.squeeze.auto.AutoOrder
import app.squeeze.auto.AutoRuleResolver
import app.squeeze.auto.ScheduleMode
import app.squeeze.auto.TimeWindow
import app.squeeze.engine.ExportMode
import app.squeeze.engine.Preset
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoScreen(vm: AutoViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val config = ui.config
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshAccess() }
    fun askPermissions() = permissions.launch(
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS),
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auto_title)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchRow(stringResource(R.string.auto_enable), config.enabled) { on ->
                            vm.update { it.copy(enabled = on) }
                            if (on && !ui.hasMediaAccess) askPermissions()
                        }
                        Text(stringResource(R.string.auto_intro), style = MaterialTheme.typography.bodySmall)
                        if (!ui.hasMediaAccess) {
                            Text(stringResource(R.string.auto_needs_access), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = ::askPermissions) { Text(stringResource(R.string.auto_grant_access)) }
                        }
                    }
                }
            }
            item { ScheduleSection(config, vm) }
            item { ConditionsSection(config, vm) }
            item { RulesSection(config, ui.presets, vm) }
            item { FiltersSection(config, vm) }
            item { OutputSection(config, ui.canManageMedia, vm) }
            item { RunSection(ui, vm) }
            item { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, and a screen reader announces it as one switch with its label.
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> RadioRow(label: String, value: T, selected: T, onSelect: (T) -> Unit, supporting: String? = null) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Column(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, suffix: String? = null) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

// --- schedule ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSection(config: AutoConfig, vm: AutoViewModel) {
    SectionCard(stringResource(R.string.auto_schedule)) {
        RadioRow(stringResource(R.string.auto_mode_windows), ScheduleMode.WINDOWS, config.mode, { m -> vm.update { it.copy(mode = m) } },
            stringResource(R.string.auto_mode_windows_hint))
        RadioRow(stringResource(R.string.auto_mode_charging), ScheduleMode.WHENEVER_CHARGING, config.mode, { m -> vm.update { it.copy(mode = m) } })
        RadioRow(stringResource(R.string.auto_mode_external), ScheduleMode.EXTERNAL_ONLY, config.mode, { m -> vm.update { it.copy(mode = m) } },
            stringResource(R.string.auto_mode_external_hint))
        if (config.mode == ScheduleMode.WINDOWS) {
            config.windows.forEachIndexed { index, window ->
                WindowEditor(
                    window,
                    onChange = { changed -> vm.update { c -> c.copy(windows = c.windows.toMutableList().also { if (index in it.indices) it[index] = changed }) } },
                    onRemove = { vm.update { c -> c.copy(windows = c.windows.filterIndexed { i, _ -> i != index }) } },
                )
            }
            if (config.windows.none { it.isValid }) {
                Text(stringResource(R.string.auto_no_window), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = {
                vm.update { c -> c.copy(windows = c.windows + TimeWindow(DayOfWeek.entries.toSet(), LocalTime.of(1, 0), LocalTime.of(6, 0))) }
            }) { Text(stringResource(R.string.auto_add_window)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindowEditor(window: TimeWindow, onChange: (TimeWindow) -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val format = remember { DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT) }
    fun pick(initial: LocalTime, set: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, h, m -> set(LocalTime.of(h, m)) }, initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { pick(window.start) { onChange(window.copy(start = it)) } }) { Text(window.start.format(format)) }
            OutlinedButton(onClick = { pick(window.end) { onChange(window.copy(end = it)) } }) { Text(window.end.format(format)) }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.auto_remove)) }
        }
        if (window.wrapsMidnight) Text(stringResource(R.string.auto_window_wraps), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { day ->
                FilterChip(
                    selected = day in window.days,
                    onClick = { onChange(window.copy(days = if (day in window.days) window.days - day else window.days + day)) },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, LocalLocale.current.platformLocale)) },
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

// --- conditions ---

@Composable
private fun ConditionsSection(config: AutoConfig, vm: AutoViewModel) {
    SectionCard(stringResource(R.string.auto_conditions)) {
        SwitchRow(stringResource(R.string.auto_require_charging), config.requireCharging) { on -> vm.update { it.copy(requireCharging = on) } }
        Text(stringResource(R.string.auto_min_battery, config.minBatteryPercent), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = config.minBatteryPercent.toFloat(), onValueChange = { v -> vm.update { it.copy(minBatteryPercent = (v / 5).toInt() * 5) } },
            valueRange = 0f..80f, steps = 15,
        )
    }
}

// --- rules ---

@Composable
private fun RulesSection(config: AutoConfig, presets: List<Preset>, vm: AutoViewModel) {
    SectionCard(stringResource(R.string.auto_rules)) {
        Text(stringResource(R.string.auto_rules_hint), style = MaterialTheme.typography.bodySmall)
        config.tiers.sortedBy { it.minAgeDays }.forEach { tier ->
            TierEditor(
                tier, presets,
                onChange = { changed -> vm.update { c -> c.copy(tiers = c.tiers.map { if (it == tier) changed else it }) } },
                onRemove = { vm.update { c -> c.copy(tiers = c.tiers - tier) } },
            )
        }
        AutoRuleResolver.validate(config.tiers).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = {
            val next = (config.tiers.maxOfOrNull { it.minAgeDays } ?: 0) + 30
            vm.update { c -> c.copy(tiers = c.tiers + AgeTier(next, presets.firstOrNull()?.id ?: app.squeeze.engine.BuiltInPresets.BALANCED_ID)) }
        }) { Text(stringResource(R.string.auto_add_tier)) }
    }
}

@Composable
private fun TierEditor(tier: AgeTier, presets: List<Preset>, onChange: (AgeTier) -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val presetName = presets.firstOrNull { it.id == tier.presetId }?.name ?: stringResource(R.string.auto_missing_preset)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.auto_older_than), tier.minAgeDays, { onChange(tier.copy(minAgeDays = it)) }, Modifier.width(140.dp), stringResource(R.string.auto_days))
            Box(Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text(stringResource(R.string.auto_remove)) }
        }
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { menu = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.auto_use_preset, presetName), maxLines = 1) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                presets.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { menu = false; onChange(tier.copy(presetId = p.id)) }) }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

// --- filters ---

@Composable
private fun FiltersSection(config: AutoConfig, vm: AutoViewModel) {
    SectionCard(stringResource(R.string.auto_filters)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.auto_min_size), config.minSizeMb, { v -> vm.update { it.copy(minSizeMb = v) } }, Modifier.weight(1f), "MB")
            NumberField(stringResource(R.string.auto_min_bitrate), config.minBitrateKbps, { v -> vm.update { it.copy(minBitrateKbps = v) } }, Modifier.weight(1f), "kbit/s")
        }
        var folders by remember { mutableStateOf(config.excludedFolders.joinToString(", ")) }
        OutlinedTextField(
            value = folders,
            onValueChange = { text ->
                folders = text
                vm.update { it.copy(excludedFolders = text.split(',').map(String::trim).filter(String::isNotEmpty)) }
            },
            label = { Text(stringResource(R.string.auto_excluded)) },
            supportingText = { Text(stringResource(R.string.auto_excluded_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.auto_order), style = MaterialTheme.typography.titleSmall)
        RadioRow(stringResource(R.string.auto_order_oldest), AutoOrder.OLDEST_FIRST, config.order, { o -> vm.update { it.copy(order = o) } })
        RadioRow(stringResource(R.string.auto_order_largest), AutoOrder.LARGEST_FIRST, config.order, { o -> vm.update { it.copy(order = o) } })
    }
}

// --- output ---

@Composable
private fun OutputSection(config: AutoConfig, canManageMedia: Boolean, vm: AutoViewModel) {
    val context = LocalContext.current
    SectionCard(stringResource(R.string.auto_output)) {
        RadioRow(stringResource(R.string.auto_output_copies), ExportMode.SAVE_COPIES, config.exportMode, { m -> vm.update { it.copy(exportMode = m) } },
            stringResource(R.string.auto_output_copies_hint))
        RadioRow(stringResource(R.string.auto_output_replace), ExportMode.REPLACE_ORIGINALS, config.exportMode, { m -> vm.update { it.copy(exportMode = m) } },
            stringResource(R.string.auto_output_replace_hint))
        if (config.exportMode == ExportMode.REPLACE_ORIGINALS && !canManageMedia) {
            Text(stringResource(R.string.auto_replace_needs_access), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(R.string.auto_open_access_settings)) }
        }
    }
}

// --- run ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunSection(ui: AutoUi, vm: AutoViewModel) {
    val context = LocalContext.current
    SectionCard(stringResource(R.string.auto_run)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::dryRun, enabled = !ui.run.busy) { Text(stringResource(R.string.auto_dry_run)) }
            Button(onClick = vm::runNow, enabled = !ui.run.busy) { Text(stringResource(R.string.auto_run_now)) }
            TextButton(onClick = vm::stop) { Text(stringResource(R.string.auto_stop)) }
        }
        val result = ui.run.result
        when {
            ui.run.busy -> Text(stringResource(R.string.auto_scanning))
            result?.skippedReason != null -> Text(stringResource(R.string.auto_skipped, result.skippedReason))
            result != null && result.videos == 0 -> Text(stringResource(R.string.auto_nothing_due))
            result != null -> {
                Text(
                    stringResource(if (ui.run.dryRun) R.string.auto_would_compress else R.string.auto_queued) + " " +
                        pluralStringResource(R.plurals.history_videos, result.videos, result.videos) + " · " +
                        Formatter.formatShortFileSize(context, result.bytes),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (result.estimatedSavedBytes > 0) {
                    Text(stringResource(R.string.auto_estimated_saving, Formatter.formatShortFileSize(context, result.estimatedSavedBytes)))
                }
                result.planned.take(PREVIEW_ROWS).forEach {
                    Text(
                        "${it.video.name} · ${Formatter.formatShortFileSize(context, it.video.sizeBytes)} → ${it.preset.name}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (result.videos > PREVIEW_ROWS) Text(stringResource(R.string.auto_and_more, result.videos - PREVIEW_ROWS), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val PREVIEW_ROWS = 8
