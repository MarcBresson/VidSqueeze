package app.squeeze.work

/** Lets a value through at most once per [minIntervalMs] (progress callbacks arrive many times a second). */
class ProgressThrottle(
    private val minIntervalMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var last = Long.MIN_VALUE

    /** True when the caller should publish now. */
    fun tryAcquire(): Boolean {
        val now = clock()
        if (last != Long.MIN_VALUE && now - last < minIntervalMs) return false
        last = now
        return true
    }
}
