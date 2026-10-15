package app.squeeze.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface RateControl {
    /** Software encoders: constant rate factor. */
    @Serializable @SerialName("crf")
    data class Crf(val value: Int) : RateControl

    /** Target average bitrate in kbit/s (the reliable mode for hardware encoders). */
    @Serializable @SerialName("bitrate")
    data class BitrateKbps(val kbps: Int) : RateControl

    /** Target bitrate as a percentage of the source video bitrate. */
    @Serializable @SerialName("bitrate_percent")
    data class PercentOfSource(val percent: Int) : RateControl

    /** Hardware constant-quality mode, only where the device supports it. */
    @Serializable @SerialName("cq")
    data class ConstantQuality(val value: Int) : RateControl
}

@Serializable
enum class EncoderKind { SOFTWARE, HARDWARE_MEDIACODEC }

@Serializable
enum class EncoderPreset(val ffName: String) {
    ULTRAFAST("ultrafast"),
    SUPERFAST("superfast"),
    VERYFAST("veryfast"),
    FASTER("faster"),
    FAST("fast"),
    MEDIUM("medium"),
    SLOW("slow"),
    SLOWER("slower"),
    VERYSLOW("veryslow"),
}

@Serializable
enum class VideoCodec {
    /** Keep the source video stream untouched (no re-encode). */
    COPY,
    H264,
    H265,
    VP9,
    AV1;

    companion object {
        /** Maps an ffprobe `codec_name` to the codec family, or null if we have no encoder for it. */
        fun fromProbeName(name: String?): VideoCodec? = when (name?.lowercase()) {
            "h264" -> H264
            "hevc", "h265" -> H265
            "vp9" -> VP9
            "av1" -> AV1
            else -> null
        }
    }
}

@Serializable
enum class AudioCodec(val ffEncoder: String?, val probeNames: Set<String>) {
    COPY(null, emptySet()),
    AAC("aac", setOf("aac")),
    OPUS("libopus", setOf("opus")),
    MP3("libmp3lame", setOf("mp3")),
}

@Serializable
enum class Container(val extension: String) {
    /** Keep the source container when it can carry the output streams, otherwise fall back. */
    ORIGINAL(""),
    MP4("mp4"),
    MKV("mkv"),
    WEBM("webm"),
    MOV("mov"),
    AVI("avi");

    companion object {
        fun fromExtension(ext: String?): Container? =
            entries.firstOrNull { it != ORIGINAL && it.extension.equals(ext?.trimStart('.'), ignoreCase = true) }
                ?: if (ext.equals("m4v", ignoreCase = true)) MP4 else null
    }
}

@Serializable
enum class PixelFormat(val ffName: String?, val bitDepth: Int, val chroma: Chroma) {
    /** yuv420p for SDR 8-bit output, yuv420p10le when HDR is kept. */
    AUTO(null, 8, Chroma.C420),
    YUV420P("yuv420p", 8, Chroma.C420),
    YUV420P10LE("yuv420p10le", 10, Chroma.C420),
    YUV422P("yuv422p", 8, Chroma.C422),
    YUV422P10LE("yuv422p10le", 10, Chroma.C422),
    YUV444P("yuv444p", 8, Chroma.C444),

    /** Emit no `format=` filter, so the encoder receives whatever the source decodes to. */
    KEEP_SOURCE(null, 8, Chroma.C420);

    enum class Chroma { C420, C422, C444 }
}

@Serializable
enum class DynamicRange {
    /** Preserve HDR (10-bit, colour tags kept). Needs an HDR-capable codec. */
    KEEP,

    /** Always tone-map to Rec.709 SDR. A no-op for sources that are already SDR. */
    FORCE_SDR,

    /** Tone-map to SDR only when the codec/container cannot carry HDR. */
    AUTO_SDR_IF_UNSUPPORTED,
}

@Serializable
data class FfmpegParams(
    /** null = keep. Reduce-only: omitted when the source fps is at or below the target. */
    val fpsMax: Int? = null,
    val rateControl: RateControl = RateControl.Crf(26),
    val encoderKind: EncoderKind = EncoderKind.SOFTWARE,
    val encoderPreset: EncoderPreset = EncoderPreset.MEDIUM,
    val videoCodec: VideoCodec = VideoCodec.H265,
    val pixelFormat: PixelFormat = PixelFormat.AUTO,
    val dynamicRange: DynamicRange = DynamicRange.AUTO_SDR_IF_UNSUPPORTED,
    val audioCodec: AudioCodec = AudioCodec.AAC,
    val audioBitrateK: Int = 128,
    /** Limit applied to the short side, never upscaling. null = keep. */
    val maxShortSide: Int? = null,
    /** Raw extra arguments, tokenised shell-style and appended just before the output path. */
    val customArgs: String = "",
    /** Advanced: use [customArgs] instead of the generated arguments (ffshare-style). */
    val customArgsReplaceGenerated: Boolean = false,
    val container: Container = Container.ORIGINAL,
)

@Serializable
enum class ExportMode { ASK_WHEN_DONE, REPLACE_ORIGINALS, SAVE_COPIES }

@Serializable
data class ExportOptions(
    val mode: ExportMode = ExportMode.ASK_WHEN_DONE,
    val nameTemplate: String = DEFAULT_NAME_TEMPLATE,
    val keepExif: Boolean = true,
    val keepLocation: Boolean = true,
) {
    companion object {
        const val DEFAULT_NAME_TEMPLATE = "{original_file_name} - compressed.{output_format}"
    }
}

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val params: FfmpegParams = FfmpegParams(),
    val export: ExportOptions = ExportOptions(),
)
