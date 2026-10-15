package app.squeeze.automation

import app.squeeze.engine.ExportMode
import java.security.MessageDigest
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** The public intent API. Action names are stable and independent of the build variant's package. */
object AutomationApi {
    const val PREFIX = "app.squeeze.action."
    const val START_AUTO_RUN = PREFIX + "START_AUTO_RUN"
    const val STOP_AUTO_RUN = PREFIX + "STOP_AUTO_RUN"
    const val PAUSE_QUEUE = PREFIX + "PAUSE_QUEUE"
    const val RESUME_QUEUE = PREFIX + "RESUME_QUEUE"
    const val CANCEL_ALL = PREFIX + "CANCEL_ALL"
    const val SET_AUTO_ENABLED = PREFIX + "SET_AUTO_ENABLED"
    const val COMPRESS_FOLDER = PREFIX + "COMPRESS_FOLDER"

    val ACTIONS = listOf(START_AUTO_RUN, STOP_AUTO_RUN, PAUSE_QUEUE, RESUME_QUEUE, CANCEL_ALL, SET_AUTO_ENABLED, COMPRESS_FOLDER)

    const val EXTRA_TOKEN = "token"
    const val EXTRA_DURATION_MINUTES = "duration_minutes"
    const val EXTRA_UNTIL = "until"
    const val EXTRA_IGNORE_CONSTRAINTS = "ignore_constraints"
    const val EXTRA_PRESET_OVERRIDE = "preset_override"
    const val EXTRA_DRY_RUN = "dry_run"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_FOLDER = "folder"
    const val EXTRA_OLDER_THAN_DAYS = "older_than_days"
    const val EXTRA_PRESET = "preset"
    const val EXTRA_EXPORT_MODE = "export_mode"

    /** Outgoing events (opt-in), broadcast to apps that registered for them. */
    const val EVENT_BATCH_STARTED = "app.squeeze.event.BATCH_STARTED"
    const val EVENT_BATCH_FINISHED = "app.squeeze.event.BATCH_FINISHED"
}

sealed interface AutomationCommand {
    data class StartAutoRun(
        val durationMinutes: Int?,
        val until: LocalTime?,
        val ignoreConstraints: Boolean,
        val presetOverride: String?,
        val dryRun: Boolean,
    ) : AutomationCommand

    data object StopAutoRun : AutomationCommand
    data object PauseQueue : AutomationCommand
    data object ResumeQueue : AutomationCommand
    data object CancelAll : AutomationCommand
    data class SetAutoEnabled(val enabled: Boolean) : AutomationCommand
    data class CompressFolder(
        val folder: String,
        val olderThanDays: Int,
        val preset: String?,
        val exportMode: ExportMode?,
        val ignoreConstraints: Boolean,
    ) : AutomationCommand
}

sealed interface ParseResult {
    data class Ok(val command: AutomationCommand) : ParseResult
    data class Invalid(val reason: String) : ParseResult
}

/**
 * Turns an action and its extras (all as strings, because Tasker, MacroDroid and `am` send numbers
 * and booleans as text) into a command. Pure so the whole API surface is unit tested.
 */
object AutomationParser {
    fun parse(action: String?, extras: Map<String, String?>): ParseResult {
        fun text(key: String) = extras[key]?.trim()?.takeIf { it.isNotEmpty() }
        fun flag(key: String) = text(key)?.lowercase() in setOf("true", "1", "yes", "on")
        fun number(key: String): Result<Int?> {
            val raw = text(key) ?: return Result.success(null)
            return raw.toIntOrNull()?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException("$key must be a number"))
        }

        return when (action) {
            AutomationApi.START_AUTO_RUN -> {
                val minutes = number(AutomationApi.EXTRA_DURATION_MINUTES).getOrElse { return invalid(it) }
                if (minutes != null && minutes <= 0) return ParseResult.Invalid("duration_minutes must be positive")
                val until = text(AutomationApi.EXTRA_UNTIL)?.let { parseTime(it) ?: return ParseResult.Invalid("until must look like 06:30") }
                ParseResult.Ok(
                    AutomationCommand.StartAutoRun(
                        minutes, until, flag(AutomationApi.EXTRA_IGNORE_CONSTRAINTS),
                        text(AutomationApi.EXTRA_PRESET_OVERRIDE), flag(AutomationApi.EXTRA_DRY_RUN),
                    ),
                )
            }
            AutomationApi.STOP_AUTO_RUN -> ParseResult.Ok(AutomationCommand.StopAutoRun)
            AutomationApi.PAUSE_QUEUE -> ParseResult.Ok(AutomationCommand.PauseQueue)
            AutomationApi.RESUME_QUEUE -> ParseResult.Ok(AutomationCommand.ResumeQueue)
            AutomationApi.CANCEL_ALL -> ParseResult.Ok(AutomationCommand.CancelAll)
            AutomationApi.SET_AUTO_ENABLED -> {
                val raw = text(AutomationApi.EXTRA_ENABLED)?.lowercase() ?: return ParseResult.Invalid("enabled is required")
                when (raw) {
                    "true", "1", "yes", "on" -> ParseResult.Ok(AutomationCommand.SetAutoEnabled(true))
                    "false", "0", "no", "off" -> ParseResult.Ok(AutomationCommand.SetAutoEnabled(false))
                    else -> ParseResult.Invalid("enabled must be true or false")
                }
            }
            AutomationApi.COMPRESS_FOLDER -> {
                val folder = text(AutomationApi.EXTRA_FOLDER) ?: return ParseResult.Invalid("folder is required")
                if (!isSafeFolder(folder)) return ParseResult.Invalid("folder must be a relative media folder such as DCIM/Camera")
                val days = number(AutomationApi.EXTRA_OLDER_THAN_DAYS).getOrElse { return invalid(it) } ?: 0
                if (days < 0) return ParseResult.Invalid("older_than_days must not be negative")
                val mode = text(AutomationApi.EXTRA_EXPORT_MODE)?.let { parseExportMode(it) ?: return ParseResult.Invalid("export_mode must be save, replace or ask") }
                ParseResult.Ok(
                    AutomationCommand.CompressFolder(folder, days, text(AutomationApi.EXTRA_PRESET), mode, flag(AutomationApi.EXTRA_IGNORE_CONSTRAINTS)),
                )
            }
            else -> ParseResult.Invalid("unknown action")
        }
    }

    private fun invalid(error: Throwable) = ParseResult.Invalid(error.message ?: "invalid value")

    fun parseTime(text: String): LocalTime? = try {
        LocalTime.parse(text.padStart(5, '0'))
    } catch (e: DateTimeParseException) {
        null
    }

    fun parseExportMode(text: String): ExportMode? = when (text.lowercase()) {
        "save", "save_copies", "copy", "copies" -> ExportMode.SAVE_COPIES
        "replace", "replace_originals" -> ExportMode.REPLACE_ORIGINALS
        "ask", "ask_when_done" -> ExportMode.ASK_WHEN_DONE
        else -> null
    }

    /** Folders are MediaStore-relative: no absolute paths, no `..`, no schemes. */
    fun isSafeFolder(folder: String): Boolean {
        val f = folder.trim()
        return f.isNotEmpty() && !f.startsWith("/") && !f.contains("..") && !f.contains(":") && !f.contains("\\")
    }
}

object AutomationAuth {
    /** Compares in constant time so the secret cannot be guessed byte by byte from response timing. */
    fun tokenMatches(expected: String?, given: String?): Boolean {
        if (expected.isNullOrEmpty() || given == null) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))
    }
}

/** At most [maxEvents] commands per [windowMs]; further ones are refused until the window moves on. */
class RateLimiter(private val maxEvents: Int = 20, private val windowMs: Long = 60_000) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (times.size >= maxEvents) return false
        times.addLast(now)
        return true
    }
}
