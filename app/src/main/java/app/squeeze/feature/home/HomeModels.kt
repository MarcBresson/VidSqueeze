package app.squeeze.feature.home

import android.net.Uri
import app.squeeze.auto.VideoInfo

/** What turning on auto compression would do to the videos it would pick up today. */
data class AutoPromo(val videos: Int, val currentBytes: Long, val achievableBytes: Long?) {
    val reductionPercent: Int? get() = Reduction.percent(currentBytes, achievableBytes)

    /** Share of the current weight that would be reclaimed, 0..1. */
    val reclaimedFraction: Float get() = reductionPercent?.let { it / 100f } ?: 0f
}

/** One of the heaviest videos of the library, with the size it would reach with the default preset. */
data class HeavyVideo(val video: VideoInfo, val uri: Uri, val estimatedBytes: Long?) {
    val reductionPercent: Int? get() = Reduction.percent(video.sizeBytes, estimatedBytes)
}

object Reduction {
    /** Whole percent saved going from [original] to [result]; null when the result is unknown. */
    fun percent(original: Long, result: Long?): Int? {
        if (result == null || original <= 0) return null
        return (((original - result).coerceAtLeast(0) * 100) / original).toInt()
    }
}

/** A short resolution name for a badge: "4K", "1080p"... */
object ResolutionLabel {
    /** Based on the long side, so portrait and landscape clips get the same name; null when unknown. */
    fun of(width: Int, height: Int): String? {
        val side = maxOf(width, height)
        return when {
            side <= 0 -> null
            side >= 7680 -> "8K"
            side >= 3840 -> "4K"
            side >= 2560 -> "2K"
            side >= 1920 -> "1080p"
            side >= 1280 -> "720p"
            else -> "SD"
        }
    }
}
