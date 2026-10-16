package app.squeeze.feature.home

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.R
import app.squeeze.data.BatchSource
import app.squeeze.feature.compress.CompressionSheet
import app.squeeze.feature.compress.CompressionSheetViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenHistory: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
    sheetVm: CompressionSheetViewModel = hiltViewModel(),
) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val autoEnabled by vm.autoEnabled.collectAsStateWithLifecycle()
    val promoDismissed by vm.promoDismissed.collectAsStateWithLifecycle()
    val promo by vm.promo.collectAsStateWithLifecycle()
    val heavy by vm.heavy.collectAsStateWithLifecycle()
    val libraryAccess by vm.libraryAccess.collectAsStateWithLifecycle()
    val week by vm.weekStats.collectAsStateWithLifecycle()
    val month by vm.monthStats.collectAsStateWithLifecycle()
    val all by vm.allStats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val libraryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Turning auto compression on needs the same permissions as the Auto tab asks for.
    val autoPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            sheetVm.loadFiles(uris)
            sheetOpen = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "banner") { AppBanner() }

            if (queue.items.isNotEmpty() || queue.ready.isNotEmpty()) {
                item(key = "jobs") { JobProgressWidget(queue, onClick = onOpenHistory) }
            }

            if (libraryAccess && autoEnabled == true) {
                item(key = "auto-title") { SectionTitle(R.string.home_auto_title) }
                item(key = "auto-stats") { AutoStatsCard(week = week, month = month) }
            } else if (libraryAccess && autoEnabled == false && promoDismissed == false) {
                promo?.let { offer ->
                    item(key = "auto-title") { SectionTitle(R.string.home_auto_title) }
                    item(key = "auto-promo") {
                        AutoPromoCard(
                            promo = offer,
                            onEnable = {
                                autoPermissions.launch(
                                    arrayOf(
                                        Manifest.permission.READ_MEDIA_VIDEO,
                                        Manifest.permission.ACCESS_MEDIA_LOCATION,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ),
                                )
                                vm.enableAuto()
                            },
                            onDismiss = vm::dismissPromo,
                        )
                    }
                }
            }

            item(key = "select-title") { SectionTitle(R.string.home_compress_title) }
            item(key = "select") {
                SelectVideosCard(onClick = {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                })
            }

            if (!libraryAccess) {
                item(key = "access-title") { SectionTitle(R.string.home_heavy_title) }
                item(key = "access") {
                    AccessCard(onAllow = { libraryPermission.launch(Manifest.permission.READ_MEDIA_VIDEO) })
                }
            } else if (heavy.isNotEmpty()) {
                item(key = "heavy-title") { SectionTitle(R.string.home_heavy_title) }
                items(heavy, key = { "heavy-${it.video.id}" }) { item ->
                    HeavyVideoCard(item, onShrink = {
                        sheetVm.loadFiles(listOf(item.uri))
                        sheetOpen = true
                    })
                }
            }

            item(key = "impact-title") { SectionTitle(R.string.home_impact_title) }
            item(key = "impact") { ImpactCard(all = all, week = week) }
        }
        // Content scrolls under the transparent status bar; a scrim keeps the clock and icons readable.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.background),
        )
    }

    if (sheetOpen) {
        CompressionSheet(
            vm = sheetVm,
            onDismiss = { sheetOpen = false },
            onStart = {
                val started = sheetVm.start(BatchSource.PICKER) { result ->
                    if (result.isFailure) {
                        context.mainExecutor.execute {
                            Toast.makeText(context, R.string.queue_import_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                if (started) sheetOpen = false
            },
        )
    }
}
