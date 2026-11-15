package app.squeeze.feature.compress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.squeeze.engine.HelpEntry

/** A "?" affordance next to a setting label that opens a plain-language explanation. */
@Composable
fun HelpIcon(entry: HelpEntry) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(24.dp)) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = "What is this?",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(entry.title) },
            text = {
                Column {
                    Text(entry.whatItDoes, style = MaterialTheme.typography.bodyMedium)
                    Text(entry.whichToPick, style = MaterialTheme.typography.titleSmall)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Got it") } },
        )
    }
}
