package app.squeeze.data

import app.squeeze.auto.AutoGates
import app.squeeze.auto.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunLimitsTest {
    private val charging = DeviceState(true, 80)

    @Test
    fun `a run goes on until its deadline`() {
        val limits = RunLimits(deadlineMs = 1000, gates = null)
        assertNull(limits.stopReason(999, charging))
        assertEquals("the off-peak window ended", limits.stopReason(1000, charging))
    }

    @Test
    fun `a run without a deadline only stops when a gate fails`() {
        val limits = RunLimits(null, AutoGates(requireCharging = true, minBatteryPercent = 20))
        assertNull(limits.stopReason(Long.MAX_VALUE, charging))
        assertEquals("not charging", limits.stopReason(0, DeviceState(false, 80)))
    }

    @Test
    fun `no limits means no stop`() {
        assertNull(RunLimits(null, null).stopReason(Long.MAX_VALUE, DeviceState(false, 1)))
    }
}
