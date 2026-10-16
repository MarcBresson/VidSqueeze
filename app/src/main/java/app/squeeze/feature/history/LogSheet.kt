package app.squeeze.feature.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.squeeze.R
import app.squeeze.work.ItemLog

/** The ffmpeg log of one item: the command on top (selectable, copyable), then everything ffmpeg printed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogSheet(itemId: Long, vm: HistoryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var log by remember(itemId) { mutableStateOf<ItemLog?>(null) }
    LaunchedEffect(itemId) { log = vm.log(itemId) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.history_logs_title), style = MaterialTheme.typography.titleMedium)
                log?.let { loaded ->
                    TextButton(onClick = { copyAll(context, loaded) }) { Text(stringResource(R.string.history_logs_copy_all)) }
                }
            }
            log?.let { loaded ->
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    SelectionContainer {
                        Text(loaded.command, Modifier.padding(10.dp), style = mono, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider()
                if (loaded.output.isBlank()) {
                    Text(stringResource(R.string.history_logs_empty), style = MaterialTheme.typography.bodySmall)
                } else {
                    val lines = remember(loaded) { loaded.output.lines() }
                    LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                        itemsIndexed(lines) { _, line -> Text(line, style = mono, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }
    }
}

private val mono @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp)

private fun copyAll(context: Context, log: ItemLog) {
    val text = log.command + "\n\n" + log.output
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ffmpeg log", text))
    Toast.makeText(context, R.string.history_logs_copied, Toast.LENGTH_SHORT).show()
}
