package app.squeeze.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import app.squeeze.data.AutoSettingsRepository
import app.squeeze.data.BatchSource
import app.squeeze.data.PresetRepository
import app.squeeze.data.QueueRepository
import app.squeeze.data.QueuedSource
import app.squeeze.data.RunLimits
import app.squeeze.engine.Preset
import app.squeeze.engine.SizeEstimator
import app.squeeze.engine.SourceInfo
import app.squeeze.finalize.MediaStoreWriter
import app.squeeze.work.QueueManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

enum class Trigger { SCHEDULED, MANUAL, EXTERNAL }

data class RunOptions(
    val trigger: Trigger,
    /** Skip the charger and battery gates (the user or an automation tool explicitly asked). */
    val ignoreConstraints: Boolean = false,
    val durationMinutes: Int? = null,
    val until: LocalTime? = null,
    /** Preset id or name that replaces the tiers' presets for this run. */
    val presetOverride: String? = null,
    val dryRun: Boolean = false,
    /** Only videos under this folder (relative to the storage root), and older than [olderThanDays]. */
    val folder: String? = null,
    val olderThanDays: Int? = null,
    val exportModeOverride: app.squeeze.engine.ExportMode? = null,
)

/** One video an auto run would compress, with what it would use and a rough size estimate. */
data class PlannedVideo(val video: VideoInfo, val preset: Preset, val estimatedBytes: Long?) {
    val estimatedSavedBytes: Long get() = estimatedBytes?.let { (video.sizeBytes - it).coerceAtLeast(0) } ?: 0
}

data class AutoRunResult(
    /** Why nothing happened, or null when the run went ahead (or had nothing to do). */
    val skippedReason: String? = null,
    val planned: List<PlannedVideo> = emptyList(),
    val queued: Boolean = false,
) {
    val videos: Int get() = planned.size
    val bytes: Long get() = planned.sumOf { it.video.sizeBytes }
    val estimatedSavedBytes: Long get() = planned.sumOf { it.estimatedSavedBytes }
}

/**
 * Finds the videos that should be compressed now and queues them as `AUTO` batches, one per tier
 * (each batch carries its tier's preset). The queue worker does the rest and watches the limits
 * stored here, see [RunLimits].
 */
@Singleton
class AutoRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AutoSettingsRepository,
    private val scanner: GalleryScanner,
    private val queue: QueueRepository,
    private val queueManager: QueueManager,
    private val presets: PresetRepository,
    private val device: DeviceStateReader,
    private val store: MediaStoreWriter,
) {
    /** What an auto run would do right now, ignoring schedule and gates. Used for the dry run. */
    suspend fun plan(config: AutoConfig, options: RunOptions? = null, now: Instant = Instant.now()): List<PlannedVideo> {
        val all = presets.all()
        val override = options?.presetOverride?.let { key -> all.firstOrNull { it.id == key } ?: all.firstOrNull { it.name.equals(key, ignoreCase = true) } }
        val effective = if (options?.folder != null || options?.olderThanDays != null) {
            // A folder command has its own age rule instead of the tiers.
            val base = override ?: return emptyList()
            config.copy(tiers = listOf(AgeTier(options.olderThanDays?.coerceAtLeast(0) ?: 0, base.id)), minBitrateKbps = 0, minSizeMb = 0)
        } else {
            config
        }
        val folder = options?.folder?.trim()?.trim('/')?.lowercase()?.takeIf { it.isNotEmpty() }
        val videos = scanner.scan().filter { v ->
            folder == null || v.relativePath.trim('/').lowercase().let { it == folder || it.startsWith("$folder/") }
        }
        val skip = queue.skipIds()
        val byId = all.associateBy { it.id }
        return CandidateSelector.select(videos, effective, now, skip).mapNotNull { candidate ->
            val base = override ?: byId[candidate.tier.presetId] ?: return@mapNotNull null
            val export = base.export.copy(mode = options?.exportModeOverride ?: effective.exportMode)
            val preset = base.copy(export = export)
            val v = candidate.video
            val info = SourceInfo(durationMs = v.durationMs, width = v.width, height = v.height)
            PlannedVideo(v, preset, SizeEstimator.estimateBytes(preset.params, info, v.sizeBytes))
        }
    }

    suspend fun run(options: RunOptions): AutoRunResult {
        val config = settings.currentConfig()
        val now = ZonedDateTime.now()
        when (options.trigger) {
            Trigger.SCHEDULED -> {
                if (!config.enabled) return AutoRunResult("auto compression is off")
                if (!AutoSchedule.mayRun(config.mode, config.windows, now, externallyTriggered = false)) {
                    return AutoRunResult("outside the off-peak window")
                }
            }
            Trigger.EXTERNAL -> if (!config.enabled && options.folder == null) return AutoRunResult("auto compression is off")
            Trigger.MANUAL -> Unit
        }
        if (options.trigger != Trigger.MANUAL && !options.ignoreConstraints) {
            config.gates.problem(device.read())?.let { return AutoRunResult(it) }
        }
        if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
            return AutoRunResult("no access to videos")
        }

        val planned = plan(config, options, now.toInstant())
        if (options.dryRun || planned.isEmpty()) return AutoRunResult(planned = planned)

        val limits = when (options.trigger) {
            Trigger.MANUAL -> RunLimits(null, null)
            else -> RunLimits(
                AutoRunPlan.deadline(config, now, options.durationMinutes, options.until),
                if (options.ignoreConstraints) null else config.gates,
            )
        }
        settings.setRunLimits(limits)
        // One batch per preset keeps each batch's snapshot honest.
        planned.groupBy { it.preset }.forEach { (preset, group) ->
            val sources = group.map {
                QueuedSource(
                    uri = itemUri(it.video.id).toString(), mediaStoreId = it.video.id, isImported = false,
                    displayName = it.video.name, size = it.video.sizeBytes,
                )
            }
            queue.enqueue(BatchSource.AUTO, preset, sources)
        }
        queueManager.ensureRunning()
        return AutoRunResult(planned = planned, queued = true)
    }

    /** Ends the auto run: waiting auto items are cancelled, the one being encoded is stopped. */
    suspend fun stop() {
        settings.setRunLimits(RunLimits(deadlineMs = System.currentTimeMillis(), gates = null))
        queueManager.cancelAuto()
    }

    private fun itemUri(id: Long): Uri = store.itemUri(id)
}
