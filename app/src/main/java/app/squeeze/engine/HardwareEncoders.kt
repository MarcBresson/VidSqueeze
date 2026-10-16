package app.squeeze.engine

/** What one hardware-accelerated MediaCodec video encoder of this device can do. */
data class HardwareEncoder(
    val codec: VideoCodec,
    /** MediaCodec name (`c2.qti.hevc.encoder`), passed as `-codec_name` so ffmpeg cannot pick a software one. */
    val name: String,
    /** Whether constant-quality (CQ) rate control is supported; most phone encoders only do VBR/CBR. */
    val supportsCq: Boolean,
    /** Device specific scale of `-global_quality` in CQ mode, higher is better. Null when unknown. */
    val qualityRange: IntRange?,
    val bitrateRangeKbps: IntRange?,
)

/** The hardware video encoders a device offers, by codec. Empty on devices without any (or before probing). */
data class HardwareEncoders(val byCodec: Map<VideoCodec, HardwareEncoder> = emptyMap()) {
    operator fun get(codec: VideoCodec): HardwareEncoder? = byCodec[codec]

    /** Codecs the compression sheet may offer as hardware chips, in display order. */
    val codecs: List<VideoCodec> get() = HARDWARE_CODECS.filter { it in byCodec }

    companion object {
        /** The only codecs the bundled ffmpeg has a MediaCodec encoder wrapper for. */
        val HARDWARE_CODECS = listOf(VideoCodec.H264, VideoCodec.H265)

        /**
         * Keeps the best hardware encoder per codec out of what [MediaCodecList][android.media.MediaCodecList]
         * reported. Pure so it can be tested: encoders that are software-only, not hardware accelerated
         * or not for a supported codec are dropped, and an encoder with CQ wins over one without.
         */
        fun select(candidates: List<Candidate>): HardwareEncoders {
            val best = candidates
                .filter { it.isHardware && it.codec in HARDWARE_CODECS }
                .groupBy { it.codec }
                .mapValues { (_, list) -> list.maxByOrNull { if (it.encoder.supportsCq) 1 else 0 }!!.encoder }
            return HardwareEncoders(best)
        }
    }

    /** One encoder as reported by the platform, before filtering. */
    data class Candidate(val codec: VideoCodec, val isHardware: Boolean, val encoder: HardwareEncoder)
}
