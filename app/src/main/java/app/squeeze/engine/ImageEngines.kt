package app.squeeze.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One still-image encode. [input] is an FFmpegKit input token (SAF parameter or path) for the
 * FFmpeg path; [sourceUri] is the readable original for the platform and libjxl decoders.
 * [orientationDegrees] is the EXIF rotation to bake into the pixels (image demuxers ignore it);
 * [exifBytes] is the filtered EXIF block to embed (libjxl only — see MetadataCopier).
 */
data class ImageEncodeRequest(
    val input: String,
    val sourceUri: android.net.Uri,
    val output: File,
    val params: ImageParams,
    val source: SourceInfo,
    val orientationDegrees: Int,
    val exifBytes: ByteArray?,
    val onLog: (String) -> Unit = {},
    val onProgress: (Float) -> Unit = {},
)

sealed interface ImageEncodeResult {
    data object Success : ImageEncodeResult
    data object Cancelled : ImageEncodeResult
    data class Failed(val message: String) : ImageEncodeResult
}

/**
 * Routes each image format to the encoder that has one: FFmpeg (JPEG/PNG/AVIF), the platform
 * Bitmap API (WebP — the bundled FFmpeg has no libwebp encoder) and the bundled libjxl (JPEG XL).
 */
@Singleton
class ImageEngines @Inject constructor(
    private val ffmpeg: FfmpegKitEngine,
    @ApplicationContext private val context: Context,
) {
    suspend fun encode(request: ImageEncodeRequest): ImageEncodeResult = withContext(Dispatchers.IO) {
        when (request.params.format) {
            ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.AVIF -> encodeWithFfmpeg(request)
            ImageFormat.WEBP -> encodeWithPlatform(request)
            ImageFormat.JPEG_XL -> encodeWithJxl(request)
        }
    }

    /** Pseudo-command for the history "exact command" column of non-FFmpeg encoders. */
    fun describe(params: ImageParams): String = when (params.format) {
        ImageFormat.WEBP ->
            "platform-webp quality=${params.quality} lossless=${params.webpLossless}"
        ImageFormat.JPEG_XL -> with(params.jxl) {
            "libjxl effort=$effort distance=$distance modular=$modular colorspace=$colorspace bitDepth=${bitDepth.takeIf { it > 0 } ?: "source"}"
        }
        else -> "ffmpeg"
    }

    private suspend fun encodeWithFfmpeg(request: ImageEncodeRequest): ImageEncodeResult {
        val build = ImageCommandBuilder.build(
            request.params, request.source, request.orientationDegrees, request.input, request.output.absolutePath,
        )
        return when (build) {
            is ImageBuildInvalid ->
                ImageEncodeResult.Failed(build.issues.filter { it.severity == Severity.ERROR }
                    .joinToString("\n") { it.message }.ifEmpty { "Invalid image settings" })
            is ImageBuildOk -> {
                build.issues.forEach { request.onLog("NOTE: ${it.message}") }
                val result = ffmpeg.run(build.args, durationMs = 0, onLog = request.onLog)
                when (result) {
                    RunResult.Success -> ImageEncodeResult.Success
                    RunResult.Cancelled -> ImageEncodeResult.Cancelled
                    is RunResult.Failed -> ImageEncodeResult.Failed("ffmpeg failed (code ${result.returnCode})\n${result.logTail}")
                }
            }
        }
    }

    /**
     * WebP through the platform: ImageDecoder (honours EXIF orientation, gives us the pixels)
     * + Bitmap.compress. 8-bit RGBA only — a deeper source is downconverted (documented).
     */
    private suspend fun encodeWithPlatform(request: ImageEncodeRequest): ImageEncodeResult {
        request.onProgress(0f)
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, request.sourceUri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            } ?: return ImageEncodeResult.Failed("Could not decode the image for WebP encoding.")
            val scaled = limitShortSide(bitmap, request.params.maxShortSide)
            request.output.outputStream().use { out ->
                val format = if (request.params.webpLossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                if (!scaled.compress(format, request.params.quality, out)) {
                    return ImageEncodeResult.Failed("WebP encoding failed.")
                }
            }
            request.onProgress(1f)
            ImageEncodeResult.Success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) ImageEncodeResult.Cancelled
            else ImageEncodeResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** JPEG XL through the bundled libjxl: FFmpeg decodes to raw pixels, libjxl encodes them. */
    private suspend fun encodeWithJxl(request: ImageEncodeRequest): ImageEncodeResult {
        request.onProgress(0f)
        val jxl = request.params.jxl
        val hasAlpha = true // decode as RGBA so alpha survives PNG sources
        val raw = File(context.cacheDir, "jxl-raw-${request.output.nameWithoutExtension}.raw")
        return try {
            val filters = mutableListOf<String>()
            when (request.orientationDegrees) {
                90 -> filters += "transpose=1"
                180 -> filters += "transpose=1,transpose=1"
                270 -> filters += "transpose=2"
            }
            val limit = request.params.maxShortSide
            if (limit != null && request.source.dimensionsKnown && minOf(request.source.displayWidth, request.source.displayHeight) > limit) {
                val scale = limit.toDouble() / minOf(request.source.displayWidth, request.source.displayHeight)
                filters += "scale=${kotlin.math.round(request.source.displayWidth * scale).toInt()}x${kotlin.math.round(request.source.displayHeight * scale).toInt()}"
            }
            // 16-bit decode for deep sources; 8-bit RGBA otherwise (the common phone case).
            val deep = request.params.jxl.bitDepth >= 16
            val pixFmt = if (deep) "rgba64le" else "rgba"
            val args = mutableListOf("-y", "-i", request.input, "-map", "0:v:0", "-frames:v", "1")
            if (filters.isNotEmpty()) args += listOf("-vf", filters.joinToString(","))
            args += listOf("-pix_fmt", pixFmt, "-f", "rawvideo", raw.absolutePath)

            val result = ffmpeg.run(args, durationMs = 0, onLog = request.onLog)
            if (result != RunResult.Success) {
                return if (result == RunResult.Cancelled) ImageEncodeResult.Cancelled
                else ImageEncodeResult.Failed("Decoding for JPEG XL encoding failed.")
            }

            val bytes = raw.readBytes()
            // The encoder needs the *post-filter* dimensions: rotation swaps w/h, scaling shrinks them.
            val short = minOf(request.source.displayWidth, request.source.displayHeight)
            val scale = limit?.takeIf { short > it && request.source.dimensionsKnown }?.toDouble()?.div(short) ?: 1.0
            val scaledW = kotlin.math.round(request.source.displayWidth * scale).toInt().coerceAtLeast(1)
            val scaledH = kotlin.math.round(request.source.displayHeight * scale).toInt().coerceAtLeast(1)
            val (w, h) = if (request.orientationDegrees == 90 || request.orientationDegrees == 270) scaledH to scaledW else scaledW to scaledH
            val sampleBytes = if (deep) 8 else 4
            val encoded = app.squeeze.jxl.Jxl.encodeJxl(
                width = w, height = h, hasAlpha = true, bitDepth = if (deep) 16 else 8,
                effort = jxl.effort, distance = jxl.distance, modular = jxl.modular,
                xyb = jxl.colorspace == JxlColorspace.XYB && jxl.distance > 0.0,
                pixels = bytes.takeIf { it.size >= w * h * sampleBytes } ?: return ImageEncodeResult.Failed("Decoded pixel buffer too small."),
                exif = request.exifBytes,
            ) ?: return ImageEncodeResult.Failed("libjxl could not encode these pixels.")
            request.output.writeBytes(encoded)
            request.onProgress(1f)
            return ImageEncodeResult.Success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) ImageEncodeResult.Cancelled
            else ImageEncodeResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            raw.delete()
        }
    }

    private fun limitShortSide(bitmap: Bitmap, limit: Int?): Bitmap {
        if (limit == null) return bitmap
        val short = minOf(bitmap.width, bitmap.height)
        if (short <= limit) return bitmap
        val scale = limit.toDouble() / short
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
