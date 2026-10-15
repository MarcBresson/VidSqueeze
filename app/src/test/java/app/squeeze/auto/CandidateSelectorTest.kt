package app.squeeze.auto

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateSelectorTest {
    private val day = 24L * 60 * 60 * 1000
    private val mb = 1024L * 1024
    private val now = Instant.ofEpochMilli(2_000 * day)
    private val config = AutoConfig(
        tiers = listOf(AgeTier(30, "a"), AgeTier(365, "b")),
        minSizeMb = 20, minBitrateKbps = 3_000,
    )

    private fun video(
        id: Long, ageDays: Long = 100, size: Long = 200 * mb, durationMs: Long = 60_000,
        path: String = "DCIM/Camera/", taken: Boolean = true,
    ) = VideoInfo(
        id, "v$id.mp4", path, size, durationMs, 1920, 1080,
        dateTakenMs = if (taken) now.toEpochMilli() - ageDays * day else null,
        dateAddedMs = now.toEpochMilli() - ageDays * day,
    )

    private fun ids(vararg v: VideoInfo, cfg: AutoConfig = config, skip: Set<Long> = emptySet()) =
        CandidateSelector.select(v.toList(), cfg, now, skip).map { it.video.id }

    @Test
    fun `videos newer than the youngest tier are never touched`() {
        assertEquals(emptyList<Long>(), ids(video(1, ageDays = 29)))
        assertEquals(listOf(2L), ids(video(2, ageDays = 30)))
    }

    @Test
    fun `the oldest matching tier decides the preset`() {
        val result = CandidateSelector.select(listOf(video(1, ageDays = 100), video(2, ageDays = 400)), config, now, emptySet())
        assertEquals(listOf("a", "b"), result.sortedBy { it.video.id }.map { it.tier.presetId })
    }

    @Test
    fun `small and already light videos are left alone`() {
        assertEquals(emptyList<Long>(), ids(video(1, size = 10 * mb)))
        // 200 MB over 10 minutes is about 2.8 Mbit/s: below the 3000 kbit/s gate.
        assertEquals(emptyList<Long>(), ids(video(2, size = 200 * mb, durationMs = 600_000)))
        assertEquals(listOf(3L), ids(video(3, size = 200 * mb, durationMs = 60_000)))
    }

    @Test
    fun `an unknown duration passes the bitrate gate`() {
        assertEquals(listOf(1L), ids(video(1, durationMs = 0)))
    }

    @Test
    fun `date added stands in for a missing capture date`() {
        assertEquals(listOf(1L), ids(video(1, ageDays = 100, taken = false)))
    }

    @Test
    fun `processed and queued videos are skipped`() {
        assertEquals(listOf(2L), ids(video(1), video(2), skip = setOf(1L)))
    }

    @Test
    fun `excluded folders match by name or path prefix and our own folder is always excluded`() {
        val cfg = config.copy(excludedFolders = listOf("WhatsApp", "DCIM/Screenshots"))
        assertEquals(
            listOf(1L, 5L), // "WhatsAppish" is not the "WhatsApp" folder
            ids(
                video(1, path = "DCIM/Camera/"),
                video(2, path = "Movies/WhatsApp/Sent/"),
                video(3, path = "DCIM/Screenshots/"),
                video(4, path = "Movies/Squeeze/"),
                video(5, path = "DCIM/WhatsAppish/"),
                cfg = cfg,
            ).sorted(),
        )
        assertTrue(CandidateSelector.isExcluded("Movies/Squeeze/", emptyList()))
        assertFalse(CandidateSelector.isExcluded("Movies/Squeezed/", emptyList()))
    }

    @Test
    fun `order is oldest first or largest first`() {
        val vs = arrayOf(video(1, ageDays = 50, size = 900 * mb), video(2, ageDays = 500, size = 300 * mb), video(3, ageDays = 100, size = 600 * mb))
        assertEquals(listOf(2L, 3L, 1L), ids(*vs))
        assertEquals(listOf(1L, 3L, 2L), ids(*vs, cfg = config.copy(order = AutoOrder.LARGEST_FIRST)))
    }

    @Test
    fun `heavy video suggestions are the biggest unprocessed files`() {
        val result = HeavyVideoSelector.select(
            listOf(video(1, size = 300 * mb), video(2, size = 50 * mb), video(3, size = 900 * mb), video(4, size = 500 * mb, path = "Movies/Squeeze/")),
            skip = setOf(1L), limit = 5,
        )
        assertEquals(listOf(3L), result.map { it.id })
    }

    private val zone = ZoneId.of("Europe/Paris")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 3, 4, h, m, 0, 0, zone)
    private val night = AutoConfig(windows = listOf(TimeWindow(DayOfWeek.entries.toSet(), LocalTime.of(1, 0), LocalTime.of(6, 0))))

    @Test
    fun `a scheduled run ends with the window`() {
        assertEquals(at(6).toInstant().toEpochMilli(), AutoRunPlan.deadline(night, at(2)))
        assertNull(AutoRunPlan.deadline(night, at(12)))
        assertNull(AutoRunPlan.deadline(night.copy(mode = ScheduleMode.WHENEVER_CHARGING), at(2)))
    }

    @Test
    fun `external limits win over the window and the earliest applies`() {
        assertEquals(at(2, 30).toInstant().toEpochMilli(), AutoRunPlan.deadline(night, at(2), durationMinutes = 30))
        assertEquals(at(3).toInstant().toEpochMilli(), AutoRunPlan.deadline(night, at(2), durationMinutes = 90, until = LocalTime.of(3, 0)))
        // "until 01:00" asked at 02:00 means tomorrow.
        assertEquals(at(1).plusDays(1).toInstant().toEpochMilli(), AutoRunPlan.deadline(night.copy(mode = ScheduleMode.EXTERNAL_ONLY), at(2), until = LocalTime.of(1, 0)))
    }

    @Test
    fun `gates demand a charger and enough battery`() {
        val gates = AutoGates(requireCharging = true, minBatteryPercent = 30)
        assertNull(gates.problem(DeviceState(charging = true, batteryPercent = 5)))
        assertEquals("not charging", gates.problem(DeviceState(charging = false, batteryPercent = 90)))
        val onBattery = AutoGates(requireCharging = false, minBatteryPercent = 30)
        assertNull(onBattery.problem(DeviceState(false, 60)))
        assertEquals("battery below 30%", onBattery.problem(DeviceState(false, 10)))
    }

    @Test
    fun `config survives a round trip and bad input falls back to defaults`() {
        val custom = AutoConfig(
            enabled = true, mode = ScheduleMode.EXTERNAL_ONLY,
            windows = listOf(TimeWindow(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), LocalTime.of(22, 30), LocalTime.of(5, 15))),
            tiers = listOf(AgeTier(7, "x")), excludedFolders = listOf("WhatsApp"), order = AutoOrder.LARGEST_FIRST, minSizeMb = 5,
        )
        assertEquals(custom, AutoConfigJson.decode(AutoConfigJson.encode(custom)))
        assertEquals(AutoConfig(), AutoConfigJson.decode("{ not json"))
        assertEquals(AutoConfig(), AutoConfigJson.decode(null))
        assertEquals(AutoConfig().tiers, AutoConfigJson.decode("""{"enabled":true}""").tiers)
    }
}
