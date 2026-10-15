package app.squeeze.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsValuesTest {
    @Test
    fun unknownThemeFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("SEPIA"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("DARK"))
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromKey(it.name)) }
    }

    @Test
    fun retentionOnlyAcceptsTheOfferedChoices() {
        Retention.CHOICES.forEach { assertEquals(it, Retention.normalize(it)) }
        assertEquals(Retention.DEFAULT_DAYS, Retention.normalize(null))
        assertEquals(Retention.DEFAULT_DAYS, Retention.normalize(-5))
        assertEquals(Retention.DEFAULT_DAYS, Retention.normalize(11))
    }

    @Test
    fun retentionConvertsToMilliseconds() {
        assertEquals(7L * 24 * 60 * 60 * 1000, Retention.maxAgeMs(7))
        assertNull("never means results are kept", Retention.maxAgeMs(Retention.NEVER))
    }

    @Test
    fun theDefaultRetentionIsOffered() {
        assert(Retention.DEFAULT_DAYS in Retention.CHOICES)
    }
}
