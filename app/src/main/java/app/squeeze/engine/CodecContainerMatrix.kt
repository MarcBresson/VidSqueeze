package app.squeeze.engine

import app.squeeze.engine.PixelFormat.Chroma

/** Which codec / container / pixel-format combinations are valid, with fallbacks. */
object CodecContainerMatrix {

    private val videoByContainer = mapOf(
        Container.MP4 to setOf(VideoCodec.H264, VideoCodec.H265, VideoCodec.VP9, VideoCodec.AV1),
        Container.MKV to setOf(VideoCodec.H264, VideoCodec.H265, VideoCodec.VP9, VideoCodec.AV1),
        Container.WEBM to setOf(VideoCodec.VP9, VideoCodec.AV1),
        Container.MOV to setOf(VideoCodec.H264, VideoCodec.H265),
        Container.AVI to setOf(VideoCodec.H264),
    )

    private val audioByContainer = mapOf(
        Container.MP4 to setOf(AudioCodec.AAC, AudioCodec.MP3, AudioCodec.OPUS),
        Container.MKV to setOf(AudioCodec.AAC, AudioCodec.MP3, AudioCodec.OPUS),
        Container.WEBM to setOf(AudioCodec.OPUS),
        Container.MOV to setOf(AudioCodec.AAC, AudioCodec.MP3),
        Container.AVI to setOf(AudioCodec.AAC, AudioCodec.MP3),
    )

    /** ffprobe audio codec names that can be stream-copied into each container. */
    private val copyableAudioNames = mapOf(
        Container.MP4 to setOf("aac", "mp3", "opus", "ac3", "eac3", "alac"),
        Container.MKV to setOf("aac", "mp3", "opus", "vorbis", "ac3", "eac3", "flac", "alac", "pcm_s16le"),
        Container.WEBM to setOf("opus", "vorbis"),
        Container.MOV to setOf("aac", "mp3", "ac3", "alac", "pcm_s16le"),
        Container.AVI to setOf("aac", "mp3", "ac3", "pcm_s16le"),
    )

    /** Containers that can carry the codec; for [VideoCodec.COPY] every container (checked per source). */
    fun containersFor(codec: VideoCodec): Set<Container> =
        if (codec == VideoCodec.COPY) videoByContainer.keys
        else videoByContainer.filterValues { codec in it }.keys

    fun videoCodecsFor(container: Container): Set<VideoCodec> = videoByContainer[container].orEmpty()

    fun audioCodecsFor(container: Container): Set<AudioCodec> = audioByContainer[container].orEmpty()

    /** Codecs (other than copy) able to carry HDR in a given container. */
    fun carriesHdr(codec: VideoCodec, container: Container, encoderKind: EncoderKind): Boolean =
        codec in setOf(VideoCodec.H265, VideoCodec.AV1, VideoCodec.VP9) &&
            container != Container.AVI &&
            // MediaCodec 10-bit output is device dependent, so it is never assumed to carry HDR.
            encoderKind == EncoderKind.SOFTWARE

    /** The effective video codec for compatibility checks: for COPY, what the source actually holds. */
    private fun effectiveVideo(params: FfmpegParams, source: SourceInfo): VideoCodec? =
        if (params.videoCodec == VideoCodec.COPY) VideoCodec.fromProbeName(source.videoCodec)
        else params.videoCodec

    private fun videoFits(container: Container, params: FfmpegParams, source: SourceInfo): Boolean {
        val codec = effectiveVideo(params, source)
        // Unknown copied codecs (mpeg4, vp8, ...) are only guaranteed to fit Matroska.
        return if (codec == null) container == Container.MKV else codec in videoCodecsFor(container)
    }

    private fun audioFits(container: Container, params: FfmpegParams, source: SourceInfo): Boolean = when {
        params.audioCodec == AudioCodec.COPY ->
            !source.hasAudio || source.audioCodec?.lowercase() in copyableAudioNames[container].orEmpty()
        else -> params.audioCodec in audioCodecsFor(container)
    }

    fun fits(container: Container, params: FfmpegParams, source: SourceInfo): Boolean =
        videoFits(container, params, source) && audioFits(container, params, source)

    data class Resolution(val container: Container?, val issues: List<Issue>)

    /**
     * Picks the output container. An explicit choice that cannot carry the streams is an error;
     * [Container.ORIGINAL] falls back to the source container, then MP4, then MKV, with a notice.
     */
    fun resolveContainer(params: FfmpegParams, source: SourceInfo): Resolution {
        if (params.container != Container.ORIGINAL) {
            val c = params.container
            val issues = buildList {
                if (!videoFits(c, params, source)) {
                    add(error("container_video", "${c.extension.uppercase()} cannot hold ${videoLabel(params, source)} video."))
                }
                if (!audioFits(c, params, source)) {
                    add(error("container_audio", "${c.extension.uppercase()} cannot hold ${params.audioCodec} audio."))
                }
            }
            return Resolution(if (issues.hasErrors) null else c, issues)
        }

        val original = source.container
        if (original != null && fits(original, params, source)) return Resolution(original, emptyList())

        val fallback = listOf(Container.MP4, Container.MKV).firstOrNull { fits(it, params, source) }
            ?: return Resolution(
                null,
                listOf(error("container_none", "No container can hold ${videoLabel(params, source)} video with ${params.audioCodec} audio.")),
            )
        val notice = if (original == null) emptyList() else listOf(
            Issue(
                Severity.INFO,
                "container_fallback",
                "The original container (${original.extension.uppercase()}) cannot hold this output, using ${fallback.extension.uppercase()} instead.",
            ),
        )
        return Resolution(fallback, notice)
    }

    /** Pixel-format compatibility for a *resolved* (non-AUTO) format. */
    fun pixelFormatIssues(
        pix: PixelFormat,
        codec: VideoCodec,
        encoderKind: EncoderKind,
        container: Container,
    ): List<Issue> = buildList {
        if (codec == VideoCodec.COPY || pix.ffName == null) return@buildList
        val tenBit = pix.bitDepth == 10
        val non420 = pix.chroma != Chroma.C420

        if (encoderKind == EncoderKind.HARDWARE_MEDIACODEC) {
            if (non420) add(error("pix_hw_chroma", "Hardware encoders only accept 4:2:0 video."))
            else if (tenBit) add(Issue(Severity.WARNING, "pix_hw_10bit", "10-bit hardware encoding only works on some devices."))
            return@buildList
        }
        if (non420 && container == Container.WEBM) {
            add(error("pix_webm_chroma", "WebM only supports 4:2:0 video."))
        }
        if (codec == VideoCodec.H264 && tenBit) {
            add(Issue(Severity.WARNING, "pix_poor_playback", "10-bit H.264 has poor phone and TV playback support."))
        }
        if (non420 && (codec == VideoCodec.H264 || codec == VideoCodec.H265)) {
            add(Issue(Severity.WARNING, "pix_poor_playback", "4:2:2 / 4:4:4 video has poor phone and TV playback support."))
        }
    }

    private fun videoLabel(params: FfmpegParams, source: SourceInfo): String =
        if (params.videoCodec == VideoCodec.COPY) source.videoCodec ?: "the source" else params.videoCodec.name

    internal fun error(code: String, message: String) = Issue(Severity.ERROR, code, message)
}
