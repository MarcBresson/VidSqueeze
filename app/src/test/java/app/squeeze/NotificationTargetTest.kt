package app.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationTargetTest {
    @Test
    fun `a non negative extra is a batch id`() {
        assertEquals(42L, NotificationTarget.batchId(42L))
        assertEquals(0L, NotificationTarget.batchId(0L))
    }

    @Test
    fun `the sentinel and negatives mean no batch`() {
        assertNull(NotificationTarget.batchId(-1L))
        assertNull(NotificationTarget.batchId(Long.MIN_VALUE))
    }
}
