package app.squeeze.engine

/**
 * What the command builder needs to know about the input. Filled in by the ffprobe wrapper
 * (`MediaProbe`); plain data so the builder stays JVM-testable. Zero / null mean "unknown".
 */
data class SourceInfo(
    val container: Container? = null,
    val durationMs: Long = 0,
    /** Coded dimensions as reported by ffprobe (before applying [rotation]). */
    val width: Int = 0,
    val height: Int = 0,
    /** Display-matrix rotation in degrees (0, 90, 180, 270). */
    val rotation: Int = 0,
    val fps: Double = 0.0,
    /** ffprobe `codec_name` of the first video stream. */
    val videoCodec: String? = null,
    val pixFmt: String? = null,
    val colorTransfer: String? = null,
    val colorPrimaries: String? = null,
    val colorSpace: String? = null,
    val dolbyVisionProfile: Int? = null,
    val videoBitrateKbps: Int? = null,
    /** ffprobe `codec_name` of the first audio stream, null when there is no audio. */
    val audioCodec: String? = null,
) {
    val hasAudio: Boolean get() = audioCodec != null

    private val rotated: Boolean get() = rotation % 180 != 0
    val displayWidth: Int get() = if (rotated) height else width
    val displayHeight: Int get() = if (rotated) width else height
    val dimensionsKnown: Boolean get() = width > 0 && height > 0

    val isHdr: Boolean
        get() = colorTransfer in HDR_TRANSFERS || dolbyVisionProfile != null

    companion object {
        const val TRANSFER_PQ = "smpte2084"
        const val TRANSFER_HLG = "arib-std-b67"
        private val HDR_TRANSFERS = setOf(TRANSFER_PQ, TRANSFER_HLG)
    }
}
