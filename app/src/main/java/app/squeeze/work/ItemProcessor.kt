package app.squeeze.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import app.squeeze.data.BatchItemEntity
import app.squeeze.engine.BuildOptions
import app.squeeze.engine.BuildResult
import app.squeeze.engine.CodecContainerMatrix
import app.squeeze.engine.EncodeProgress
import app.squeeze.engine.FfmpegCommandBuilder
import app.squeeze.engine.FfmpegKitEngine
import app.squeeze.engine.HardwareEncoderProbe
import app.squeeze.engine.ImageEncodeRequest
import app.squeeze.engine.ImageEncodeResult
import app.squeeze.engine.ImageEngines
import app.squeeze.engine.ImageFormat
import app.squeeze.engine.Issue
import app.squeeze.engine.MediaKind
import app.squeeze.engine.Preset
import app.squeeze.engine.RunResult
import app.squeeze.engine.Severity
import app.squeeze.finalize.FinalizePlanner
import app.squeeze.finalize.MetadataCopier
import app.squeeze.finalize.MetadataFilter
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
    private val hardware: HardwareEncoderProbe,
    private val imageEngines: ImageEngines,
    private val metadata: MetadataCopier,
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
        if (item.kind == MediaKind.IMAGE) return processImage(item, preset, onProbed, onProgress)
        var output: File? = null
        try {
            val uri = readUri(item, preset)
            // Dual presets carry both params blocks; the item's kind decides which applies.
            val videoParams = preset.params ?: return ItemOutcome.Failed("This preset cannot compress videos.", null)
            val source = engine.probe(engine.inputFor(uri), item.displayName.substringAfterLast('.', ""))
                ?: return ItemOutcome.Failed("Could not read a video stream from this file.", null)
            onProbed(source.durationMs)
            if (current.isCancelRequested) return ItemOutcome.Cancelled

            val container = CodecContainerMatrix.resolveContainer(videoParams, source)
            val extension = container.container?.extension
                ?: return ItemOutcome.Failed(container.issues.errorText(), null)
            // The final file name is the file's name in staging, so resharing shows a proper name.
            val name = FinalizePlanner.copyName(preset, item.displayName, extension, LocalDate.now())
            output = File(stagingDir, "${item.batchId}/${item.id}/$name").also { it.parentFile?.mkdirs() }

            // SAF parameters are single use: the probe consumed the first one.
            val build = FfmpegCommandBuilder.build(
                videoParams, source, engine.inputFor(uri), output.absolutePath, preset.export,
                BuildOptions(hardware = hardware.encoders),
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

    /** The still-image branch of [process]: probe, pre-read EXIF, encode, then copy metadata. */
    private suspend fun processImage(
        item: BatchItemEntity,
        preset: Preset,
        onProbed: suspend (durationMs: Long) -> Unit,
        onProgress: (EncodeProgress) -> Unit,
    ): ItemOutcome {
        var output: File? = null
        try {
            val imageParams = preset.imageParams
                ?: return ItemOutcome.Failed("This preset cannot compress photos.", null)
            val uri = readUri(item, preset)
            val input = engine.inputFor(uri)
            val source = engine.probe(input, item.displayName.substringAfterLast('.', ""))
                ?: return ItemOutcome.Failed("Could not read this image.", null)
            // Decision: animated image inputs are rejected, not silently truncated to one frame.
            if (source.frameCount > 1) return ItemOutcome.Failed("Animated images are not supported yet.", null)
            onProbed(source.durationMs)
            if (current.isCancelRequested) return ItemOutcome.Cancelled

            val name = FinalizePlanner.copyName(preset, item.displayName, imageParams.format.extension, LocalDate.now())
            output = File(stagingDir, "${item.batchId}/${item.id}/$name").also { it.parentFile?.mkdirs() }

            val exif = metadata.readSource(item.sourceUri.toUri())
            val filtered = exif?.tags?.let {
                MetadataFilter.filter(it, preset.export.keepExif, preset.export.keepLocation)
            } ?: emptyMap()
            val keepMetadata = filtered.isNotEmpty()

            val log = logs.open(item.id, imageEngines.describe(imageParams))
            val result = try {
                imageEngines.encode(
                    ImageEncodeRequest(
                        input = input,
                        sourceUri = uri,
                        output = output,
                        params = imageParams,
                        source = source,
                        orientationDegrees = exif?.orientationDegrees ?: 0,
                        exifBytes = null, // JXL takes a raw TIFF block ExifInterface cannot produce (see MetadataCopier)
                        onLog = log::append,
                        onProgress = { f -> onProgress(EncodeProgress(0, 0, 0.0, f)) },
                    ),
                )
            } finally {
                log.close()
            }
            when (result) {
                ImageEncodeResult.Cancelled -> {
                    logs.delete(item.id)
                    return ItemOutcome.Cancelled
                }
                is ImageEncodeResult.Failed -> return ItemOutcome.Failed(result.message, imageEngines.describe(imageParams))
                ImageEncodeResult.Success -> Unit
            }

            // Metadata copy is best-effort: a format that cannot carry EXIF only logs a note.
            val metadataNote = when {
                imageParams.format == ImageFormat.JPEG_XL && keepMetadata ->
                    "NOTE: JPEG XL output does not carry this photo's EXIF yet."
                imageParams.format in listOf(ImageFormat.PNG, ImageFormat.AVIF) && keepMetadata ->
                    "NOTE: ${imageParams.format} output cannot carry EXIF in this app."
                else -> null
            }
            if (metadataNote != null) logs.append(item.id, metadataNote)
            if (!metadata.copyTo(output, exif?.tags ?: emptyMap(), preset.export.keepExif, preset.export.keepLocation)) {
                logs.append(item.id, "NOTE: the photo metadata could not be copied to the output.")
            }

            val size = output.length()
            if (size <= 0) return ItemOutcome.Failed("The encoder produced an empty file.", imageEngines.describe(imageParams))
            if (item.originalSize in 1..size) return ItemOutcome.NoGain(size, imageEngines.describe(imageParams), source.durationMs)
            val done = ItemOutcome.Done(output, size, imageEngines.describe(imageParams), source.durationMs)
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
