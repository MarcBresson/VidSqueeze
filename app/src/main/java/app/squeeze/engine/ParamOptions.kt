package app.squeeze.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Quality wording shown next to the CRF slider. */
enum class QualityHint { NEAR_LOSSLESS, HIGH, BALANCED, SMALL, VERY_SMALL }

/** What the compression sheet may offer for a given set of parameters, and why something is disabled. */
object ParamOptions {
    val RESOLUTIONS = listOf(2160, 1440, 1080, 720, 480)
    val FPS_CHOICES = listOf(60, 30, 24)
    val AUDIO_BITRATES = listOf(64, 96, 128, 192, 256)

    /**
     * A CRF that gives roughly the same visual quality in every codec (x264 23 ≈ x265 28 ≈ VP9 31 ≈
     * AV1 30), so switching codec keeps the quality instead of the number.
     */
    private val referenceCrf = mapOf(
        VideoCodec.H264 to 23,
        VideoCodec.H265 to 28,
        VideoCodec.VP9 to 31,
        VideoCodec.AV1 to 30,
    )

    fun crfRange(codec: VideoCodec): IntRange = when (codec) {
        VideoCodec.VP9, VideoCodec.AV1 -> 0..63
        else -> 0..51
    }

    fun equivalentCrf(from: VideoCodec, to: VideoCodec, crf: Int): Int {
        val a = referenceCrf[from] ?: return crf.coerceIn(crfRange(to))
        val b = referenceCrf[to] ?: return crf.coerceIn(crfRange(to))
        return (crf + b - a).coerceIn(crfRange(to))
    }

    fun qualityHint(codec: VideoCodec, crf: Int): QualityHint {
        val ref = referenceCrf[codec] ?: return QualityHint.BALANCED
        return when {
            crf < ref - 6 -> QualityHint.NEAR_LOSSLESS
            crf < ref -> QualityHint.HIGH
            crf <= ref + 3 -> QualityHint.BALANCED
            crf <= ref + 9 -> QualityHint.SMALL
            else -> QualityHint.VERY_SMALL
        }
    }

    /**
     * Why [container] cannot be chosen with [params], or null when it can. [Container.ORIGINAL] is
     * always selectable: it falls back with a visible notice.
     */
    fun containerDisabledReason(container: Container, params: FfmpegParams): String? {
        if (container == Container.ORIGINAL) return null
        val name = container.extension.uppercase()
        if (params.videoCodec != VideoCodec.COPY && params.videoCodec !in CodecContainerMatrix.videoCodecsFor(container)) {
            return "$name cannot hold ${params.videoCodec.label()} video"
        }
        if (params.audioCodec != AudioCodec.COPY && params.audioCodec !in CodecContainerMatrix.audioCodecsFor(container)) {
            return "$name cannot hold ${params.audioCodec.label()} audio"
        }
        return null
    }

    /** Copying has no rate control at all. */
    fun usesRateControl(params: FfmpegParams) = params.videoCodec != VideoCodec.COPY

    fun isHardware(params: FfmpegParams) = params.encoderKind == EncoderKind.HARDWARE_MEDIACODEC

    /**
     * Whether [mode] works with the selected encoder: software encoders only have CRF, hardware
     * encoders have no CRF and only some have a constant-quality mode.
     */
    fun isRateModeAvailable(params: FfmpegParams, mode: RateMode, hardware: HardwareEncoders): Boolean = when {
        !isHardware(params) -> mode == RateMode.CRF
        mode == RateMode.CRF -> false
        mode == RateMode.QUALITY -> hardware[params.videoCodec]?.supportsCq == true
        else -> true
    }

    fun VideoCodec.label(): String = when (this) {
        VideoCodec.COPY -> "Copy"
        VideoCodec.H264 -> "H.264"
        VideoCodec.H265 -> "H.265"
        VideoCodec.VP9 -> "VP9"
        VideoCodec.AV1 -> "AV1"
    }

    fun AudioCodec.label(): String = when (this) {
        AudioCodec.COPY -> "Copy"
        AudioCodec.AAC -> "AAC"
        AudioCodec.OPUS -> "Opus"
        AudioCodec.MP3 -> "MP3"
    }

    /** Switches codec, keeping CRF-equivalent quality and dropping a container the new codec cannot use. */
    fun withVideoCodec(params: FfmpegParams, codec: VideoCodec): FfmpegParams {
        var rate = params.rateControl
        if (rate is RateControl.Crf && params.videoCodec != VideoCodec.COPY && codec != VideoCodec.COPY) {
            rate = RateControl.Crf(equivalentCrf(params.videoCodec, codec, rate.value))
        } else if (rate is RateControl.Crf && codec != VideoCodec.COPY) {
            rate = RateControl.Crf(referenceCrf.getValue(codec) + 3)
        }
        val next = params.copy(videoCodec = codec, rateControl = rate)
        val container = if (containerDisabledReason(next.container, next) != null) Container.ORIGINAL else next.container
        val audio = if (container != Container.ORIGINAL && params.audioCodec != AudioCodec.COPY &&
            params.audioCodec !in CodecContainerMatrix.audioCodecsFor(container)
        ) CodecContainerMatrix.audioCodecsFor(container).first() else next.audioCodec
        return next.copy(container = container, audioCodec = audio)
    }

    /**
     * Picks a codec together with the encoder that runs it. Quality carries over where it can
     * (CRF between software codecs); a hardware encoder has no CRF, so it starts from its own
     * constant quality when the device has one and from a bitrate otherwise.
     */
    fun withEncoder(params: FfmpegParams, codec: VideoCodec, kind: EncoderKind, hardware: HardwareEncoders): FfmpegParams {
        val base = withVideoCodec(params, codec).copy(encoderKind = kind)
        val rate = base.rateControl
        val next: RateControl = when {
            codec == VideoCodec.COPY -> rate
            kind == EncoderKind.SOFTWARE ->
                rate as? RateControl.Crf ?: RateControl.Crf(referenceCrf.getValue(codec) + 3)
            else -> {
                val device = hardware[codec]
                when {
                    rate is RateControl.ConstantQuality && device?.supportsCq == true -> RateControl.ConstantQuality(clampQuality(rate.value, device))
                    rate is RateControl.Crf || rate is RateControl.ConstantQuality ->
                        if (device?.supportsCq == true) RateControl.ConstantQuality(seedQuality(device)) else RateControl.BitrateKbps(4_000)
                    else -> rate
                }
            }
        }
        return base.copy(rateControl = next)
    }

    /** Roughly "good quality" on the device's own scale, whatever its range is. */
    fun seedQuality(device: HardwareEncoder): Int {
        val range = device.qualityRange ?: return 1
        return (range.first + (range.last - range.first) * 0.6).roundToInt().coerceAtLeast(1)
    }

    private fun clampQuality(value: Int, device: HardwareEncoder): Int {
        val range = device.qualityRange ?: return value
        return value.coerceIn(range.first.coerceAtLeast(1), range.last)
    }

    /** Selecting another mode seeds a sensible value; switching back restores a CRF. */
    fun withRateMode(params: FfmpegParams, mode: RateMode, hardware: HardwareEncoders = HardwareEncoders()): FfmpegParams {
        val current = params.rateControl
        val next: RateControl = when (mode) {
            RateMode.CRF -> current as? RateControl.Crf
                ?: RateControl.Crf(referenceCrf[params.videoCodec]?.plus(3) ?: 26)
            RateMode.QUALITY -> current as? RateControl.ConstantQuality
                ?: RateControl.ConstantQuality(hardware[params.videoCodec]?.let(::seedQuality) ?: 1)
            RateMode.BITRATE -> current as? RateControl.BitrateKbps ?: RateControl.BitrateKbps(4_000)
            RateMode.PERCENT -> current as? RateControl.PercentOfSource ?: RateControl.PercentOfSource(40)
        }
        return params.copy(rateControl = next)
    }

    fun rateMode(rate: RateControl): RateMode = when (rate) {
        is RateControl.Crf -> RateMode.CRF
        is RateControl.ConstantQuality -> RateMode.QUALITY
        is RateControl.BitrateKbps -> RateMode.BITRATE
        is RateControl.PercentOfSource -> RateMode.PERCENT
    }

    fun clampPercent(v: Int) = max(5, min(100, v))
}

enum class RateMode { CRF, QUALITY, BITRATE, PERCENT }
