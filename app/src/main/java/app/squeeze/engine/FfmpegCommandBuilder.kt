package app.squeeze.engine

import app.squeeze.engine.CodecContainerMatrix.error

data class BuildOptions(
    /** `-threads` value, null = ffmpeg default. */
    val threads: Int? = null,
    /** Whether the bundled ffmpeg ships libzimg (`zscale`), needed for HDR tone mapping. */
    val hasZscale: Boolean = true,
    /**
     * Hardware encoders the device reports. `null` skips the availability checks (and `-codec_name`),
     * for callers and tests that do not probe.
     */
    val hardware: HardwareEncoders? = null,
)

sealed interface BuildResult {
    /** [container]'s extension must match the extension of the output path that was passed in. */
    data class Ok(val args: List<String>, val container: Container, val issues: List<Issue>) : BuildResult {
        fun display(program: String = "ffmpeg"): String = CommandFormatter.format(args, program)
    }

    data class Invalid(val issues: List<Issue>) : BuildResult
}

/**
 * Turns [FfmpegParams] + probed [SourceInfo] into an ffmpeg argument list. Pure Kotlin: `input`
 * and `output` are opaque strings (SAF parameters, paths, ...), and nothing here touches Android.
 */
object FfmpegCommandBuilder {

    /**
     * HDR to SDR tone mapping. The first zscale pins the input transfer, matrix and primaries instead of
     * trusting the frame tags: frames that are untagged (Dolby Vision) or tagged as an RGB matrix on YUV data
     * make zimg fail with "no path between colorspaces" / "YUV color family cannot have RGB matrix coefficients".
     */
    internal fun toneMapChain(source: SourceInfo): String {
        val transfer = source.colorTransfer?.takeIf { it in HDR_TRANSFERS } ?: SourceInfo.TRANSFER_PQ
        val matrix = source.colorSpace?.takeIf { it in YUV_MATRICES } ?: "bt2020nc"
        val primaries = source.colorPrimaries?.takeIf { it in WIDE_PRIMARIES } ?: "bt2020"
        return "zscale=tin=$transfer:min=$matrix:pin=$primaries:rin=tv:t=linear:npl=100:p=$primaries," +
            "format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv"
    }

    private val HDR_TRANSFERS = setOf(SourceInfo.TRANSFER_PQ, SourceInfo.TRANSFER_HLG)
    private val YUV_MATRICES = setOf("bt2020nc", "bt709")
    private val WIDE_PRIMARIES = setOf("bt2020", "bt709", "smpte432")

    fun build(
        params: FfmpegParams,
        source: SourceInfo,
        input: String,
        output: String,
        export: ExportOptions = ExportOptions(),
        options: BuildOptions = BuildOptions(),
    ): BuildResult {
        val issues = mutableListOf<Issue>()

        val custom = try {
            CustomArgs.tokenize(params.customArgs)
        } catch (e: ArgSyntaxException) {
            return BuildResult.Invalid(listOf(error("custom_syntax", e.message.orEmpty())))
        }
        issues += CustomArgs.validate(custom)

        val resolution = CodecContainerMatrix.resolveContainer(params, source)
        issues += resolution.issues
        val container = resolution.container
        if (container == null || issues.hasErrors) return BuildResult.Invalid(issues)

        if (params.customArgsReplaceGenerated) {
            val args = listOf("-y", "-i", input) + custom + output
            return BuildResult.Ok(args, container, issues)
        }

        val plan = plan(params, source, container, options, issues)
        if (plan == null || issues.hasErrors) return BuildResult.Invalid(issues)
        val args = mutableListOf("-y")
        options.threads?.let { args += listOf("-threads", it.toString()) }
        args += listOf("-i", input, "-map", "0:v:0")
        if (source.hasAudio) args += listOf("-map", "0:a?")
        args += "-dn"

        args += videoArgs(params, source, container, plan, options, issues)
        if (issues.hasErrors) return BuildResult.Invalid(issues)
        args += audioArgs(params, source)
        args += metadataArgs(export)
        if (container == Container.MP4 || container == Container.MOV) args += listOf("-movflags", "+faststart")
        args += custom
        args += output
        return BuildResult.Ok(args, container, issues)
    }

    /** Dynamic-range and pixel-format decisions, resolved once so the arg builders agree. */
    private data class Plan(val copyVideo: Boolean, val toneMap: Boolean, val keepHdr: Boolean, val pixFmt: PixelFormat)

    private fun plan(
        params: FfmpegParams,
        source: SourceInfo,
        container: Container,
        options: BuildOptions,
        issues: MutableList<Issue>,
    ): Plan? {
        val codec = params.videoCodec
        if (codec == VideoCodec.COPY) {
            if (params.fpsMax != null || params.maxShortSide != null) {
                issues += Issue(Severity.WARNING, "copy_ignores_filters", "Frame rate and resolution limits are ignored when the video is copied.")
            }
            if (params.dynamicRange == DynamicRange.FORCE_SDR && source.isHdr) {
                issues += Issue(Severity.WARNING, "copy_keeps_hdr", "The video is copied, so HDR is kept.")
            }
            return Plan(copyVideo = true, toneMap = false, keepHdr = source.isHdr, pixFmt = PixelFormat.KEEP_SOURCE)
        }

        if (params.encoderKind == EncoderKind.HARDWARE_MEDIACODEC && codec != VideoCodec.H264 && codec != VideoCodec.H265) {
            issues += error("hw_codec", "Hardware encoding supports only H.264 and H.265.")
            return null
        }
        if (params.encoderKind == EncoderKind.HARDWARE_MEDIACODEC && options.hardware != null && options.hardware[codec] == null) {
            issues += error("hw_unavailable", "This device has no hardware ${codec.name} encoder. Pick a software encoder.")
            return null
        }

        val keepHdr: Boolean
        val toneMap: Boolean
        when {
            !source.isHdr -> { keepHdr = false; toneMap = false }
            params.dynamicRange == DynamicRange.KEEP -> { keepHdr = true; toneMap = false }
            params.dynamicRange == DynamicRange.FORCE_SDR -> { keepHdr = false; toneMap = true }
            CodecContainerMatrix.carriesHdr(codec, container, params.encoderKind) -> { keepHdr = true; toneMap = false }
            else -> { keepHdr = false; toneMap = true }
        }

        if (keepHdr && !CodecContainerMatrix.carriesHdr(codec, container, params.encoderKind)) {
            issues += error("hdr_unsupported", "Keeping HDR needs H.265, AV1 or VP9 software encoding. Switch codec or remove HDR.")
        }
        if (toneMap && !options.hasZscale) {
            issues += error("no_zscale", "HDR to SDR conversion is unavailable: this ffmpeg build lacks the zscale filter.")
        }
        if (source.isHdr && source.dolbyVisionProfile == 5) {
            issues += Issue(Severity.WARNING, "dv_profile5", "Dolby Vision profile 5 has no HDR10 base layer, colours will look wrong.")
        }

        val pix = when {
            params.pixelFormat == PixelFormat.AUTO -> if (keepHdr) PixelFormat.YUV420P10LE else PixelFormat.YUV420P
            params.pixelFormat == PixelFormat.KEEP_SOURCE && toneMap -> PixelFormat.YUV420P
            else -> params.pixelFormat
        }
        if (keepHdr && pix.ffName != null && pix.bitDepth < 10) {
            issues += error("hdr_needs_10bit", "Keeping HDR needs a 10-bit pixel format.")
        }
        issues += CodecContainerMatrix.pixelFormatIssues(pix, codec, params.encoderKind, container)
        return Plan(copyVideo = false, toneMap = toneMap, keepHdr = keepHdr, pixFmt = pix)
    }

    private fun videoArgs(
        params: FfmpegParams,
        source: SourceInfo,
        container: Container,
        plan: Plan,
        options: BuildOptions,
        issues: MutableList<Issue>,
    ): List<String> {
        if (plan.copyVideo) return listOf("-c:v", "copy")

        val args = mutableListOf<String>()
        val codec = params.videoCodec
        val hardware = params.encoderKind == EncoderKind.HARDWARE_MEDIACODEC
        val rc = params.rateControl

        if (hardware) {
            val device = options.hardware?.get(codec)
            args += listOf("-c:v", if (codec == VideoCodec.H264) "h264_mediacodec" else "hevc_mediacodec")
            // Without this Android picks the mime type's default encoder, which may be a software one.
            device?.let { args += listOf("-codec_name", it.name) }
            when (rc) {
                is RateControl.Crf -> issues += error("rc_hw_crf", "Hardware encoders have no CRF mode, choose a bitrate or constant quality.")
                is RateControl.BitrateKbps -> args += hardwareVbr(rc.kbps, device, issues)
                is RateControl.PercentOfSource -> {
                    val sourceKbps = source.videoBitrateKbps
                    if (sourceKbps == null) {
                        issues += error("rc_unknown_bitrate", "The source bitrate is unknown, use an absolute bitrate.")
                    } else {
                        args += hardwareVbr((sourceKbps * rc.percent / 100).coerceAtLeast(100), device, issues)
                    }
                }
                is RateControl.ConstantQuality -> {
                    val range = device?.qualityRange
                    when {
                        options.hardware != null && device?.supportsCq != true ->
                            issues += error("rc_hw_cq", "This device's ${codec.name} hardware encoder has no constant-quality mode, choose a bitrate.")
                        rc.value <= 0 || (range != null && rc.value !in range) ->
                            issues += error("cq_range", "Quality must be between ${range?.first?.coerceAtLeast(1) ?: 1} and ${range?.last ?: "the encoder maximum"} for this device.")
                        else -> args += listOf("-bitrate_mode", "cq", "-global_quality", rc.value.toString())
                    }
                }
            }
        } else {
            val crf = (rc as? RateControl.Crf)?.value
            if (crf == null) {
                issues += error("rc_sw_crf", "Software encoders use CRF; bitrate and constant-quality modes are for hardware encoding.")
                return args
            }
            val max = if (codec == VideoCodec.H264 || codec == VideoCodec.H265) 51 else 63
            if (crf !in 0..max) {
                issues += error("crf_range", "CRF must be between 0 and $max for ${codec.name}.")
                return args
            }
            when (codec) {
                VideoCodec.H264 -> args += listOf("-c:v", "libx264", "-crf", "$crf", "-preset", params.encoderPreset.ffName)
                VideoCodec.H265 -> args += listOf("-c:v", "libx265", "-crf", "$crf", "-preset", params.encoderPreset.ffName)
                VideoCodec.VP9 -> args += listOf(
                    "-c:v", "libvpx-vp9", "-crf", "$crf", "-b:v", "0", "-row-mt", "1",
                    "-cpu-used", vp9CpuUsed(params.encoderPreset).toString(),
                )
                VideoCodec.AV1 -> args += listOf(
                    "-c:v", "libaom-av1", "-crf", "$crf", "-b:v", "0", "-row-mt", "1",
                    "-cpu-used", aomCpuUsed(params.encoderPreset).toString(),
                )
                VideoCodec.COPY -> Unit
            }
        }

        if ((codec == VideoCodec.H265) && (container == Container.MP4 || container == Container.MOV)) {
            args += listOf("-tag:v", "hvc1")
        }

        val filters = videoFilters(params, source, plan)
        if (filters.isNotEmpty()) args += listOf("-vf", filters.joinToString(","))

        if (plan.toneMap) {
            args += listOf("-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709")
        } else if (plan.keepHdr) {
            source.colorPrimaries?.let { args += listOf("-color_primaries", it) }
            source.colorTransfer?.let { args += listOf("-color_trc", it) }
            source.colorSpace?.let { args += listOf("-colorspace", it) }
        }
        return args
    }

    /** Hardware encoders treat the bitrate as a target, so it is only checked against what the device accepts. */
    private fun hardwareVbr(kbps: Int, device: HardwareEncoder?, issues: MutableList<Issue>): List<String> {
        val range = device?.bitrateRangeKbps
        if (range != null && kbps !in range) {
            issues += Issue(Severity.WARNING, "hw_bitrate_range", "The ${range.first}-${range.last} kbit/s range is what this device's encoder accepts; $kbps kbit/s may be clamped.")
        }
        return listOf("-bitrate_mode", "vbr", "-b:v", "${kbps}k")
    }

    private fun videoFilters(params: FfmpegParams, source: SourceInfo, plan: Plan): List<String> {
        val filters = mutableListOf<String>()
        val codec = params.videoCodec
        val subsampled = plan.pixFmt.chroma != PixelFormat.Chroma.C444

        params.fpsMax?.let { target ->
            // Reduce-only: an unknown source fps means we can't tell, so leave it alone.
            if (source.fps > target + 0.01) filters += "fps=$target"
        }

        val evenRequired = codec == VideoCodec.H264 || codec == VideoCodec.H265 || params.encoderKind == EncoderKind.HARDWARE_MEDIACODEC
        val oddOrUnknown = !source.dimensionsKnown || source.width % 2 != 0 || source.height % 2 != 0
        if (evenRequired && subsampled && oddOrUnknown) filters += "crop=trunc(iw/2)*2:trunc(ih/2)*2"

        params.maxShortSide?.let { side ->
            val needsScale = !source.dimensionsKnown || minOf(source.displayWidth, source.displayHeight) > side
            if (needsScale) {
                val auto = if (evenRequired) "-2" else "-1"
                filters += "scale='if(lt(iw,ih),min(iw,$side),$auto)':'if(lt(iw,ih),$auto,min(ih,$side))'"
                filters += "setsar=1"
            }
        }

        if (plan.toneMap) filters += toneMapChain(source)
        plan.pixFmt.ffName?.let { filters += "format=$it" }
        return filters
    }

    private fun audioArgs(params: FfmpegParams, source: SourceInfo): List<String> {
        if (!source.hasAudio) return emptyList()
        val encoder = params.audioCodec.ffEncoder ?: return listOf("-c:a", "copy")
        return listOf("-c:a", encoder, "-b:a", "${params.audioBitrateK}k")
    }

    private fun metadataArgs(export: ExportOptions): List<String> {
        if (!export.keepExif) return listOf("-map_metadata", "-1")
        val args = mutableListOf("-map_metadata", "0")
        if (!export.keepLocation) {
            args += listOf("-metadata", "location=", "-metadata", "location-eng=", "-metadata", "com.apple.quicktime.location.ISO6709=")
        }
        return args
    }

    /** libaom is the only AV1 encoder in the bundled build; it is slow, so even "medium" maps to a fast setting. */
    private fun aomCpuUsed(p: EncoderPreset): Int = when (p) {
        EncoderPreset.ULTRAFAST, EncoderPreset.SUPERFAST, EncoderPreset.VERYFAST -> 8
        EncoderPreset.FASTER, EncoderPreset.FAST -> 7
        EncoderPreset.MEDIUM -> 6
        EncoderPreset.SLOW -> 5
        EncoderPreset.SLOWER -> 4
        EncoderPreset.VERYSLOW -> 3
    }

    private fun vp9CpuUsed(p: EncoderPreset): Int = when (p) {
        EncoderPreset.ULTRAFAST, EncoderPreset.SUPERFAST -> 5
        EncoderPreset.VERYFAST, EncoderPreset.FASTER -> 4
        EncoderPreset.FAST -> 3
        EncoderPreset.MEDIUM -> 2
        EncoderPreset.SLOW -> 1
        EncoderPreset.SLOWER, EncoderPreset.VERYSLOW -> 0
    }
}
