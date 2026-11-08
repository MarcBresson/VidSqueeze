package app.squeeze.engine

/**
 * Builds the ffmpeg argument list for still-image formats FFmpeg encodes itself (JPEG, PNG, AVIF).
 * WebP and JPEG XL go through the platform/libjxl encoders instead and get a display-only
 * pseudo-command from their engines. Pure Kotlin: `input` and `output` are opaque strings.
 */
object ImageCommandBuilder {

    fun build(
        params: ImageParams,
        source: SourceInfo,
        orientationDegrees: Int,
        input: String,
        output: String,
    ): ImageBuildResult {
        val issues = mutableListOf<Issue>()
        val args = mutableListOf("-y", "-i", input, "-map", "0:v:0", "-frames:v", "1", "-dn", "-map_metadata", "-1")

        when (params.format) {
            ImageFormat.JPEG -> {
                args += listOf("-c:v", "mjpeg", "-q:v", jpegQscale(params.quality).toString())
                args += listOf("-pix_fmt", if (params.jpegChroma444) "yuvj444p" else "yuvj420p")
            }
            ImageFormat.PNG -> {
                args += listOf("-c:v", "png", "-compression_level", params.pngCompression.coerceIn(0, 9).toString())
                args += listOf("-pix_fmt", if (params.pngBitDepth == 16) "rgb48be" else "rgb24")
                if (params.pngBitDepth > 8 && source.pixFmt != null && !source.pixFmt.orEmpty().contains("10")) {
                    issues += Issue(Severity.WARNING, "png_upconvert", "The source is 8-bit; a 16-bit PNG adds size without quality.")
                }
            }
            ImageFormat.AVIF -> {
                args += listOf(
                    "-c:v", "libaom-av1", "-still-picture", "1",
                    "-crf", params.avifCrf.coerceIn(0, 63).toString(),
                    "-cpu-used", params.avifEffort.coerceIn(0, 8).toString(),
                    "-b:v", "0",
                )
            }
            ImageFormat.WEBP, ImageFormat.JPEG_XL ->
                return ImageBuildInvalid(listOf(Issue(Severity.ERROR, "image_engine", "${params.format} is encoded outside FFmpeg.")))
        }

        if (source.isHdr) {
            issues += Issue(Severity.WARNING, "image_hdr", "HDR photo; the output is clipped to SDR.")
        }

        val filters = imageFilters(params, source, orientationDegrees)
        if (filters.isNotEmpty()) args += listOf("-vf", filters.joinToString(","))
        args += output
        return ImageBuildOk(args, params.format, issues)
    }

    /**
     * FFmpeg's mjpeg encoder takes an inverse `qscale` of 1 (best) to 31 (worst).
     * A user quality of 100 ≈ 1, 0 ≈ 31; 85 lands at ~5, the usual "high quality JPEG".
     */
    fun jpegQscale(quality: Int): Int = 31 - (quality.coerceIn(1, 100) * 30 / 100)

    private fun imageFilters(params: ImageParams, source: SourceInfo, orientationDegrees: Int): List<String> {
        val filters = mutableListOf<String>()
        when (orientationDegrees) {
            90 -> filters += "transpose=1"
            180 -> filters += "transpose=1,transpose=1"
            270 -> filters += "transpose=2"
        }
        val limit = params.maxShortSide
        if (limit != null && source.dimensionsKnown && minOf(source.displayWidth, source.displayHeight) > limit) {
            val scale = limit.toDouble() / minOf(source.displayWidth, source.displayHeight)
            val w = kotlin.math.round(source.displayWidth * scale).toInt().coerceAtLeast(1)
            val h = kotlin.math.round(source.displayHeight * scale).toInt().coerceAtLeast(1)
            // Never upscale: this filter only exists when the source exceeds the limit.
            filters += "scale=${w}x${h}"
            filters += "setsar=1"
        }
        return filters
    }
}

/** Result of [ImageCommandBuilder.build], mirroring the video side's [BuildResult]. */
sealed interface ImageBuildResult

data class ImageBuildOk(val args: List<String>, val format: ImageFormat, val issues: List<Issue>) : ImageBuildResult {
    fun display(program: String = "ffmpeg"): String = CommandFormatter.format(args, program)
}

data class ImageBuildInvalid(val issues: List<Issue>) : ImageBuildResult
