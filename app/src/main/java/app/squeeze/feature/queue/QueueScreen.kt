package app.squeeze.feature.queue

import android.Manifest
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.R
import app.squeeze.data.AwaitingBatch
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.ItemStatus
import app.squeeze.data.BatchSource
import app.squeeze.feature.compress.CompressionSheet
import app.squeeze.feature.compress.CompressionSheetViewModel
import app.squeeze.finalize.FinalizeAction
import app.squeeze.finalize.FinalizeActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onOpenPresets: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAuto: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: QueueViewModel = hiltViewModel(),
    sheetVm: CompressionSheetViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val libraryAccess by vm.libraryAccess.collectAsStateWithLifecycle()
    var suggestionsDismissed by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshSuggestions() }
    val libraryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshSuggestions() }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            sheetVm.loadFiles(uris)
            sheetOpen = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    var menu by rememberSaveable { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.nav_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.nav_auto)) }, onClick = { menu = false; onOpenAuto() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.nav_history)) }, onClick = { menu = false; onOpenHistory() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.nav_presets)) }, onClick = { menu = false; onOpenPresets() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.nav_settings)) }, onClick = { menu = false; onOpenSettings() })
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }) { Text(stringResource(R.string.queue_pick)) }
                if (ui.items.isNotEmpty()) {
                    if (ui.paused) {
                        OutlinedButton(onClick = vm::resume) { Text(stringResource(R.string.queue_resume)) }
                    } else {
                        OutlinedButton(onClick = vm::pause) { Text(stringResource(R.string.queue_pause)) }
                    }
                    TextButton(onClick = vm::cancelAll) { Text(stringResource(R.string.queue_cancel_all)) }
                }
            }

            if (ui.paused) Text(stringResource(R.string.queue_paused))

            val showSuggestions = !suggestionsDismissed && (suggestions != null || !libraryAccess)
            if (ui.items.isEmpty() && ui.ready.isEmpty() && !showSuggestions) {
                Text(stringResource(R.string.queue_empty))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showSuggestions) {
                        item(key = "suggestions") {
                            val found = suggestions
                            if (found != null) {
                                SuggestionCard(
                                    found,
                                    onCompress = { sheetVm.loadFiles(found.uris); sheetOpen = true },
                                    onDismiss = { suggestionsDismissed = true },
                                )
                            } else {
                                AccessCard(
                                    onAllow = { libraryPermission.launch(Manifest.permission.READ_MEDIA_VIDEO) },
                                    onDismiss = { suggestionsDismissed = true },
                                )
                            }
                        }
                    }
                    if (ui.ready.isNotEmpty()) {
                        item(key = "ready-title") {
                            Text(stringResource(R.string.ready_title), style = MaterialTheme.typography.titleMedium)
                        }
                        items(ui.ready, key = { "ready-${it.batch.id}" }) { ready ->
                            ReadyCard(ready, onSave = { vm.saveCopies(ready.batch.id) }, onDiscard = { vm.discard(ready.batch.id) })
                        }
                    }
                    items(ui.items, key = { it.id }) { item -> QueueRow(item, onRemove = { vm.remove(item.id) }) }
                }
            }
        }
    }

    if (sheetOpen) {
        CompressionSheet(
            vm = sheetVm,
            onDismiss = { sheetOpen = false },
            onStart = {
                val started = sheetVm.start(BatchSource.PICKER) { result ->
                    if (result.isFailure) {
                        context.mainExecutor.execute {
                            Toast.makeText(context, R.string.queue_import_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                if (started) sheetOpen = false
            },
        )
    }
}

@Composable
private fun QueueRow(item: BatchItemEntity, onRemove: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val size = Formatter.formatShortFileSize(context, item.originalSize)
                val state = when {
                    item.status != ItemStatus.RUNNING -> stringResource(R.string.queue_waiting)
                    item.progress != null -> "${(item.progress * 100).toInt()}%"
                    else -> stringResource(R.string.notif_preparing)
                }
                Text("$size · $state", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRemove) { Text(stringResource(R.string.queue_remove)) }
            }
            if (item.status == ItemStatus.RUNNING) {
                val progress = item.progress
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** A finished batch waiting for the user: the same actions as the results notification, plus Discard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyCard(ready: AwaitingBatch, onSave: () -> Unit, onDiscard: () -> Unit) {
    val context = LocalContext.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val summary = ready.summary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                pluralStringResource(R.plurals.notif_result_videos, summary.done, summary.done) + " · " + ready.batch.presetName,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    R.string.notif_result_saved,
                    Formatter.formatShortFileSize(context, summary.originalBytes),
                    Formatter.formatShortFileSize(context, summary.originalBytes - summary.savedBytes),
                    summary.savedPercent,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    context.startActivity(FinalizeActivity.intent(context, ready.batch.id, FinalizeAction.SHARE))
                }) { Text(stringResource(R.string.ready_reshare)) }
                TextButton(onClick = {
                    context.startActivity(FinalizeActivity.intent(context, ready.batch.id, FinalizeAction.REPLACE))
                }) { Text(stringResource(R.string.ready_replace)) }
                TextButton(onClick = {
                    onSave()
                    Toast.makeText(context, R.string.ready_save_started, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.ready_save)) }
                TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(R.string.ready_discard)) }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.ready_discard_title)) },
            text = { Text(stringResource(R.string.ready_discard_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text(stringResource(R.string.ready_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** Heavy videos found in the library, ready to be compressed with the usual sheet. */
@Composable
private fun SuggestionCard(suggestions: app.squeeze.auto.Suggestions, onCompress: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.suggest_title), style = MaterialTheme.typography.titleSmall)
            Text(
                pluralStringResource(R.plurals.history_videos, suggestions.videos.size, suggestions.videos.size) + " · " +
                    Formatter.formatShortFileSize(context, suggestions.totalBytes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                suggestions.videos.take(3).joinToString("\n") { "${it.name} · ${Formatter.formatShortFileSize(context, it.sizeBytes)}" },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onCompress) { Text(stringResource(R.string.suggest_compress)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.suggest_dismiss)) }
            }
        }
    }
}

/** Without access to the library Squeeze cannot find heavy videos; ask once, politely. */
@Composable
private fun AccessCard(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.suggest_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.suggest_access), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onAllow) { Text(stringResource(R.string.auto_grant_access)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.suggest_dismiss)) }
            }
        }
    }
}
