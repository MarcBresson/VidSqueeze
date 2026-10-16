package app.squeeze.feature.queue

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.squeeze.R
import app.squeeze.data.AwaitingBatch
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.ItemStatus
import app.squeeze.finalize.FinalizeAction
import app.squeeze.finalize.FinalizeActivity

/** What the running jobs section needs from a view model: the state and the actions on it. */
class ActiveJobsActions(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onCancelAll: () -> Unit,
    val onRemove: (Long) -> Unit,
    val onSaveCopies: (Long) -> Unit,
    val onDiscard: (Long) -> Unit,
)

/** Controls, running and waiting items, and finished batches that still wait for the user. */
fun LazyListScope.activeJobs(ui: QueueUi, actions: ActiveJobsActions) {
    if (ui.items.isNotEmpty()) {
        item(key = "jobs-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.paused) {
                        OutlinedButton(onClick = actions.onResume) { Text(stringResource(R.string.queue_resume)) }
                    } else {
                        OutlinedButton(onClick = actions.onPause) { Text(stringResource(R.string.queue_pause)) }
                    }
                    TextButton(onClick = actions.onCancelAll) { Text(stringResource(R.string.queue_cancel_all)) }
                }
                if (ui.paused) Text(stringResource(R.string.queue_paused), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(ui.items, key = { "job-${it.id}" }) { item -> QueueRow(item, onRemove = { actions.onRemove(item.id) }) }
    }
    if (ui.ready.isNotEmpty()) {
        item(key = "ready-title") {
            Text(stringResource(R.string.ready_title), style = MaterialTheme.typography.titleMedium)
        }
        items(ui.ready, key = { "ready-${it.batch.id}" }) { ready ->
            ReadyCard(ready, onSave = { actions.onSaveCopies(ready.batch.id) }, onDiscard = { actions.onDiscard(ready.batch.id) })
        }
    }
}

@Composable
fun QueueRow(item: BatchItemEntity, onRemove: () -> Unit) {
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
fun ReadyCard(ready: AwaitingBatch, onSave: () -> Unit, onDiscard: () -> Unit) {
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
