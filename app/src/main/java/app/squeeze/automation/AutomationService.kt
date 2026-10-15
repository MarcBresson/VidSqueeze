package app.squeeze.automation

import android.os.Bundle
import android.text.format.Formatter
import android.content.Context
import app.squeeze.auto.AutoRunner
import app.squeeze.auto.RunOptions
import app.squeeze.auto.Trigger
import app.squeeze.auto.AutomationLogEntry
import app.squeeze.data.AutoSettingsRepository
import app.squeeze.engine.BuiltInPresets
import app.squeeze.work.QueueManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class AutomationOutcome(val accepted: Boolean, val detail: String)

/**
 * Runs commands from other apps. Every command is checked (switch on, token, rate limit, valid
 * arguments) and every outcome, accepted or not, goes to the automation log the user can read.
 */
@Singleton
class AutomationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AutoSettingsRepository,
    private val runner: AutoRunner,
    private val queue: QueueManager,
) {
    private val limiter = RateLimiter()

    suspend fun handle(action: String?, extras: Bundle?): AutomationOutcome {
        val outcome = execute(action, extras)
        settings.appendLog(AutomationLogEntry(System.currentTimeMillis(), action?.removePrefix(AutomationApi.PREFIX) ?: "?", outcome.accepted, outcome.detail))
        return outcome
    }

    private suspend fun execute(action: String?, extras: Bundle?): AutomationOutcome {
        if (!settings.automationEnabled.first()) return AutomationOutcome(false, "external automation is off")
        if (!limiter.tryAcquire(System.currentTimeMillis())) return AutomationOutcome(false, "too many commands")
        if (!AutomationAuth.tokenMatches(settings.automationToken.first(), extras?.getString(AutomationApi.EXTRA_TOKEN))) {
            return AutomationOutcome(false, "wrong or missing token")
        }
        return when (val parsed = AutomationParser.parse(action, extras.asStrings())) {
            is ParseResult.Invalid -> AutomationOutcome(false, parsed.reason)
            is ParseResult.Ok -> try {
                run(parsed.command)
            } catch (e: Exception) {
                AutomationOutcome(false, "failed: ${e.message}")
            }
        }
    }

    private suspend fun run(command: AutomationCommand): AutomationOutcome = when (command) {
        is AutomationCommand.StartAutoRun -> {
            val result = runner.run(
                RunOptions(Trigger.EXTERNAL, command.ignoreConstraints, command.durationMinutes, command.until, command.presetOverride, command.dryRun),
            )
            describe(result, command.dryRun)
        }
        AutomationCommand.StopAutoRun -> { runner.stop(); AutomationOutcome(true, "auto run stopped") }
        AutomationCommand.PauseQueue -> { queue.pause(); AutomationOutcome(true, "queue paused") }
        AutomationCommand.ResumeQueue -> { queue.resume(); AutomationOutcome(true, "queue resumed") }
        AutomationCommand.CancelAll -> { queue.cancelAll(); AutomationOutcome(true, "queue cancelled") }
        is AutomationCommand.SetAutoEnabled -> {
            settings.update { it.copy(enabled = command.enabled) }
            AutomationOutcome(true, if (command.enabled) "auto compression on" else "auto compression off")
        }
        is AutomationCommand.CompressFolder -> {
            val result = runner.run(
                RunOptions(
                    Trigger.EXTERNAL, command.ignoreConstraints,
                    presetOverride = command.preset ?: BuiltInPresets.SHARE_ID,
                    folder = command.folder, olderThanDays = command.olderThanDays, exportModeOverride = command.exportMode,
                ),
            )
            describe(result, dryRun = false)
        }
    }

    private fun describe(result: app.squeeze.auto.AutoRunResult, dryRun: Boolean): AutomationOutcome {
        result.skippedReason?.let { return AutomationOutcome(true, "nothing started: $it") }
        val size = Formatter.formatShortFileSize(context, result.bytes)
        return when {
            result.videos == 0 -> AutomationOutcome(true, "nothing to compress")
            dryRun -> AutomationOutcome(true, "dry run: ${result.videos} videos ($size), about ${Formatter.formatShortFileSize(context, result.estimatedSavedBytes)} to save")
            else -> AutomationOutcome(true, "queued ${result.videos} videos ($size)")
        }
    }

    companion object {
        /** A fresh 192-bit secret. */
        fun newToken(): String {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

@Suppress("DEPRECATION")
private fun Bundle?.asStrings(): Map<String, String?> =
    this?.keySet()?.associateWith { get(it)?.toString() }.orEmpty()
