package app.squeeze.feature.compare

import android.view.LayoutInflater
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.ui.PlayerView
import app.squeeze.R
import app.squeeze.ui.media.VideoRef
import app.squeeze.ui.media.playVideo
import kotlinx.coroutines.delay

private const val TICK_MS = 150L

/**
 * Plays [original] and [compressed] together with one set of controls. It knows nothing about
 * history or finalizing, so any screen can host it: pick a layout, tap a pane to hear that video
 * (or, in the single-pane layout, to flip between the two), scrub both at once.
 */
@Composable
fun ComparePlayer(original: VideoRef, compressed: VideoRef, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Survive rotation: the position and the layout are restored, playback restarts paused.
    var resumeMs by rememberSaveable { mutableStateOf(0L) }
    var layout by rememberSaveable { mutableStateOf(CompareLayout.SIDE_BY_SIDE) }
    var visibleInSwitch by rememberSaveable { mutableStateOf(CompareSide.COMPRESSED) }

    val players = remember(original.uri, compressed.uri) { SyncedPlayers(context, original, compressed, resumeMs) }
    DisposableEffect(players) { onDispose(players::release) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { players.pause() }
    LaunchedEffect(players) {
        while (true) {
            players.tick()
            resumeMs = players.positionMs
            delay(TICK_MS)
        }
    }
    // New surfaces (first display, other layout) can stay blank while paused; ask for a frame.
    val ready = players.durationMs > 0
    LaunchedEffect(players, layout, ready, players.isPlaying) {
        delay(300)
        players.refreshFrame()
    }
    // The visible pane is the one heard in the single-pane layout.
    LaunchedEffect(layout, visibleInSwitch) { if (layout == CompareLayout.SWITCH) players.selectAudio(visibleInSwitch) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            val pane: @Composable (CompareSide, Modifier) -> Unit = { side, paneModifier ->
                VideoPane(
                    side = side,
                    players = players,
                    video = if (side == CompareSide.ORIGINAL) original else compressed,
                    heard = players.audioFrom == side,
                    showBorder = layout != CompareLayout.SWITCH,
                    onClick = {
                        if (layout == CompareLayout.SWITCH) visibleInSwitch = visibleInSwitch.other else players.selectAudio(side)
                    },
                    modifier = paneModifier,
                )
            }
            when (layout) {
                CompareLayout.SIDE_BY_SIDE -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    pane(CompareSide.ORIGINAL, Modifier.weight(1f).fillMaxSize())
                    pane(CompareSide.COMPRESSED, Modifier.weight(1f).fillMaxSize())
                }
                CompareLayout.STACKED -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    pane(CompareSide.ORIGINAL, Modifier.weight(1f).fillMaxWidth())
                    pane(CompareSide.COMPRESSED, Modifier.weight(1f).fillMaxWidth())
                }
                // Both stay composed so the hidden one keeps its surface and stays in step.
                CompareLayout.SWITCH -> Box(Modifier.fillMaxSize()) {
                    CompareSide.entries.forEach { side ->
                        pane(side, Modifier.fillMaxSize().alpha(if (side == visibleInSwitch) 1f else 0f))
                    }
                }
            }
        }
        LayoutPicker(layout, onSelect = { layout = it })
        Transport(players)
    }
}

@Composable
private fun VideoPane(
    side: CompareSide,
    players: SyncedPlayers,
    video: VideoRef,
    heard: Boolean,
    showBorder: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val failed = side in players.failed
    val label = stringResource(if (side == CompareSide.ORIGINAL) R.string.compare_original else R.string.compare_compressed)
    Box(
        modifier.then(if (showBorder && heard) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) else Modifier),
    ) {
        AndroidView(
            factory = { LayoutInflater.from(it).inflate(R.layout.compare_player_view, null) as PlayerView },
            update = { it.player = players.player(side) },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // Over the video, so taps reach Compose rather than the platform view.
        Box(Modifier.fillMaxSize().clickable(onClickLabel = label, onClick = onClick))
        Surface(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            shape = MaterialTheme.shapes.small,
            color = Color.Black.copy(alpha = 0.6f),
            contentColor = Color.White,
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                if (heard) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.compare_audio_on), Modifier.padding(start = 4.dp).size(14.dp))
                }
            }
        }
        if (failed) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.compare_pane_error), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { context.playVideo(video) }) { Text(stringResource(R.string.compare_open_external)) }
            }
        }
    }
}

@Composable
private fun LayoutPicker(selected: CompareLayout, onSelect: (CompareLayout) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CompareLayout.entries.forEach { option ->
            val (icon, description) = when (option) {
                CompareLayout.SIDE_BY_SIDE -> Icons.Default.ViewColumn to R.string.compare_layout_side_by_side
                CompareLayout.STACKED -> Icons.Default.ViewAgenda to R.string.compare_layout_stacked
                CompareLayout.SWITCH -> Icons.Default.SwapHoriz to R.string.compare_layout_switch
            }
            FilledIconToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Icon(icon, contentDescription = stringResource(description))
            }
        }
    }
}

@Composable
private fun Transport(players: SyncedPlayers) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = players.durationMs
    val fraction = if (duration > 0) (players.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = players::toggle) {
            Icon(
                if (players.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (players.isPlaying) R.string.compare_pause else R.string.compare_play),
            )
        }
        Slider(
            value = scrub ?: fraction,
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let { players.seekTo((it * duration).toLong()) }
                scrub = null
            },
            enabled = duration > 0,
            modifier = Modifier.weight(1f),
        )
        Text(
            SyncPolicy.formatClock(players.positionMs) + " / " + SyncPolicy.formatClock(duration),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
