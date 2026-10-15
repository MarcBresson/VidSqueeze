package app.squeeze.engine

object Progress {
    /**
     * Encode progress in 0..1 from ffmpeg's processed media time, or null when the duration is
     * unknown or zero (callers show an indeterminate bar instead of dividing by zero).
     */
    fun fraction(processedMs: Long, durationMs: Long): Float? =
        if (durationMs <= 0) null else (processedMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()

    /** Remaining wall-clock time from ffmpeg's `speed` (media seconds per second); null if unknowable. */
    fun etaMs(processedMs: Long, durationMs: Long, speed: Double): Long? =
        if (durationMs <= 0 || speed <= 0.0) null
        else ((durationMs - processedMs).coerceAtLeast(0) / speed).toLong()
}
