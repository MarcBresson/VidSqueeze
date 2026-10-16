package app.squeeze.feature.compare

import kotlin.math.abs

/** Decides when two players that should show the same moment have drifted too far apart. */
object SyncPolicy {
    /** About four frames at 30 fps: below this the two panes look in step. */
    const val MAX_DRIFT_MS = 120L

    fun needsResync(leaderMs: Long, followerMs: Long): Boolean = abs(leaderMs - followerMs) > MAX_DRIFT_MS

    /** "1:05" or "1:02:03". */
    fun formatClock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/** How the two videos share the screen. */
enum class CompareLayout {
    /** Left and right, divided by a vertical line. */
    SIDE_BY_SIDE,

    /** Top and bottom, divided by a horizontal line. */
    STACKED,

    /** One full-size video; a tap flips between original and compressed. */
    SWITCH,
}

/** The two videos being compared. */
enum class CompareSide {
    ORIGINAL, COMPRESSED;

    val other get() = if (this == ORIGINAL) COMPRESSED else ORIGINAL
}
