package app.squeeze.feature.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.R
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.FinalizeState
import app.squeeze.data.HistoryEntry
import app.squeeze.data.ItemStatus
import app.squeeze.data.PresetStats
import app.squeeze.data.StatTotals
import app.squeeze.data.StatsPeriod
import app.squeeze.feature.queue.activeJobs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.history_tab_history)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.history_tab_stats)) })
            }
            if (tab == 0) HistoryList(vm) else StatsPane(vm)
        }
    }
}

@Composable
private fun HistoryList(vm: HistoryViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val entries = history ?: return
    if (entries.isEmpty() && jobs.items.isEmpty() && jobs.ready.isEmpty()) {
        Text(stringResource(R.string.history_empty), modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        activeJobs(jobs, vm.jobActions)
        items(entries, key = { it.batch.id }) { entry -> HistoryCard(entry) }
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry) {
    val single = entry.single
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (single != null) {
                ItemBlock(single, entry.batch.presetName)
            } else {
                BatchHeader(entry)
                entry.items.forEach { ItemBlock(it, presetName = null) }
            }
        }
    }
}

@Composable
private fun BatchHeader(entry: HistoryEntry) {
    val context = LocalContext.current
    val summary = entry.summary
    Text(
        pluralStringResource(R.plurals.history_videos, entry.items.size, entry.items.size) + " · " + entry.batch.presetName,
        style = MaterialTheme.typography.titleSmall,
    )
    val started = entry.batch.startedAt ?: entry.batch.createdAt
    val finished = entry.batch.finishedAt
    Text(
        if (finished == null) formatDateTime(started)
        else stringResource(R.string.history_started_finished, formatDateTime(started), formatDateTime(finished)),
        style = MaterialTheme.typography.bodySmall,
    )
    if (summary.done > 0) {
        Text(
            stringResource(
                R.string.notif_result_saved,
                Formatter.formatShortFileSize(context, summary.originalBytes),
                Formatter.formatShortFileSize(context, summary.originalBytes - summary.savedBytes),
                summary.savedPercent,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** One video: name, sizes and outcome; tap for preset, dates and the exact ffmpeg command. */
@Composable
private fun ItemBlock(item: BatchItemEntity, presetName: String?) {
    val context = LocalContext.current
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { open = !open },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Text(outcome(context, item), style = MaterialTheme.typography.bodyMedium)
        val compressedAt = item.finishedAt
        if (presetName != null) {
            val date = compressedAt?.let { " · " + formatDateTime(it) }.orEmpty()
            Text(presetName + date, style = MaterialTheme.typography.bodySmall)
        } else if (compressedAt != null) {
            Text(formatDateTime(compressedAt), style = MaterialTheme.typography.bodySmall)
        }
        if (open) {
            item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            finalizeLabel(item)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            val command = item.ffmpegCommand
            if (command != null) {
                Text(stringResource(R.string.history_command), style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { copy(context, command) }) { Text(stringResource(R.string.history_copy_command)) }
            }
        }
    }
}

private fun outcome(context: Context, item: BatchItemEntity): String {
    val original = Formatter.formatShortFileSize(context, item.originalSize)
    return when (item.status) {
        ItemStatus.DONE -> {
            val output = item.outputSize ?: item.originalSize
            val percent = if (item.originalSize <= 0) 0 else (((item.originalSize - output).coerceAtLeast(0) * 100) / item.originalSize).toInt()
            context.getString(R.string.history_item_done, original, Formatter.formatShortFileSize(context, output), percent)
        }
        ItemStatus.SKIPPED_NO_GAIN -> context.getString(R.string.history_item_no_gain, original)
        ItemStatus.FAILED -> context.getString(R.string.history_item_failed, original)
        else -> original
    }
}

private fun finalizeLabel(item: BatchItemEntity): Int? = if (item.status != ItemStatus.DONE) null else when (item.finalizeState) {
    FinalizeState.PENDING -> R.string.history_state_pending
    FinalizeState.REPLACED -> R.string.history_state_replaced
    FinalizeState.SAVED -> R.string.history_state_saved
    FinalizeState.SHARED_ONLY -> R.string.history_state_shared
    FinalizeState.DISCARDED -> R.string.history_state_discarded
}

private fun copy(context: Context, command: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("ffmpeg", command))
    Toast.makeText(context, R.string.history_command_copied, Toast.LENGTH_SHORT).show()
}

private val dateTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatDateTime(epochMs: Long): String =
    dateTime.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsPane(vm: HistoryViewModel) {
    val period by vm.period.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "period") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsPeriod.entries.forEach { p ->
                    FilterChip(selected = p == period, onClick = { vm.setPeriod(p) }, label = { Text(stringResource(periodLabel(p))) })
                }
            }
        }
        val current = stats ?: return@LazyColumn
        if (current.total.videos == 0) {
            item(key = "empty") { Text(stringResource(R.string.stats_empty)) }
            return@LazyColumn
        }
        item(key = "total") { StatsCard(stringResource(R.string.stats_all_presets), current.total) }
        items(current.perPreset, key = { "${it.presetId}|${it.presetName}" }) { StatsCard(it.presetName, it.totals) }
    }
}

private fun periodLabel(period: StatsPeriod): Int = when (period) {
    StatsPeriod.ALL -> R.string.stats_period_all
    StatsPeriod.YEAR -> R.string.stats_period_year
    StatsPeriod.MONTH -> R.string.stats_period_month
    StatsPeriod.WEEK -> R.string.stats_period_week
}

@Composable
private fun StatsCard(title: String, totals: StatTotals) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                pluralStringResource(R.plurals.history_videos, totals.videos, totals.videos),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.stats_saved, size(totals.savedBytes), totals.savedPercent),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.stats_sizes, size(totals.originalBytes), size(totals.outputBytes)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (totals.freedBytes > 0) {
                Text(stringResource(R.string.stats_freed, size(totals.freedBytes)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
