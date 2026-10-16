package app.squeeze.feature.compare

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.R
import app.squeeze.feature.home.Reduction

/**
 * A saved copy next to its original, played together, with the choice to keep only the copy.
 * The comparison itself is [ComparePlayer]; this screen adds the history item around it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(onBack: () -> Unit, vm: CompareViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }

    // The system dialog that deletes the original; once approved the replace carries on.
    val deleteConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) vm.replace()
        else Toast.makeText(context, R.string.compare_original_kept, Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                CompareEvent.Replaced -> {
                    Toast.makeText(context, R.string.compare_replaced, Toast.LENGTH_SHORT).show()
                    onBack()
                }
                is CompareEvent.AskToDelete -> deleteConsent.launch(IntentSenderRequest.Builder(event.request).build())
                CompareEvent.Failed -> Toast.makeText(context, R.string.compare_replace_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text((ui as? CompareUi.Ready)?.name ?: stringResource(R.string.compare_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.presets_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val state = ui) {
                CompareUi.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                CompareUi.Unavailable -> Text(
                    stringResource(R.string.compare_unavailable),
                    Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                is CompareUi.Ready -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComparePlayer(state.original, state.compressed, Modifier.weight(1f).fillMaxWidth())
                    val saved = Reduction.percent(state.originalSize, state.compressedSize)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.compare_summary,
                                Formatter.formatShortFileSize(context, state.originalSize),
                                Formatter.formatShortFileSize(context, state.compressedSize),
                            ) + (saved?.let { " · −$it%" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { confirm = true }, enabled = !state.replacing, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.compare_replace))
                        }
                    }
                }
            }
        }
    }

    val ready = ui as? CompareUi.Ready
    if (confirm && ready != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.compare_replace_title)) },
            text = { Text(stringResource(R.string.compare_replace_body, ready.finalName)) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.replace() }) { Text(stringResource(R.string.compare_replace_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.sheet_cancel)) } },
        )
    }
}
