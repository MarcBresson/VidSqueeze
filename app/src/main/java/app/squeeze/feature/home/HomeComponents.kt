package app.squeeze.feature.home

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.squeeze.R
import app.squeeze.data.StatTotals
import app.squeeze.feature.queue.QueueUi

@Composable
private fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** "1 h 12 min", "8 min", "45 s"; zero shows as a dash-free "0 s". */
@Composable
private fun durationText(ms: Long): String {
    val seconds = ms / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.duration_minutes, minutes)
        else -> stringResource(R.string.duration_seconds, seconds)
    }
}

@Composable
internal fun SectionTitle(titleRes: Int) {
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** Small rounded label, used for counts and resolutions. */
@Composable
private fun Pill(text: String, container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun AppBanner() {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF4F378B)),
            contentAlignment = Alignment.Center,
        ) {
            // The launcher artwork is a 108dp adaptive layer; drawn bigger so the logo fills the tile.
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(72.dp))
        }
        Column {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Compact status of the queue; the detail lives in the History tab. */
@Composable
fun JobProgressWidget(queue: QueueUi, onClick: () -> Unit) {
    val running = queue.running
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (queue.items.isEmpty()) {
                Text(
                    pluralStringResource(R.plurals.home_results_ready, queue.ready.size, queue.ready.size),
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                Text(
                    when {
                        queue.paused -> stringResource(R.string.home_job_paused)
                        running != null -> running.displayName
                        else -> stringResource(R.string.notif_preparing)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val progress = running?.progress
                if (running != null && progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else if (running != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val waiting = queue.items.size - if (running != null) 1 else 0
                Text(
                    pluralStringResource(R.plurals.home_job_waiting, waiting, waiting),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun AutoPromoCard(promo: AutoPromo, onEnable: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.home_promo_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.home_promo_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill(pluralStringResource(R.plurals.home_promo_videos, promo.videos, promo.videos))
            }
            if (promo.videos > 0) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(sizeText(promo.currentBytes), style = MaterialTheme.typography.titleMedium)
                            Text("→", style = MaterialTheme.typography.titleMedium)
                            val achievable = promo.achievableBytes
                            Text(
                                achievable?.let { sizeText(it) } ?: "—",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            promo.reductionPercent?.let { Text("(−$it%)", style = MaterialTheme.typography.bodyMedium) }
                        }
                        SavingsBar(promo.reclaimedFraction)
                    }
                }
            } else {
                Text(stringResource(R.string.home_promo_none), style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onEnable, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.home_promo_enable)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_promo_dismiss)) }
            }
        }
    }
}

/** Reclaimed part of the storage in the accent colour, the part that stays in the muted one. */
@Composable
fun SavingsBar(reclaimed: Float, modifier: Modifier = Modifier) {
    val share = reclaimed.coerceIn(0f, 1f)
    val keptWeight = (1f - share).coerceAtLeast(0.001f)
    Row(modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
        if (share > 0f) Box(Modifier.weight(share).height(10.dp).background(MaterialTheme.colorScheme.primary))
        Box(Modifier.weight(keptWeight).height(10.dp))
    }
}

@Composable
fun AutoStatsCard(week: StatTotals?, month: StatTotals?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_auto_on_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PeriodStats(stringResource(R.string.home_this_week), week, Modifier.weight(1f))
                PeriodStats(stringResource(R.string.home_this_month), month, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PeriodStats(label: String, totals: StatTotals?, modifier: Modifier = Modifier) {
    val value = totals ?: StatTotals()
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sizeText(value.savedBytes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(pluralStringResource(R.plurals.history_videos, value.videos, value.videos), style = MaterialTheme.typography.bodySmall)
            Text(durationText(value.durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The main action: the whole card is the button. */
@Composable
fun SelectVideosCard(onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Filled.VideoLibrary, contentDescription = null, modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.home_select_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.home_select_description), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun HeavyVideoCard(item: HeavyVideo, onShrink: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResolutionLabel.of(item.video.width, item.video.height)?.let {
                        Pill(it, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Text(item.video.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(sizeText(item.video.sizeBytes), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text("→", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        item.estimatedBytes?.let { sizeText(it) } ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                item.reductionPercent?.let {
                    Text("−$it%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
            Button(onClick = onShrink, contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text(stringResource(R.string.home_shrink))
            }
        }
    }
}

@Composable
fun ImpactCard(all: StatTotals?, week: StatTotals?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_impact_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val total = all ?: StatTotals()
            if (total.videos == 0) {
                Text(stringResource(R.string.home_impact_empty), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Column {
                Text(
                    sizeText(total.savedBytes),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.home_impact_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.home_impact_week, sizeText((week ?: StatTotals()).savedBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImpactFigure(total.videos.toString(), stringResource(R.string.home_impact_videos), Modifier.weight(1f))
                ImpactFigure("−${total.savedPercent}%", stringResource(R.string.home_impact_average), Modifier.weight(1f))
                ImpactFigure(durationText(total.durationMs), stringResource(R.string.home_impact_time), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ImpactFigure(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Without access to the library Squeeze cannot find heavy videos; ask once, politely. */
@Composable
fun AccessCard(onAllow: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.suggest_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.suggest_access), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onAllow) { Text(stringResource(R.string.auto_grant_access)) }
        }
    }
}
