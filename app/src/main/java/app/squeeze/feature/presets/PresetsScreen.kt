package app.squeeze.feature.presets

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.R
import app.squeeze.engine.ParamOptions.label
import app.squeeze.engine.Preset
import app.squeeze.engine.RateControl
import app.squeeze.engine.VideoCodec
import app.squeeze.feature.compress.MessageToast
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(onBack: () -> Unit, onEdit: (String) -> Unit, vm: PresetsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val title = stringResource(R.string.presets_export_title)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.presets_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.presets_back))
                    }
                },
                actions = {
                    TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                        Text(stringResource(R.string.presets_import))
                    }
                    TextButton(onClick = {
                        scope.launch {
                            val uri = vm.exportFile(includeBuiltIn = false) ?: return@launch
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("application/json")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_TITLE, "squeeze-presets.json")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, title))
                        }
                    }) { Text(stringResource(R.string.presets_export)) }
                },
            )
        },
    ) { padding ->
        val (builtIn, mine) = ui.presets.partition { it.builtIn }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Header(stringResource(R.string.presets_built_in)) }
            items(builtIn, key = { it.id }) { PresetRow(it, it.id == ui.defaultPresetId, onEdit, vm::setDefault) }
            item { Header(stringResource(R.string.presets_yours)) }
            if (mine.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.presets_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(mine, key = { it.id }) { PresetRow(it, it.id == ui.defaultPresetId, onEdit, vm::setDefault) }
        }
    }
    MessageToast(ui.message, vm::messageShown)
}

@Composable
private fun Header(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))

@Composable
private fun PresetRow(preset: Preset, isDefault: Boolean, onEdit: (String) -> Unit, onDefault: (String) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onEdit(preset.id) },
        headlineContent = { Text(preset.name) },
        supportingContent = { Text(summary(preset)) },
        trailingContent = {
            IconButton(onClick = { onDefault(preset.id) }) {
                Icon(
                    if (isDefault) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = stringResource(R.string.sheet_set_default),
                    tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

private fun summary(p: Preset): String {
    val params = p.params
    val parts = buildList {
        add(params.videoCodec.label())
        when (val r = params.rateControl) {
            is RateControl.Crf -> if (params.videoCodec != VideoCodec.COPY) add("CRF ${r.value}")
            is RateControl.BitrateKbps -> add("${r.kbps} kbit/s")
            is RateControl.PercentOfSource -> add("${r.percent}% of source")
            is RateControl.ConstantQuality -> add("CQ ${r.value}")
        }
        params.maxShortSide?.let { add("${it}p") }
        params.fpsMax?.let { add("$it fps") }
    }
    return parts.joinToString(" · ")
}
