package app.squeeze.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import app.squeeze.data.BatchItemEntity
import app.squeeze.engine.BuildResult
import app.squeeze.engine.CodecContainerMatrix
import app.squeeze.engine.EncodeProgress
import app.squeeze.engine.FfmpegCommandBuilder
import app.squeeze.engine.FfmpegKitEngine
import app.squeeze.engine.Issue
import app.squeeze.engine.Preset
import app.squeeze.engine.RunResult
import app.squeeze.engine.Severity
import app.squeeze.finalize.FinalizePlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

sealed interface ItemOutcome {
    data class Done(val output: File, val outputSize: Long, val command: String, val durationMs: Long) : ItemOutcome
    data class NoGain(val outputSize: Long, val command: String, val durationMs: Long) : ItemOutcome
    data class Failed(val message: String, val command: String?) : ItemOutcome
    data object Cancelled : ItemOutcome
}

/** Probes, builds the command and encodes one queue item into the staging directory. */
class ItemProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: FfmpegKitEngine,
    private val current: CurrentJob,
    private val logs: LogStore = LogStore(context),
) {
    val stagingDir get() = File(context.filesDir, "staging")

    /**
     * Never throws except for coroutine cancellation (which means the worker was stopped; the
     * caller re-queues the item). The staged file is deleted on every outcome except [ItemOutcome.Done].
     */
    suspend fun process(
        item: BatchItemEntity,
        preset: Preset,
        onProbed: suspend (durationMs: Long) -> Unit,
        onProgress: (EncodeProgress) -> Unit,
    ): ItemOutcome {
        var output: File? = null
        try {
            val uri = readUri(item, preset)
            val source = engine.probe(engine.inputFor(uri), item.displayName.substringAfterLast('.', ""))
                ?: return ItemOutcome.Failed("Could not read a video stream from this file.", null)
            onProbed(source.durationMs)
            if (current.isCancelRequested) return ItemOutcome.Cancelled

            val container = CodecContainerMatrix.resolveContainer(preset.params, source)
            val extension = container.container?.extension
                ?: return ItemOutcome.Failed(container.issues.errorText(), null)
            // The final file name is the file's name in staging, so resharing shows a proper name.
            val name = FinalizePlanner.copyName(preset, item.displayName, extension, LocalDate.now())
            output = File(stagingDir, "${item.batchId}/${item.id}/$name").also { it.parentFile?.mkdirs() }

            // SAF parameters are single use: the probe consumed the first one.
            val build = FfmpegCommandBuilder.build(
                preset.params, source, engine.inputFor(uri), output.absolutePath, preset.export,
            )
            val command = when (build) {
                is BuildResult.Invalid -> return ItemOutcome.Failed(build.issues.errorText(), null)
                is BuildResult.Ok -> build
            }
            val shown = command.display()

            val log = logs.open(item.id, shown)
            val result = try {
                engine.run(command.args, source.durationMs, onLog = log::append, onProgress = onProgress)
            } finally {
                log.close()
            }
            when (result) {
                RunResult.Cancelled -> {
                    logs.delete(item.id)
                    return ItemOutcome.Cancelled
                }
                is RunResult.Failed ->
                    return ItemOutcome.Failed("ffmpeg failed (code ${result.returnCode})\n${result.logTail}", shown)
                RunResult.Success -> Unit
            }

            val size = output.length()
            if (size <= 0) return ItemOutcome.Failed("ffmpeg produced an empty file.", shown)
            // The original is never replaced by a bigger file (unknown original size: keep the result).
            if (item.originalSize in 1..size) return ItemOutcome.NoGain(size, shown, source.durationMs)
            val done = ItemOutcome.Done(output, size, shown, source.durationMs)
            output = null
            return done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ItemOutcome.Failed(e.message ?: e.javaClass.simpleName, null)
        } finally {
            output?.delete()
        }
    }

    /**
     * The URI to read the source from. When the preset keeps the location and we may read it,
     * ask MediaStore for the unredacted file (otherwise it blanks the GPS tag).
     */
    private fun readUri(item: BatchItemEntity, preset: Preset): Uri {
        val uri = item.sourceUri.toUri()
        val wantsLocation = preset.export.keepExif && preset.export.keepLocation
        val canRead = item.mediaStoreId != null && !item.isImported &&
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (wantsLocation && canRead) MediaStore.setRequireOriginal(uri) else uri
    }

    private fun List<Issue>.errorText() =
        filter { it.severity == Severity.ERROR }.joinToString("\n") { it.message }.ifEmpty { "Invalid settings" }
}
