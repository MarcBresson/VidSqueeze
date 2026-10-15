package app.squeeze.auto

import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScheduleTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val all = DayOfWeek.entries.toSet()

    // 2026-10-14 is a Wednesday.
    private fun at(date: String, time: String, zone: ZoneId = paris) =
        ZonedDateTime.of(java.time.LocalDate.parse(date), LocalTime.parse(time), zone)

    private fun w(days: Set<DayOfWeek>, s: String, e: String) = TimeWindow(days, LocalTime.parse(s), LocalTime.parse(e))

    @Test
    fun simpleWindowBounds() {
        val windows = listOf(w(all, "01:00", "06:00"))
        assertFalse(AutoSchedule.isActive(windows, at("2026-10-14", "00:59")))
        assertTrue(AutoSchedule.isActive(windows, at("2026-10-14", "01:00")))
        assertTrue(AutoSchedule.isActive(windows, at("2026-10-14", "05:59")))
        assertFalse(AutoSchedule.isActive(windows, at("2026-10-14", "06:00")))
    }

    @Test
    fun windowWrappingMidnightBelongsToStartDay() {
        val windows = listOf(w(setOf(FRIDAY), "23:00", "02:00"))
        assertTrue(AutoSchedule.isActive(windows, at("2026-10-16", "23:30"))) // Fri night
        assertTrue(AutoSchedule.isActive(windows, at("2026-10-17", "01:30"))) // Sat early morning
        assertFalse(AutoSchedule.isActive(windows, at("2026-10-16", "01:30"))) // Fri early morning: window starts Thu
        assertFalse(AutoSchedule.isActive(windows, at("2026-10-17", "23:30"))) // Sat night
    }

    @Test
    fun perWeekdayWindows() {
        val windows = listOf(w(setOf(MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, FRIDAY), "00:00", "06:00"), w(setOf(SATURDAY, SUNDAY), "00:00", "10:00"))
        assertFalse(AutoSchedule.isActive(windows, at("2026-10-14", "08:00"))) // Wed
        assertTrue(AutoSchedule.isActive(windows, at("2026-10-17", "08:00"))) // Sat
    }

    @Test
    fun multipleWindowsAndInvalidIgnored() {
        val windows = listOf(w(all, "01:00", "02:00"), w(all, "12:00", "13:00"), w(all, "05:00", "05:00"), w(emptySet(), "00:00", "23:00"))
        assertTrue(AutoSchedule.isActive(windows, at("2026-10-14", "12:30")))
        assertFalse(AutoSchedule.isActive(windows, at("2026-10-14", "05:00")))
        assertFalse(AutoSchedule.isActive(windows, at("2026-10-14", "10:00")))
    }

    @Test
    fun modes() {
        val night = listOf(w(all, "01:00", "06:00"))
        val noon = at("2026-10-14", "12:00")
        assertFalse(AutoSchedule.mayRun(ScheduleMode.WINDOWS, night, noon, externallyTriggered = false))
        assertTrue(AutoSchedule.mayRun(ScheduleMode.WINDOWS, night, noon, externallyTriggered = true))
        assertTrue(AutoSchedule.mayRun(ScheduleMode.WHENEVER_CHARGING, emptyList(), noon, false))
        assertFalse(AutoSchedule.mayRun(ScheduleMode.EXTERNAL_ONLY, night, at("2026-10-14", "02:00"), false))
        assertTrue(AutoSchedule.mayRun(ScheduleMode.EXTERNAL_ONLY, night, noon, true))
    }

    @Test
    fun activeUntilMergesBackToBackWindows() {
        val windows = listOf(w(all, "22:00", "00:00"), w(all, "00:00", "06:00"))
        // 22:00–00:00 is a wrapping window (end < start is false for 00:00? end 00:00 < start 22:00 -> wraps).
        val until = AutoSchedule.activeUntil(windows, at("2026-10-14", "23:00"))
        assertEquals(at("2026-10-15", "06:00"), until)
        assertNull(AutoSchedule.activeUntil(windows, at("2026-10-14", "12:00")))
    }

    @Test
    fun activeUntilForWrappingWindowFromEarlyMorning() {
        val windows = listOf(w(all, "23:00", "02:00"))
        assertEquals(at("2026-10-14", "02:00"), AutoSchedule.activeUntil(windows, at("2026-10-14", "01:00")))
        assertEquals(at("2026-10-15", "02:00"), AutoSchedule.activeUntil(windows, at("2026-10-14", "23:30")))
    }

    @Test
    fun nextStart() {
        val windows = listOf(w(setOf(SATURDAY), "01:00", "06:00"))
        assertEquals(at("2026-10-17", "01:00"), AutoSchedule.nextStart(windows, at("2026-10-14", "12:00")))
        assertEquals(at("2026-10-24", "01:00"), AutoSchedule.nextStart(windows, at("2026-10-17", "01:00")))
        assertNull(AutoSchedule.nextStart(emptyList(), at("2026-10-14", "12:00")))
    }

    @Test
    fun dstDaysUseWallClock() {
        // Europe/Paris springs forward on 2026-03-29 (02:00 -> 03:00) and falls back on 2026-10-25.
        val windows = listOf(w(all, "01:00", "06:00"))
        assertTrue(AutoSchedule.isActive(windows, at("2026-03-29", "03:30")))
        assertTrue(AutoSchedule.isActive(windows, at("2026-10-25", "02:30")))
        // Window end on the spring-forward night is still 06:00 local.
        assertEquals(at("2026-03-29", "06:00"), AutoSchedule.activeUntil(windows, at("2026-03-29", "03:30")))
        // A window starting inside the DST gap opens at the shifted wall-clock time.
        val gap = listOf(w(all, "02:30", "05:00"))
        assertEquals(at("2026-03-29", "03:30"), AutoSchedule.nextStart(gap, at("2026-03-29", "00:30")))
    }
}
