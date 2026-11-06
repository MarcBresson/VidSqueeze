package app.squeeze.feature.settings

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.R
import app.squeeze.data.Retention
import app.squeeze.data.ThemeMode
import app.squeeze.feature.auto.SwitchRow
import app.squeeze.work.NotificationHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenPermissions: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenAbout: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AppearanceSection(ui, vm) }
            item { DefaultsSection(ui, vm) }
            item { FilterBucketsSection(ui, vm) }
            item { NotificationsSection(vm) }
            item { StorageSection(ui, vm) }
            item {
                SectionCard(stringResource(R.string.settings_more)) {
                    LinkRow(stringResource(R.string.perm_title), stringResource(R.string.settings_permissions_hint), onOpenPermissions)
                    LinkRow(stringResource(R.string.caps_title), stringResource(R.string.settings_capabilities_hint), onOpenCapabilities)
                    LinkRow(stringResource(R.string.about_title), stringResource(R.string.settings_about_hint), onOpenAbout)
                }
            }
            item { Column(Modifier.padding(bottom = 24.dp)) {} }
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
private fun LinkRow(title: String, hint: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(ui: SettingsUi, vm: SettingsViewModel) {
    SectionCard(stringResource(R.string.settings_appearance)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = ui.themeMode == mode,
                    onClick = { vm.setThemeMode(mode) },
                    label = {
                        Text(
                            stringResource(
                                when (mode) {
                                    ThemeMode.SYSTEM -> R.string.settings_theme_system
                                    ThemeMode.LIGHT -> R.string.settings_theme_light
                                    ThemeMode.DARK -> R.string.settings_theme_dark
                                },
                            ),
                        )
                    },
                )
            }
        }
        SwitchRow(stringResource(R.string.settings_dynamic_color), ui.dynamicColor, vm::setDynamicColor)
    }
}

@Composable
private fun DefaultsSection(ui: SettingsUi, vm: SettingsViewModel) {
    SectionCard(stringResource(R.string.settings_defaults)) {
        var presetMenu by rememberSaveable { mutableStateOf(false) }
        Text(stringResource(R.string.settings_default_preset), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_default_preset_hint), style = MaterialTheme.typography.bodySmall)
        val current = ui.presets.firstOrNull { it.id == ui.defaultPresetId }
        OutlinedButton(onClick = { presetMenu = true }) { Text(current?.name ?: stringResource(R.string.settings_preset_missing)) }
        DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
            ui.presets.forEach { preset ->
                DropdownMenuItem(text = { Text(preset.name) }, onClick = { presetMenu = false; vm.setDefaultPreset(preset.id) })
            }
        }

        Text(stringResource(R.string.settings_retention), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_retention_hint), style = MaterialTheme.typography.bodySmall)
        Column(Modifier.fillMaxWidth()) {
            Retention.CHOICES.forEach { days ->
                RadioChoice(
                    label = if (days == Retention.NEVER) stringResource(R.string.settings_retention_never)
                    else pluralStringResource(R.plurals.settings_days, days, days),
                    selected = ui.retentionDays == days,
                    onSelect = { vm.setRetentionDays(days) },
                )
            }
        }

        SwitchRow(stringResource(R.string.settings_replace_name), ui.replaceKeepsOriginalName, vm::setReplaceKeepsOriginalName)
        Text(stringResource(R.string.settings_replace_name_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RadioChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationsSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    fun open(channel: String?) {
        vm.ensureChannels()
        val intent = if (channel == null) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        }
        context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    SectionCard(stringResource(R.string.settings_notifications)) {
        Text(stringResource(R.string.settings_notifications_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open(NotificationHelper.CHANNEL_PROGRESS) }) { Text(stringResource(R.string.channel_progress)) }
            OutlinedButton(onClick = { open(NotificationHelper.CHANNEL_RESULTS) }) { Text(stringResource(R.string.channel_results)) }
            TextButton(onClick = { open(null) }) { Text(stringResource(R.string.settings_notifications_all)) }
        }
    }
}

@Composable
private fun StorageSection(ui: SettingsUi, vm: SettingsViewModel) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    SectionCard(stringResource(R.string.settings_storage)) {
        val bytes = ui.stagingBytes
        Text(
            if (bytes == null) stringResource(R.string.settings_storage_measuring)
            else stringResource(R.string.settings_storage_used, Formatter.formatShortFileSize(context, bytes)),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.settings_storage_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { confirm = true }, enabled = bytes != null) { Text(stringResource(R.string.settings_storage_clear)) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.settings_storage_clear_title)) },
            text = { Text(stringResource(R.string.settings_storage_clear_body)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.clearStaging() }) { Text(stringResource(R.string.settings_storage_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.sheet_cancel)) } },
        )
    }
}

/** Max thresholds the size chips split sizes into; one more than the max row count. */
private const val MAX_BUCKET_THRESHOLDS = 4

/** Edits the MB boundaries of the size chips in History's filters; each valid edit applies immediately. */
@Composable
private fun FilterBucketsSection(ui: SettingsUi, vm: SettingsViewModel) {
    // The text rows are seeded from the stored values once per row-count change; edits apply live.
    var texts by remember(ui.sizeBuckets.size) { mutableStateOf(ui.sizeBuckets.map { it.toString() }) }
    LaunchedEffect(ui.sizeBuckets) {
        // Keep empty seeds (a fresh install) in sync with the repository defaults.
        if (texts.all { it.isBlank() } && ui.sizeBuckets.isNotEmpty()) texts = ui.sizeBuckets.map { it.toString() }
    }
    SectionCard(stringResource(R.string.settings_filter_buckets)) {
        Text(stringResource(R.string.settings_filter_buckets_hint), style = MaterialTheme.typography.bodySmall)
        texts.forEachIndexed { index, text ->
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        texts = texts.toMutableList().also { it[index] = value }
                        value.toLongOrNull()?.takeIf { mb -> mb > 0 }?.let { mb ->
                            val updated = texts.mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.distinct().sorted()
                            if (updated.isNotEmpty()) vm.setSizeBuckets(updated)
                        }
                    },
                    label = { Text("MB") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                if (texts.size > 1) {
                    IconButton(onClick = {
                        texts = texts.filterIndexed { i, _ -> i != index }
                        val updated = texts.mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.distinct().sorted()
                        if (updated.isNotEmpty()) vm.setSizeBuckets(updated)
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.history_filters_clear))
                    }
                }
            }
        }
        if (texts.size < MAX_BUCKET_THRESHOLDS) {
            TextButton(onClick = { texts = texts + "" }) { Text("+ Add threshold") }
        }
    }
}
