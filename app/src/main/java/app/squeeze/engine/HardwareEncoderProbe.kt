package app.squeeze.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/** Asks the platform which hardware video encoders this device has (once, then cached). */
@Singleton
class HardwareEncoderProbe @Inject constructor() {

    val encoders: HardwareEncoders by lazy { probe() }

    private fun probe(): HardwareEncoders = try {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { info -> MIME_TO_CODEC.filterKeys { it in info.supportedTypes }.map { (mime, codec) -> candidate(info, mime, codec) } }
        HardwareEncoders.select(candidates)
    } catch (e: Exception) {
        // A broken codec list must not take the compression sheet down; software encoding still works.
        Log.w(TAG, "Could not list hardware encoders", e)
        HardwareEncoders()
    }

    private fun candidate(info: MediaCodecInfo, mime: String, codec: VideoCodec): HardwareEncoders.Candidate {
        val caps = info.getCapabilitiesForType(mime)
        val encoderCaps = caps.encoderCapabilities
        val supportsCq = encoderCaps?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CQ) == true
        val quality = if (supportsCq) encoderCaps?.qualityRange?.let { it.lower..it.upper } else null
        val bitrate = caps.videoCapabilities?.bitrateRange?.let { (it.lower / 1000)..(it.upper / 1000) }
        return HardwareEncoders.Candidate(
            codec = codec,
            isHardware = info.isHardwareAccelerated && !info.isSoftwareOnly,
            encoder = HardwareEncoder(codec, info.name, supportsCq, quality, bitrate),
        )
    }

    private companion object {
        const val TAG = "HardwareEncoderProbe"
        val MIME_TO_CODEC = mapOf(
            MediaFormat.MIMETYPE_VIDEO_AVC to VideoCodec.H264,
            MediaFormat.MIMETYPE_VIDEO_HEVC to VideoCodec.H265,
        )
    }
}
