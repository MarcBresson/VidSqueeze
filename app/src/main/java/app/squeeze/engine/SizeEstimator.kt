package app.squeeze.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Rough output size for the "≈ X GB → ≈ Y GB" footer. It models bits per pixel for each codec at a
 * given CRF, so it is only good to tens of percent (content decides the rest). Always shown with "≈".
 */
object SizeEstimator {

    /** Bits per pixel at [refCrf] for typical phone footage; every 6 CRF steps halves the bitrate. */
    private data class Curve(val bppAtRef: Double, val refCrf: Int)

    private val curves = mapOf(
        VideoCodec.H264 to Curve(0.070, 23),
        VideoCodec.H265 to Curve(0.045, 28),
        VideoCodec.VP9 to Curve(0.050, 31),
        VideoCodec.AV1 to Curve(0.040, 30),
    )

    private const val ASSUMED_AUDIO_KBPS = 128
    private const val CONTAINER_OVERHEAD = 1.01

    /** @return null when the source is too poorly known (no duration or size) to say anything. */
    fun estimateBytes(params: FfmpegParams, source: SourceInfo, sourceBytes: Long): Long? {
        if (source.durationMs <= 0 || sourceBytes <= 0) return null
        if (params.videoCodec == VideoCodec.COPY && params.audioCodec == AudioCodec.COPY) return sourceBytes
        val seconds = source.durationMs / 1000.0

        val sourceAudioBps = if (source.hasAudio) ASSUMED_AUDIO_KBPS * 1000.0 else 0.0
        val sourceVideoBps = source.videoBitrateKbps?.let { it * 1000.0 }
            ?: max(0.0, sourceBytes * 8 / seconds - sourceAudioBps)

        val videoBps = if (params.videoCodec == VideoCodec.COPY) sourceVideoBps else encodedVideoBps(params, source, sourceVideoBps)
        val audioBps = when {
            !source.hasAudio -> 0.0
            params.audioCodec == AudioCodec.COPY -> sourceAudioBps
            else -> params.audioBitrateK * 1000.0
        }
        return ((videoBps + audioBps) * seconds / 8 * CONTAINER_OVERHEAD).toLong()
    }

    private fun encodedVideoBps(params: FfmpegParams, source: SourceInfo, sourceVideoBps: Double): Double {
        val rate = params.rateControl
        if (rate is RateControl.BitrateKbps) return rate.kbps * 1000.0
        if (rate is RateControl.PercentOfSource) return sourceVideoBps * rate.percent / 100.0

        val crf = when (rate) {
            is RateControl.Crf -> rate.value
            is RateControl.ConstantQuality -> rate.value
            else -> error("unreachable")
        }
        val curve = curves.getValue(params.videoCodec)
        val bpp = curve.bppAtRef * 2.0.pow((curve.refCrf - crf) / 6.0)
        val bps = pixelsPerSecond(params, source) * bpp
        // A re-encode that is estimated bigger than its source is far more likely to be a rate
        // model miss than real; the no-gain rule catches the true cases at run time.
        return if (sourceVideoBps > 0) min(bps, sourceVideoBps) else bps
    }

    private fun pixelsPerSecond(params: FfmpegParams, source: SourceInfo): Double {
        var w = source.displayWidth.toDouble()
        var h = source.displayHeight.toDouble()
        val limit = params.maxShortSide
        val short = min(w, h)
        if (limit != null && short > limit) {
            val scale = limit / short
            w *= scale
            h *= scale
        }
        val fps = params.fpsMax?.let { if (source.fps > 0) min(it.toDouble(), source.fps) else it.toDouble() }
            ?: source.fps.takeIf { it > 0 } ?: 30.0
        return w * h * fps
    }
}
