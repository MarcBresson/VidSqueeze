package app.squeeze.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThrottleTest {
    @Test
    fun `lets one update through per interval`() {
        var now = 5_000L
        val throttle = ProgressThrottle(1_000) { now }
        assertTrue(throttle.tryAcquire())
        now += 999
        assertFalse(throttle.tryAcquire())
        now += 1
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `first call passes even at clock zero`() {
        assertTrue(ProgressThrottle(1_000) { 0L }.tryAcquire())
    }

    @Test
    fun `formats durations coarsely`() {
        assertEquals("40 s", NotificationHelper.formatDuration(40_000))
        assertEquals("12 min", NotificationHelper.formatDuration(12 * 60_000L))
        assertEquals("1 h 05 min", NotificationHelper.formatDuration(65 * 60_000L))
        assertEquals("0 s", NotificationHelper.formatDuration(-5))
    }
}
