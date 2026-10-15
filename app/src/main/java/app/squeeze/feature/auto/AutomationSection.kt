package app.squeeze.feature.auto

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.squeeze.R
import app.squeeze.automation.AutomationApi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val logTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM)

/** External automation: switch, secret token, ready-made commands and the log of what was received. */
@Composable
internal fun AutomationSection(ui: AutoUi, vm: AutoViewModel) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.automation_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.automation_intro), style = MaterialTheme.typography.bodySmall)
            SwitchRow(stringResource(R.string.automation_enable), ui.automationEnabled, vm::setAutomation)
            if (!ui.automationEnabled) return@Column

            val token = ui.automationToken.orEmpty()
            Text(stringResource(R.string.automation_token), style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(context, "token", token) }) { Text(stringResource(R.string.automation_copy_token)) }
                OutlinedButton(onClick = vm::regenerateToken) { Text(stringResource(R.string.automation_regenerate)) }
            }
            Text(stringResource(R.string.automation_example), style = MaterialTheme.typography.titleSmall)
            val example = "adb shell am broadcast -n ${context.packageName}/app.squeeze.automation.AutomationReceiver " +
                "-a ${AutomationApi.START_AUTO_RUN} --es token $token --ei duration_minutes 120"
            SelectionContainer { Text(example, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { copy(context, "command", example) }) { Text(stringResource(R.string.automation_copy_example)) }

            SwitchRow(stringResource(R.string.automation_events), ui.automationEvents, vm::setEvents)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.automation_log), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                if (ui.log.isNotEmpty()) TextButton(onClick = vm::clearLog) { Text(stringResource(R.string.automation_clear_log)) }
            }
            if (ui.log.isEmpty()) Text(stringResource(R.string.automation_log_empty), style = MaterialTheme.typography.bodySmall)
            ui.log.take(LOG_ROWS).forEach { entry ->
                val time = logTime.format(Instant.ofEpochMilli(entry.timeMs).atZone(ZoneId.systemDefault()))
                Text(
                    "$time · ${entry.action} · ${if (entry.accepted) "✓" else "✗"} ${entry.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.accepted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun copy(context: android.content.Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, R.string.history_command_copied, Toast.LENGTH_SHORT).show()
}

private const val LOG_ROWS = 20
