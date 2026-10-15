package app.squeeze.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.squeeze.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads a bundled licence text by resource name; null when this build does not contain it. */
private suspend fun readRaw(context: android.content.Context, name: String): String? = withContext(Dispatchers.IO) {
    val id = context.resources.getIdentifier(name, "raw", context.packageName)
    if (id == 0) null else context.resources.openRawResource(id).bufferedReader().use { it.readText() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit, onOpenText: (String) -> Unit) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.presets_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.about_privacy_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.about_licence), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.about_licence_body), style = MaterialTheme.typography.bodyMedium)
                    TextRow(stringResource(R.string.about_licence_text)) { onOpenText(Licenses.APP) }
                    TextRow(stringResource(R.string.about_source_offer)) { onOpenText(Licenses.SOURCE_OFFER) }
                    SOURCE_URL?.let { url ->
                        TextRow(stringResource(R.string.about_source_code)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                    TextRow(stringResource(R.string.about_open_source)) { onOpenLicenses() }
                }
            }
        }
    }
}

@Composable
private fun TextRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** The list of third-party components with their notices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpenText: (String) -> Unit) {
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_open_source)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.presets_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            item {
                Text(stringResource(R.string.licenses_ffmpeg_intro), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(Licenses.ffmpeg, key = { it.rawName }) { entry ->
                TextRow(entry.title) { onOpenText(entry.rawName) }
                HorizontalDivider()
            }
            item { Text(stringResource(R.string.licenses_android), Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium) }
            items(Licenses.android, key = { it.title }) { link ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(link.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text(link.licence, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            item { Column(Modifier.padding(bottom = 24.dp)) {} }
        }
    }
}

/** Shows one bundled licence text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseTextScreen(rawName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember(rawName) { mutableStateOf<String?>(null) }
    var missing by remember(rawName) { mutableStateOf(false) }
    LaunchedEffect(rawName) {
        text = readRaw(context, rawName)
        missing = text == null
    }
    val title = when (rawName) {
        Licenses.APP -> stringResource(R.string.about_licence_text)
        Licenses.SOURCE_OFFER -> stringResource(R.string.about_source_offer)
        else -> Licenses.ffmpeg.firstOrNull { it.rawName == rawName }?.title ?: rawName
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.presets_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            when {
                missing -> Text(stringResource(R.string.licenses_missing))
                text != null -> SelectionContainer {
                    Text(requireNotNull(text), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
