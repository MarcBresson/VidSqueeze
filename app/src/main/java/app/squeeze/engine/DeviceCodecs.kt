package app.squeeze.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

enum class CodecKind { VIDEO, AUDIO }

data class VideoDetails(val maxWidth: Int, val maxHeight: Int, val maxFrameRate: Int?)

data class AudioDetails(val maxChannels: Int, val sampleRateHz: IntRange?)

/** One encoder as the platform reports it for one MIME type. */
data class EncoderEntry(
    val name: String,
    val mime: String,
    val kind: CodecKind,
    val isHardware: Boolean,
    val supportsCq: Boolean,
    val qualityRange: IntRange?,
    val bitrateRangeKbps: IntRange?,
    val video: VideoDetails?,
    val audio: AudioDetails?,
)

/** All encoders of one MIME type, hardware ones first. */
data class CodecGroup(val mime: String, val label: String, val kind: CodecKind, val encoders: List<EncoderEntry>) {
    val hasHardware: Boolean get() = encoders.any { it.isHardware }
}

/** Human names for the MIME types people know, in the order they are listed. */
private val KNOWN_LABELS = linkedMapOf(
    MediaFormat.MIMETYPE_VIDEO_AVC to "H.264",
    MediaFormat.MIMETYPE_VIDEO_HEVC to "H.265",
    MediaFormat.MIMETYPE_VIDEO_VP9 to "VP9",
    MediaFormat.MIMETYPE_VIDEO_AV1 to "AV1",
    MediaFormat.MIMETYPE_VIDEO_VP8 to "VP8",
    MediaFormat.MIMETYPE_VIDEO_MPEG4 to "MPEG-4",
    MediaFormat.MIMETYPE_VIDEO_H263 to "H.263",
    MediaFormat.MIMETYPE_AUDIO_AAC to "AAC",
    MediaFormat.MIMETYPE_AUDIO_OPUS to "Opus",
    MediaFormat.MIMETYPE_AUDIO_FLAC to "FLAC",
    MediaFormat.MIMETYPE_AUDIO_AMR_NB to "AMR-NB",
    MediaFormat.MIMETYPE_AUDIO_AMR_WB to "AMR-WB",
    MediaFormat.MIMETYPE_AUDIO_G711_ALAW to "G.711 A-law",
    MediaFormat.MIMETYPE_AUDIO_G711_MLAW to "G.711 µ-law",
)

/** Groups encoders by MIME type: video before audio, known codecs first, hardware encoders first inside a group. */
fun groupEncoders(entries: List<EncoderEntry>): List<CodecGroup> {
    val known = KNOWN_LABELS.keys.toList()
    return entries
        .groupBy { it.mime }
        .map { (mime, encoders) ->
            CodecGroup(
                mime = mime,
                label = KNOWN_LABELS[mime] ?: mime,
                kind = encoders.first().kind,
                encoders = encoders.sortedWith(compareByDescending<EncoderEntry> { it.isHardware }.thenBy { it.name }),
            )
        }
        .sortedWith(
            compareBy<CodecGroup> { it.kind }
                .thenBy { known.indexOf(it.mime).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.label },
        )
}

/** Lists every video and audio encoder this device has, hardware or not. */
@Singleton
class DeviceCodecProbe @Inject constructor() {

    fun encoders(): List<EncoderEntry> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && !it.isAlias }
            .flatMap { info ->
                info.supportedTypes
                    .filter { it.startsWith("video/") || it.startsWith("audio/") }
                    .mapNotNull { mime -> entry(info, mime) }
            }
    } catch (e: Exception) {
        Log.w(TAG, "Could not list encoders", e)
        emptyList()
    }

    /** One misbehaving codec must not hide the others, so a failure here only drops that entry. */
    private fun entry(info: MediaCodecInfo, mime: String): EncoderEntry? = try {
        val caps = info.getCapabilitiesForType(mime)
        val encoderCaps = caps.encoderCapabilities
        val supportsCq = encoderCaps?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CQ) == true
        val video = caps.videoCapabilities
        val audio = caps.audioCapabilities
        val bitrate = (video?.bitrateRange ?: audio?.bitrateRange)?.let { (it.lower / 1000)..(it.upper / 1000) }
        EncoderEntry(
            name = info.name,
            mime = mime,
            kind = if (mime.startsWith("video/")) CodecKind.VIDEO else CodecKind.AUDIO,
            isHardware = info.isHardwareAccelerated && !info.isSoftwareOnly,
            supportsCq = supportsCq,
            qualityRange = if (supportsCq) encoderCaps?.qualityRange?.let { it.lower..it.upper } else null,
            bitrateRangeKbps = bitrate,
            video = video?.let(::videoDetails),
            audio = audio?.let(::audioDetails),
        )
    } catch (e: Exception) {
        Log.w(TAG, "Could not read capabilities of ${info.name} for $mime", e)
        null
    }

    private fun videoDetails(caps: MediaCodecInfo.VideoCapabilities): VideoDetails {
        val width = caps.supportedWidths.upper
        val height = caps.supportedHeights.upper
        // The largest width and height may not be a supported size together, which makes the exact lookup throw.
        val fps = runCatching { caps.getSupportedFrameRatesFor(width, height).upper }
            .getOrElse { runCatching { caps.supportedFrameRates.upper }.getOrNull() }
        return VideoDetails(width, height, fps?.toInt())
    }

    private fun audioDetails(caps: MediaCodecInfo.AudioCapabilities): AudioDetails {
        val rates = caps.supportedSampleRates
        val range = if (rates != null && rates.isNotEmpty()) {
            rates.min()..rates.max()
        } else {
            caps.supportedSampleRateRanges.takeIf { it.isNotEmpty() }
                ?.let { ranges -> ranges.minOf { it.lower }..ranges.maxOf { it.upper } }
        }
        return AudioDetails(caps.maxInputChannelCount, range)
    }

    private companion object {
        const val TAG = "DeviceCodecProbe"
    }
}
