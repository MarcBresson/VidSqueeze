package app.squeeze.feature.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    @Test
    fun smallDriftIsIgnoredInEitherDirection() {
        assertFalse(SyncPolicy.needsResync(10_000, 10_000))
        assertFalse(SyncPolicy.needsResync(10_000, 10_000 + SyncPolicy.MAX_DRIFT_MS))
        assertFalse(SyncPolicy.needsResync(10_000, 10_000 - SyncPolicy.MAX_DRIFT_MS))
    }

    @Test
    fun largerDriftIsCorrectedInEitherDirection() {
        assertTrue(SyncPolicy.needsResync(10_000, 10_000 + SyncPolicy.MAX_DRIFT_MS + 1))
        assertTrue(SyncPolicy.needsResync(10_000, 10_000 - SyncPolicy.MAX_DRIFT_MS - 1))
    }

    @Test
    fun clockFormatting() {
        assertEquals("0:00", SyncPolicy.formatClock(-5))
        assertEquals("0:09", SyncPolicy.formatClock(9_999))
        assertEquals("1:05", SyncPolicy.formatClock(65_000))
        assertEquals("1:02:03", SyncPolicy.formatClock(3_723_000))
    }

    @Test
    fun otherSideFlips() {
        assertEquals(CompareSide.COMPRESSED, CompareSide.ORIGINAL.other)
        assertEquals(CompareSide.ORIGINAL, CompareSide.COMPRESSED.other)
    }
}
