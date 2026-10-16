package app.squeeze.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.R

/** The permissions page reachable from Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit, vm: PermissionsViewModel = hiltViewModel()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.perm_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.presets_back))
                    }
                },
            )
        },
    ) { padding ->
        PermissionList(vm, Modifier.padding(padding).padding(horizontal = 16.dp))
    }
}

/** The first screen on a fresh install: what the app needs and why, with "Continue" always possible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: PermissionsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onboard_title)) }) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = { vm.finishOnboarding(onDone) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (state[AppPermission.VIDEOS] == true) R.string.onboard_continue else R.string.onboard_skip,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        PermissionList(vm, Modifier.padding(padding).padding(horizontal = 16.dp), intro = stringResource(R.string.onboard_intro))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionList(vm: PermissionsViewModel, modifier: Modifier = Modifier, intro: String? = null) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (intro != null) item { Text(intro, style = MaterialTheme.typography.bodyMedium) }
        items(AppPermission.entries, key = { it.name }) { permission ->
            val granted = state[permission] == true
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(permission.title()), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(permission.reason()), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(
                            when {
                                granted -> R.string.perm_allowed
                                permission.recommended -> R.string.perm_recommended
                                else -> R.string.perm_optional
                            },
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (!granted && permission.recommended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    if (!granted) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val runtime = permission.runtime
                            if (runtime != null) {
                                Button(onClick = { request.launch(runtime) }) { Text(stringResource(R.string.perm_allow)) }
                                // After "don't ask again" the dialog never shows; the app's settings page is the only way.
                                TextButton(onClick = { context.startActivity(AppPermissions.appDetails(context)) }) {
                                    Text(stringResource(R.string.perm_app_settings))
                                }
                            } else {
                                OutlinedButton(onClick = { context.startActivity(AppPermissions.settingsIntent(context, permission)) }) {
                                    Text(stringResource(R.string.perm_open_settings))
                                }
                            }
                        }
                    } else if (permission.runtime == null) {
                        // Special access is revoked on the same system page it was granted on.
                        TextButton(onClick = { context.startActivity(AppPermissions.settingsIntent(context, permission)) }) {
                            Text(stringResource(R.string.perm_open_settings))
                        }
                    }
                }
            }
        }
    }
}

private fun AppPermission.title() = when (this) {
    AppPermission.VIDEOS -> R.string.perm_videos
    AppPermission.LOCATION -> R.string.perm_location
    AppPermission.NOTIFICATIONS -> R.string.perm_notifications
    AppPermission.MANAGE_MEDIA -> R.string.perm_manage_media
    AppPermission.BATTERY -> R.string.perm_battery
}

private fun AppPermission.reason() = when (this) {
    AppPermission.VIDEOS -> R.string.perm_videos_why
    AppPermission.LOCATION -> R.string.perm_location_why
    AppPermission.NOTIFICATIONS -> R.string.perm_notifications_why
    AppPermission.MANAGE_MEDIA -> R.string.perm_manage_media_why
    AppPermission.BATTERY -> R.string.perm_battery_why
}
