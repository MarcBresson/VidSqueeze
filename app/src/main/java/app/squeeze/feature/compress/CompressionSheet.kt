package app.squeeze.feature.compress

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.R
import app.squeeze.engine.Issue
import app.squeeze.engine.Severity

/**
 * The compression dialog: preset picker, encoding and export settings, live command, and a footer
 * with the size estimate and Start. Shared by the share target and the in-app picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressionSheet(vm: CompressionSheetViewModel, onDismiss: () -> Unit, onStart: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PresetHeader(ui, vm)
                PresetForm(ui, vm)
                CommandPreviewCard(ui)
            }
            SheetFooter(ui, onStart)
        }
    }
    MessageToast(ui.message, vm::messageShown)
}

/** Preset selector with the Save as / Update / Rename / Delete menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetHeader(ui: SheetUi, vm: CompressionSheetViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val edited = stringResource(R.string.sheet_edited)
    val shown = if (ui.dirty) "${ui.base.name} ($edited)" else ui.base.name

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = shown,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.sheet_preset)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ui.presets.forEach { p ->
                    val badge = if (p.id == ui.defaultPresetId) " · ${stringResource(R.string.sheet_default_badge)}" else ""
                    DropdownMenuItem(
                        text = { Text(p.name + badge) },
                        onClick = {
                            expanded = false
                            vm.selectPreset(p.id)
                        },
                    )
                }
            }
        }
        Column {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sheet_save_as)) },
                    onClick = { menu = false; nameDialog = NameDialog.SaveAs },
                )
                if (!ui.base.builtIn) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sheet_update)) },
                        enabled = ui.dirty,
                        onClick = { menu = false; vm.updateSelected() },
                    )
                }
                if (ui.dirty) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.sheet_revert)) }, onClick = { menu = false; vm.revert() })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.sheet_duplicate)) }, onClick = { menu = false; vm.duplicateSelected() })
                if (ui.base.id != ui.defaultPresetId) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.sheet_set_default)) }, onClick = { menu = false; vm.setDefault() })
                }
                if (!ui.base.builtIn) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.sheet_rename)) }, onClick = { menu = false; nameDialog = NameDialog.Rename })
                    DropdownMenuItem(text = { Text(stringResource(R.string.sheet_delete)) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }

    if (ui.dirty) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!ui.base.builtIn) Button(onClick = vm::updateSelected) { Text(stringResource(R.string.sheet_update)) }
            TextButton(onClick = { nameDialog = NameDialog.SaveAs }) { Text(stringResource(R.string.sheet_save_as)) }
            TextButton(onClick = vm::revert) { Text(stringResource(R.string.sheet_revert)) }
        }
    }

    nameDialog?.let { kind ->
        NameInputDialog(
            initial = if (kind == NameDialog.Rename) ui.base.name else "",
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                nameDialog = null
                if (kind == NameDialog.SaveAs) vm.saveAs(name) else vm.rename(name)
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.sheet_delete_title)) },
            text = { Text(stringResource(R.string.sheet_delete_body)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) { Text(stringResource(R.string.sheet_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.sheet_cancel)) } },
        )
    }
}

private enum class NameDialog { SaveAs, Rename }

@Composable
private fun NameInputDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sheet_name_label)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(stringResource(R.string.sheet_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sheet_cancel)) } },
    )
}

@Composable
fun CommandPreviewCard(ui: SheetUi) {
    val context = LocalContext.current
    val copied = stringResource(R.string.cmd_copied)
    Text(stringResource(R.string.sec_command), style = MaterialTheme.typography.titleMedium)
    ui.preview.issues.forEach { IssueLine(it) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val command = ui.preview.command
            if (command == null) {
                Text(stringResource(R.string.cmd_none), style = MaterialTheme.typography.bodySmall)
            } else {
                SelectionContainer {
                    Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                if (ui.source == null) {
                    Text(stringResource(R.string.cmd_sample_note), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ffmpeg", command))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.cmd_copy)) }
            }
        }
    }
}

@Composable
private fun IssueLine(issue: Issue) {
    val color = when (issue.severity) {
        Severity.ERROR -> MaterialTheme.colorScheme.error
        Severity.WARNING -> MaterialTheme.colorScheme.tertiary
        Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(issue.message, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SheetFooter(ui: SheetUi, onStart: () -> Unit) {
    val context = LocalContext.current
    val total = Formatter.formatShortFileSize(context, ui.totalBytes)
    val summary = pluralStringResource(R.plurals.footer_files, ui.fileCount, ui.fileCount, total).let { files ->
        ui.estimatedBytes?.let { est ->
            "$files\n" + stringResource(R.string.footer_estimate, total, Formatter.formatShortFileSize(context, est))
        } ?: files
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Button(enabled = ui.canStart && ui.fileCount > 0, onClick = onStart) { Text(stringResource(R.string.sheet_start)) }
    }
}

/** Shows [message] as a toast once and then clears it. */
@Composable
fun MessageToast(message: SheetMessage?, onShown: () -> Unit) {
    val context = LocalContext.current
    val text = message?.let { messageText(context, it) }
    LaunchedEffect(message) {
        if (text != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            onShown()
        }
    }
}

private fun messageText(context: Context, m: SheetMessage): String = when (m) {
    SheetMessage.Saved -> context.getString(R.string.msg_saved)
    SheetMessage.Updated -> context.getString(R.string.msg_updated)
    SheetMessage.Renamed -> context.getString(R.string.msg_renamed)
    SheetMessage.Deleted -> context.getString(R.string.msg_deleted)
    is SheetMessage.Imported -> context.getString(R.string.msg_imported, m.count)
    is SheetMessage.ImportFailed -> m.reason
}
