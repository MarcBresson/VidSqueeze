package app.squeeze.feature.queue

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.squeeze.R
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.ItemStatus

/** What the running jobs section needs from a view model: the state and the actions on it. */
class ActiveJobsActions(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onCancelAll: () -> Unit,
    val onRemove: (Long) -> Unit,
)

/** Controls, running and waiting items. Finished results act from the history cards. */
fun LazyListScope.activeJobs(ui: QueueUi, actions: ActiveJobsActions) {
    if (ui.items.isEmpty()) return
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
