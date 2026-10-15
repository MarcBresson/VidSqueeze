package app.squeeze.auto

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable

/** "Older than [minAgeDays] days → compress with [presetId]". */
@Serializable
data class AgeTier(val minAgeDays: Int, val presetId: String)

object AutoRuleResolver {

    /**
     * The tier for a video of the given age: the oldest matching tier wins. A tier matches once the
     * video is at least [AgeTier.minAgeDays] days old (inclusive, measured in whole 24 h days).
     * Returns null when the video is newer than every tier, so it is never touched.
     */
    fun resolve(tiers: List<AgeTier>, age: Duration): AgeTier? {
        val days = age.toDays()
        return tiers.filter { days >= it.minAgeDays }.maxByOrNull { it.minAgeDays }
    }

    /** Age from the capture date, falling back to the date added to the library. */
    fun ageOf(dateTaken: Instant?, dateAdded: Instant?, now: Instant): Duration? {
        val reference = dateTaken ?: dateAdded ?: return null
        return Duration.between(reference, now).takeIf { !it.isNegative } ?: Duration.ZERO
    }

    /** Human-readable problems with a tier list; empty when it is valid. */
    fun validate(tiers: List<AgeTier>): List<String> = buildList {
        if (tiers.any { it.minAgeDays <= 0 }) add("Age thresholds must be positive.")
        tiers.groupBy { it.minAgeDays }.filterValues { it.size > 1 }.keys.sorted().forEach {
            add("Two tiers share the threshold of $it days.")
        }
    }
}
