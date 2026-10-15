package app.squeeze.automation

import app.squeeze.auto.AutomationLogEntry
import app.squeeze.auto.AutomationLogJson
import app.squeeze.engine.ExportMode
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationParserTest {
    private fun parse(action: String, vararg extras: Pair<String, String?>) = AutomationParser.parse(action, extras.toMap())
    private fun ok(result: ParseResult) = (result as ParseResult.Ok).command
    private fun reason(result: ParseResult) = (result as ParseResult.Invalid).reason

    @Test
    fun `start auto run reads its optional extras as text`() {
        val command = ok(
            parse(
                AutomationApi.START_AUTO_RUN,
                "duration_minutes" to "90", "until" to "6:30", "ignore_constraints" to "TRUE",
                "preset_override" to "Max savings", "dry_run" to "1",
            ),
        )
        assertEquals(AutomationCommand.StartAutoRun(90, LocalTime.of(6, 30), true, "Max savings", true), command)
        assertEquals(
            AutomationCommand.StartAutoRun(null, null, false, null, false),
            ok(parse(AutomationApi.START_AUTO_RUN)),
        )
    }

    @Test
    fun `bad numbers and times are refused with a reason`() {
        assertEquals("duration_minutes must be a number", reason(parse(AutomationApi.START_AUTO_RUN, "duration_minutes" to "soon")))
        assertEquals("duration_minutes must be positive", reason(parse(AutomationApi.START_AUTO_RUN, "duration_minutes" to "0")))
        assertEquals("until must look like 06:30", reason(parse(AutomationApi.START_AUTO_RUN, "until" to "25:99")))
    }

    @Test
    fun `control actions need no extras`() {
        assertEquals(AutomationCommand.StopAutoRun, ok(parse(AutomationApi.STOP_AUTO_RUN)))
        assertEquals(AutomationCommand.PauseQueue, ok(parse(AutomationApi.PAUSE_QUEUE)))
        assertEquals(AutomationCommand.ResumeQueue, ok(parse(AutomationApi.RESUME_QUEUE)))
        assertEquals(AutomationCommand.CancelAll, ok(parse(AutomationApi.CANCEL_ALL)))
    }

    @Test
    fun `set auto enabled needs a clear yes or no`() {
        assertEquals(AutomationCommand.SetAutoEnabled(true), ok(parse(AutomationApi.SET_AUTO_ENABLED, "enabled" to "on")))
        assertEquals(AutomationCommand.SetAutoEnabled(false), ok(parse(AutomationApi.SET_AUTO_ENABLED, "enabled" to "false")))
        assertEquals("enabled is required", reason(parse(AutomationApi.SET_AUTO_ENABLED)))
        assertEquals("enabled must be true or false", reason(parse(AutomationApi.SET_AUTO_ENABLED, "enabled" to "maybe")))
    }

    @Test
    fun `compress folder accepts only relative media folders`() {
        val command = ok(
            parse(AutomationApi.COMPRESS_FOLDER, "folder" to "DCIM/Camera", "older_than_days" to "30", "preset" to "Share", "export_mode" to "replace"),
        )
        assertEquals(AutomationCommand.CompressFolder("DCIM/Camera", 30, "Share", ExportMode.REPLACE_ORIGINALS, false), command)
        listOf("/sdcard/DCIM", "../secret", "DCIM/../../x", "content://media/x", "C:\\x").forEach {
            assertTrue(it, parse(AutomationApi.COMPRESS_FOLDER, "folder" to it) is ParseResult.Invalid)
        }
        assertEquals("folder is required", reason(parse(AutomationApi.COMPRESS_FOLDER)))
        assertEquals("export_mode must be save, replace or ask", reason(parse(AutomationApi.COMPRESS_FOLDER, "folder" to "DCIM", "export_mode" to "nuke")))
    }

    @Test
    fun `unknown actions are refused`() {
        assertEquals("unknown action", reason(parse("app.squeeze.action.FORMAT_PHONE")))
        assertEquals("unknown action", reason(AutomationParser.parse(null, emptyMap())))
    }

    @Test
    fun `token comparison needs an exact non empty match`() {
        assertTrue(AutomationAuth.tokenMatches("s3cret", "s3cret"))
        assertFalse(AutomationAuth.tokenMatches("s3cret", "s3cre"))
        assertFalse(AutomationAuth.tokenMatches("s3cret", null))
        assertFalse(AutomationAuth.tokenMatches(null, null))
        assertFalse(AutomationAuth.tokenMatches("", ""))
    }

    @Test
    fun `rate limiter allows a burst then refuses until the window moves on`() {
        val limiter = RateLimiter(maxEvents = 3, windowMs = 1000)
        assertTrue(limiter.tryAcquire(0))
        assertTrue(limiter.tryAcquire(100))
        assertTrue(limiter.tryAcquire(200))
        assertFalse(limiter.tryAcquire(300))
        assertTrue(limiter.tryAcquire(1000))
    }

    @Test
    fun `log keeps the newest entries first and is capped`() {
        var log = emptyList<AutomationLogEntry>()
        repeat(AutomationLogJson.MAX_ENTRIES + 5) { log = AutomationLogJson.append(log, AutomationLogEntry(it.toLong(), "A", true, "d$it")) }
        assertEquals(AutomationLogJson.MAX_ENTRIES, log.size)
        assertEquals((AutomationLogJson.MAX_ENTRIES + 4).toLong(), log.first().timeMs)
        assertEquals(log, AutomationLogJson.decode(AutomationLogJson.encode(log)))
        assertEquals(emptyList<AutomationLogEntry>(), AutomationLogJson.decode("garbage"))
    }

    @Test
    fun `generated tokens are long and different each time`() {
        val a = AutomationService.newToken()
        assertTrue(a.length >= 32)
        assertFalse(a == AutomationService.newToken())
    }
}
