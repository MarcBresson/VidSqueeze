package app.squeeze.auto

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRuleResolverTest {
    private val tiers = listOf(AgeTier(30, "fps30"), AgeTier(365, "archive"))

    @Test
    fun newerThanSmallestTierIsUntouched() {
        assertNull(AutoRuleResolver.resolve(tiers, Duration.ofDays(29)))
    }

    @Test
    fun boundaryIsInclusive() {
        assertEquals("fps30", AutoRuleResolver.resolve(tiers, Duration.ofDays(30))?.presetId)
        assertEquals("fps30", AutoRuleResolver.resolve(tiers, Duration.ofDays(364))?.presetId)
        assertEquals("archive", AutoRuleResolver.resolve(tiers, Duration.ofDays(365))?.presetId)
        assertNull(AutoRuleResolver.resolve(tiers, Duration.ofDays(30).minusSeconds(1)))
    }

    @Test
    fun oldestMatchingTierWinsRegardlessOfOrder() {
        assertEquals("archive", AutoRuleResolver.resolve(tiers.reversed(), Duration.ofDays(1000))?.presetId)
    }

    @Test
    fun ageFallsBackToDateAddedAndClampsFuture() {
        val now = Instant.parse("2026-10-14T00:00:00Z")
        assertEquals(Duration.ofDays(10), AutoRuleResolver.ageOf(now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(2)), now))
        assertEquals(Duration.ofDays(2), AutoRuleResolver.ageOf(null, now.minus(Duration.ofDays(2)), now))
        assertNull(AutoRuleResolver.ageOf(null, null, now))
        assertEquals(Duration.ZERO, AutoRuleResolver.ageOf(now.plusSeconds(60), null, now))
    }

    @Test
    fun validateRejectsDuplicatesAndNonPositive() {
        assertTrue(AutoRuleResolver.validate(tiers).isEmpty())
        assertEquals(1, AutoRuleResolver.validate(listOf(AgeTier(30, "a"), AgeTier(30, "b"))).size)
        assertEquals(1, AutoRuleResolver.validate(listOf(AgeTier(0, "a"))).size)
    }
}
