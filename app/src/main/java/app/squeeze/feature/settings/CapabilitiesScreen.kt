package app.squeeze.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.R
import app.squeeze.engine.CodecGroup
import app.squeeze.engine.CodecKind
import app.squeeze.engine.EncoderEntry
import app.squeeze.ui.Pill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesScreen(onBack: () -> Unit, vm: CapabilitiesViewModel = hiltViewModel()) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.caps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.presets_back))
                    }
                },
            )
        },
    ) { padding ->
        val loaded = groups
        when {
            loaded == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            loaded.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.caps_empty), style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(
                Modifier.padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.caps_intro), style = MaterialTheme.typography.bodyMedium) }
                section(R.string.caps_video, loaded.filter { it.kind == CodecKind.VIDEO })
                section(R.string.caps_audio, loaded.filter { it.kind == CodecKind.AUDIO })
                item { Column(Modifier.padding(bottom = 24.dp)) {} }
            }
        }
    }
}

private fun LazyListScope.section(titleRes: Int, groups: List<CodecGroup>) {
    if (groups.isEmpty()) return
    item(key = "header-$titleRes") {
        Text(stringResource(titleRes), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleLarge)
    }
    items(groups, key = { it.mime }) { GroupCard(it) }
}

@Composable
private fun GroupCard(group: CodecGroup) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.label, style = MaterialTheme.typography.titleMedium)
            if (!group.hasHardware) {
                Text(
                    stringResource(R.string.caps_no_hardware),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            group.encoders.forEachIndexed { index, encoder ->
                if (index > 0) HorizontalDivider()
                EncoderRow(encoder)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EncoderRow(encoder: EncoderEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(encoder.name, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (encoder.isHardware) {
                Pill(stringResource(R.string.caps_hardware), bold = true)
            } else {
                Pill(
                    stringResource(R.string.caps_software),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (encoder.supportsCq) {
                Pill(
                    stringResource(R.string.caps_cq),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        val details = details(encoder)
        if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

/** The limits worth showing on one line: size, frame rate, channels, sample rates, top bitrate. */
@Composable
private fun details(encoder: EncoderEntry): List<String> = buildList {
    encoder.video?.let { video ->
        add(stringResource(R.string.caps_max_size, video.maxWidth, video.maxHeight))
        video.maxFrameRate?.let { add(stringResource(R.string.caps_max_fps, it)) }
        encoder.bitrateRangeKbps?.let { add(stringResource(R.string.caps_max_bitrate_mbps, it.last / 1000)) }
    }
    encoder.audio?.let { audio ->
        add(pluralStringResource(R.plurals.caps_channels, audio.maxChannels, audio.maxChannels))
        audio.sampleRateHz?.let { rate ->
            add(
                if (rate.first == rate.last) stringResource(R.string.caps_sample_rate_single, khz(rate.first))
                else stringResource(R.string.caps_sample_rate, khz(rate.first), khz(rate.last)),
            )
        }
        encoder.bitrateRangeKbps?.let { add(stringResource(R.string.caps_max_bitrate_kbps, it.last)) }
    }
}

private fun khz(hz: Int): String = if (hz % 1000 == 0) (hz / 1000).toString() else "%.1f".format(hz / 1000.0)
