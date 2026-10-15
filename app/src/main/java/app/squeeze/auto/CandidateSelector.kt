package app.squeeze.auto

import java.time.Duration
import java.time.Instant

/** A video in the gallery, as MediaStore describes it. */
data class VideoInfo(
    val id: Long,
    val name: String,
    /** Folder relative to the storage root with a trailing slash, e.g. `DCIM/Camera/`. */
    val relativePath: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val dateTakenMs: Long?,
    val dateAddedMs: Long?,
) {
    /** Video and audio together, in kbit/s; null when the duration is unknown. */
    val bitrateKbps: Long? get() = if (durationMs > 0) sizeBytes * 8 / durationMs else null
}

data class Candidate(val video: VideoInfo, val tier: AgeTier)

object CandidateSelector {
    /** Folder where Squeeze puts what it cannot place next to the original; never compressed again. */
    const val OWN_FOLDER = "Movies/Squeeze/"

    /**
     * The videos an auto run may compress, in the order they should run. A video qualifies when it is
     * old enough for a tier, big and heavy enough to be worth it, outside the excluded folders and
     * not [skip] (already compressed, queued, or failed repeatedly).
     */
    fun select(videos: List<VideoInfo>, config: AutoConfig, now: Instant, skip: Set<Long>): List<Candidate> {
        val picked = videos.mapNotNull { video ->
            if (video.id in skip || video.sizeBytes < config.minSizeBytes || isExcluded(video.relativePath, config.excludedFolders)) {
                return@mapNotNull null
            }
            val bitrate = video.bitrateKbps
            if (config.minBitrateKbps > 0 && bitrate != null && bitrate < config.minBitrateKbps) return@mapNotNull null
            val age = AutoRuleResolver.ageOf(video.dateTakenMs?.let(Instant::ofEpochMilli), video.dateAddedMs?.let(Instant::ofEpochMilli), now)
                ?: return@mapNotNull null
            AutoRuleResolver.resolve(config.tiers, age)?.let { Candidate(video, it) }
        }
        return when (config.order) {
            AutoOrder.OLDEST_FIRST -> picked.sortedBy { it.video.dateTakenMs ?: it.video.dateAddedMs ?: Long.MAX_VALUE }
            AutoOrder.LARGEST_FIRST -> picked.sortedByDescending { it.video.sizeBytes }
        }
    }

    /** True for our own output folder and for anything under an excluded folder name or path prefix. */
    fun isExcluded(relativePath: String, excluded: List<String>): Boolean {
        val path = normalise(relativePath)
        if (path.startsWith(normalise(OWN_FOLDER))) return true
        return excluded.map(::normalise).filter { it != "/" }.any { entry ->
            path.startsWith(entry) || path.contains("/$entry")
        }
    }

    private fun normalise(folder: String): String = folder.trim().lowercase().trim('/').let { if (it.isEmpty()) "/" else "$it/" }
}

/** Heavy videos worth suggesting to the user: big files first, whatever their age. */
object HeavyVideoSelector {
    const val MIN_SIZE_BYTES = 100L * 1024 * 1024

    fun select(videos: List<VideoInfo>, skip: Set<Long>, limit: Int = 10, minSizeBytes: Long = MIN_SIZE_BYTES): List<VideoInfo> =
        videos.filter { it.id !in skip && it.sizeBytes >= minSizeBytes && !CandidateSelector.isExcluded(it.relativePath, emptyList()) }
            .sortedByDescending { it.sizeBytes }
            .take(limit)
}

/** When an unattended run has to stop, and how the stop is decided. */
object AutoRunPlan {
    /**
     * The end of a run started at [now]: an explicit limit from an external trigger, else the end of
     * the active window, else none (the run lasts until the work is done).
     */
    fun deadline(
        config: AutoConfig,
        now: java.time.ZonedDateTime,
        durationMinutes: Int? = null,
        until: java.time.LocalTime? = null,
    ): Long? {
        val limits = buildList {
            durationMinutes?.takeIf { it > 0 }?.let { add(now.plus(Duration.ofMinutes(it.toLong())).toInstant().toEpochMilli()) }
            until?.let {
                var at = now.toLocalDate().atTime(it).atZone(now.zone)
                if (!at.isAfter(now)) at = at.plusDays(1)
                add(at.toInstant().toEpochMilli())
            }
        }
        if (limits.isNotEmpty()) return limits.min()
        if (config.mode == ScheduleMode.WINDOWS) {
            return AutoSchedule.activeUntil(config.windows, now)?.toInstant()?.toEpochMilli()
        }
        return null
    }
}
