package app.squeeze.engine

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class EncodeProgress(
    val processedMs: Long,
    val sizeBytes: Long,
    /** Media seconds encoded per wall-clock second. */
    val speed: Double,
    /** 0..1, or null when the duration is unknown (show an indeterminate bar). */
    val fraction: Float?,
)

sealed interface RunResult {
    data object Success : RunResult

    /** Cancelled on request. This is not a failure: callers must not report an error for it. */
    data object Cancelled : RunResult
    data class Failed(val returnCode: Int?, val logTail: String) : RunResult
}

/**
 * The only place that talks to FFmpegKit, so the library stays swappable (see ADR 0001).
 */
class FfmpegKitEngine(private val context: Context) {

    /**
     * An ffmpeg/ffprobe input string for [uri]. SAF parameters are single use, so call this once
     * for each consumer (probe and run each need their own).
     */
    fun inputFor(uri: Uri): String = when (uri.scheme) {
        "file" -> requireNotNull(uri.path) { "file URI without a path: $uri" }
        else -> FFmpegKitConfig.getSafParameterForRead(context, uri)
            ?: throw IOException("Cannot open $uri for reading")
    }

    /** @return null when the file is unreadable or holds no video stream. */
    suspend fun probe(input: String, extensionHint: String? = null): SourceInfo? = withContext(Dispatchers.IO) {
        val json = FFprobeKit.getMediaInformation(input).output ?: return@withContext null
        ProbeParser.parse(json, extensionHint)
    }

    /**
     * Runs ffmpeg with [args] and suspends until it ends. Cancelling the coroutine cancels the
     * ffmpeg session; [FFmpegKit.cancel] from elsewhere surfaces as [RunResult.Cancelled].
     */
    suspend fun run(
        args: List<String>,
        durationMs: Long,
        onLog: (String) -> Unit = {},
        onProgress: (EncodeProgress) -> Unit = {},
    ): RunResult = suspendCancellableCoroutine { cont ->
        val logTail = ArrayDeque<String>()
        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { s ->
                val code = s.returnCode
                cont.resume(
                    when {
                        ReturnCode.isSuccess(code) -> RunResult.Success
                        ReturnCode.isCancel(code) -> RunResult.Cancelled
                        else -> RunResult.Failed(code?.value, synchronized(logTail) { logTail.joinToString("\n") }.ifBlank { s.lastLogLines() })
                    },
                )
            },
            { log ->
                val line = log.message.trimEnd()
                synchronized(logTail) {
                    logTail.addLast(line)
                    if (logTail.size > LOG_TAIL_LINES) logTail.removeFirst()
                }
                onLog(line)
            },
            { stats ->
                val processed = stats.time.toLong()
                onProgress(EncodeProgress(processed, stats.size, stats.speed, Progress.fraction(processed, durationMs)))
            },
        )
        cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }

    /** The log callback can lag behind the completion callback, so fall back to the session's own log. */
    private fun com.arthenica.ffmpegkit.Session.lastLogLines(): String =
        allLogsAsString.orEmpty().lines().filter { it.isNotBlank() }.takeLast(LOG_TAIL_LINES).joinToString("\n")

    /** Stops whatever ffmpeg session is running; its [run] then returns [RunResult.Cancelled]. */
    fun cancelRunning() = FFmpegKit.cancel()

    private companion object {
        const val LOG_TAIL_LINES = 40
    }
}
