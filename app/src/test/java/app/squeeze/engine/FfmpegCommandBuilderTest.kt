package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {

    private val landscape = SourceInfo(
        container = Container.MP4, width = 3840, height = 2160, fps = 60.0,
        videoCodec = "h264", pixFmt = "yuv420p", audioCodec = "aac", videoBitrateKbps = 40_000,
    )
    private val hdr = landscape.copy(
        videoCodec = "hevc", pixFmt = "yuv420p10le", colorTransfer = "smpte2084",
        colorPrimaries = "bt2020", colorSpace = "bt2020nc",
    )

    private fun ok(
        params: FfmpegParams,
        source: SourceInfo = landscape,
        export: ExportOptions = ExportOptions(),
        options: BuildOptions = BuildOptions(),
    ): BuildResult.Ok {
        val r = FfmpegCommandBuilder.build(params, source, "in", "out", export, options)
        assertTrue("expected Ok but got $r", r is BuildResult.Ok)
        return r as BuildResult.Ok
    }

    private fun invalid(params: FfmpegParams, source: SourceInfo = landscape, options: BuildOptions = BuildOptions()): BuildResult.Invalid {
        val r = FfmpegCommandBuilder.build(params, source, "in", "out", options = options)
        assertTrue("expected Invalid but got $r", r is BuildResult.Invalid)
        return r as BuildResult.Invalid
    }

    @Test
    fun h265Mp4Golden() {
        val r = ok(FfmpegParams(maxShortSide = 1080, fpsMax = 30, rateControl = RateControl.Crf(28)))
        assertEquals(
            "ffmpeg -y -i in -map 0:v:0 -map '0:a?' -dn -c:v libx265 -crf 28 -preset medium -tag:v hvc1 " +
                "-vf 'fps=30,scale='\\''if(lt(iw,ih),min(iw,1080),-2)'\\'':'\\''if(lt(iw,ih),-2,min(ih,1080))'\\'',setsar=1,format=yuv420p' " +
                "-c:a aac -b:a 128k -map_metadata 0 -movflags +faststart out",
            r.display(),
        )
        assertEquals(Container.MP4, r.container)
    }

    @Test
    fun scaleOmittedWhenShortSideAlreadySmall() {
        val small = landscape.copy(width = 1280, height = 720)
        val args = ok(FfmpegParams(maxShortSide = 1080), small).args
        assertTrue(args.none { it.contains("scale=") })
    }

    @Test
    fun rotatedSourceUsesDisplayDimensions() {
        // Coded 1920x1080 rotated 90° displays as 1080x1920: short side 1080, no downscale to 1080.
        val rotated = landscape.copy(width = 1920, height = 1080, rotation = 90)
        assertTrue(ok(FfmpegParams(maxShortSide = 1080), rotated).args.none { it.contains("scale=") })
        assertTrue(ok(FfmpegParams(maxShortSide = 720), rotated).args.any { it.contains("scale=") })
    }

    @Test
    fun fpsIsReduceOnly() {
        val slow = landscape.copy(fps = 24.0)
        assertTrue(ok(FfmpegParams(fpsMax = 30), slow).args.none { it.contains("fps=") })
        assertTrue(ok(FfmpegParams(fpsMax = 30), landscape).args.any { it.contains("fps=30") })
        assertTrue(ok(FfmpegParams(fpsMax = 30), landscape.copy(fps = 0.0)).args.none { it.contains("fps=") })
    }

    @Test
    fun oddDimensionsGetCropGuard() {
        val odd = landscape.copy(width = 1081, height = 1921)
        val vf = ok(FfmpegParams(), odd).args.let { it[it.indexOf("-vf") + 1] }
        assertTrue(vf.contains("crop=trunc(iw/2)*2:trunc(ih/2)*2"))
        val even = ok(FfmpegParams()).args.let { it[it.indexOf("-vf") + 1] }
        assertTrue(!even.contains("crop="))
    }

    @Test
    fun vp9UsesMinusOneAndWebm() {
        val r = ok(FfmpegParams(videoCodec = VideoCodec.VP9, audioCodec = AudioCodec.OPUS, container = Container.WEBM, maxShortSide = 720))
        assertEquals(Container.WEBM, r.container)
        assertTrue(r.args.containsAll(listOf("-c:v", "libvpx-vp9", "-b:v", "0")))
        assertTrue(r.args.any { it.contains("scale='if(lt(iw,ih),min(iw,720),-1)'") })
        assertTrue("-movflags" !in r.args)
    }

    @Test
    fun av1UsesLibaomCpuUsed() {
        val r = ok(FfmpegParams(videoCodec = VideoCodec.AV1, rateControl = RateControl.Crf(35), container = Container.MKV))
        assertTrue(r.args.zipWithNext().contains("-cpu-used" to "6"))
        assertTrue(r.args.contains("libaom-av1"))
    }

    @Test
    fun h264DoesNotGetHvc1Tag() {
        val r = ok(FfmpegParams(videoCodec = VideoCodec.H264, rateControl = RateControl.Crf(23)))
        assertTrue("-tag:v" !in r.args)
    }

    @Test
    fun hdrKeepForces10BitAndTags() {
        val r = ok(FfmpegParams(dynamicRange = DynamicRange.KEEP), hdr)
        val vf = r.args[r.args.indexOf("-vf") + 1]
        assertTrue(vf.endsWith("format=yuv420p10le"))
        assertTrue(r.args.zipWithNext().containsAll(listOf("-color_trc" to "smpte2084", "-color_primaries" to "bt2020", "-colorspace" to "bt2020nc")))
        assertTrue(!vf.contains("zscale"))
    }

    @Test
    fun hdrKeepWithH264IsRejected() {
        val r = invalid(FfmpegParams(videoCodec = VideoCodec.H264, dynamicRange = DynamicRange.KEEP, rateControl = RateControl.Crf(23)), hdr)
        assertTrue(r.issues.any { it.code == "hdr_unsupported" })
    }

    @Test
    fun forceSdrToneMapsHdrSource() {
        val r = ok(FfmpegParams(dynamicRange = DynamicRange.FORCE_SDR), hdr)
        val vf = r.args[r.args.indexOf("-vf") + 1]
        assertTrue(vf.contains("tonemap=tonemap=hable"))
        assertTrue(vf.endsWith("format=yuv420p"))
        assertTrue(r.args.zipWithNext().contains("-color_trc" to "bt709"))
    }

    private fun toneMapVf(source: SourceInfo, params: FfmpegParams = FfmpegParams(dynamicRange = DynamicRange.FORCE_SDR)): String {
        val r = ok(params, source)
        return r.args[r.args.indexOf("-vf") + 1]
    }

    @Test
    fun toneMapPinsTheInputFromTheProbe() {
        val vf = toneMapVf(hdr)
        assertTrue(vf, vf.contains("zscale=tin=smpte2084:min=bt2020nc:pin=bt2020:rin=tv:t=linear:npl=100:p=bt2020,format=gbrpf32le"))
        val hlg = toneMapVf(hdr.copy(colorTransfer = "arib-std-b67"))
        assertTrue(hlg, hlg.contains("tin=arib-std-b67:"))
    }

    @Test
    fun toneMapFallsBackWhenTagsAreMissingOrNotYuv() {
        val untagged = toneMapVf(hdr.copy(colorTransfer = null, colorPrimaries = null, colorSpace = null, dolbyVisionProfile = 8))
        assertTrue(untagged, untagged.contains("zscale=tin=smpte2084:min=bt2020nc:pin=bt2020:"))
        val rgbMatrix = toneMapVf(hdr.copy(colorSpace = "gbr"))
        assertTrue(rgbMatrix, rgbMatrix.contains("min=bt2020nc:") && !rgbMatrix.contains("min=gbr"))
    }

    @Test
    fun hardwareEncoderToneMapsWithPinnedInput() {
        val vf = toneMapVf(hdr, FfmpegParams(videoCodec = VideoCodec.H264, encoderKind = EncoderKind.HARDWARE_MEDIACODEC, rateControl = RateControl.BitrateKbps(8000)))
        assertTrue(vf, vf.contains("zscale=tin=smpte2084:min=bt2020nc"))
    }

    @Test
    fun forceSdrOnSdrSourceSkipsChain() {
        val r = ok(FfmpegParams(dynamicRange = DynamicRange.FORCE_SDR))
        val vf = r.args[r.args.indexOf("-vf") + 1]
        assertTrue(!vf.contains("zscale"))
        assertTrue(vf.endsWith("format=yuv420p"))
    }

    @Test
    fun autoSdrToneMapsOnlyWhenCodecCannotCarryHdr() {
        val h264 = ok(FfmpegParams(videoCodec = VideoCodec.H264, rateControl = RateControl.Crf(23), dynamicRange = DynamicRange.AUTO_SDR_IF_UNSUPPORTED), hdr)
        assertTrue(h264.args.any { it.contains("tonemap") })
        val h265 = ok(FfmpegParams(dynamicRange = DynamicRange.AUTO_SDR_IF_UNSUPPORTED), hdr)
        assertTrue(h265.args.none { it.contains("tonemap") })
        assertTrue(h265.args.any { it.endsWith("format=yuv420p10le") })
    }

    @Test
    fun toneMapNeedsZscale() {
        val r = invalid(FfmpegParams(dynamicRange = DynamicRange.FORCE_SDR), hdr, BuildOptions(hasZscale = false))
        assertTrue(r.issues.any { it.code == "no_zscale" })
    }

    @Test
    fun explicitPixelFormatsAreEmitted() {
        for (pix in listOf(PixelFormat.YUV420P, PixelFormat.YUV422P, PixelFormat.YUV444P, PixelFormat.YUV422P10LE)) {
            val vf = ok(FfmpegParams(pixelFormat = pix)).args.let { it[it.indexOf("-vf") + 1] }
            assertTrue("$pix -> $vf", vf.endsWith("format=${pix.ffName}"))
        }
        val keep = ok(FfmpegParams(pixelFormat = PixelFormat.KEEP_SOURCE)).args
        assertTrue(keep.none { it.contains("format=") })
    }

    @Test
    fun tenBitH264WarnsButBuilds() {
        val r = ok(FfmpegParams(videoCodec = VideoCodec.H264, rateControl = RateControl.Crf(23), pixelFormat = PixelFormat.YUV420P10LE))
        assertTrue(r.issues.any { it.code == "pix_poor_playback" && it.severity == Severity.WARNING })
    }

    @Test
    fun webmRejects444() {
        val r = invalid(FfmpegParams(videoCodec = VideoCodec.VP9, audioCodec = AudioCodec.OPUS, container = Container.WEBM, pixelFormat = PixelFormat.YUV444P))
        assertTrue(r.issues.any { it.code == "pix_webm_chroma" })
    }

    @Test
    fun metadataFlags() {
        assertTrue(ok(FfmpegParams(), export = ExportOptions(keepExif = false)).args.zipWithNext().contains("-map_metadata" to "-1"))
        val noLoc = ok(FfmpegParams(), export = ExportOptions(keepExif = true, keepLocation = false)).args
        assertTrue(noLoc.zipWithNext().contains("-map_metadata" to "0"))
        assertTrue(noLoc.contains("location="))
        assertTrue(noLoc.contains("com.apple.quicktime.location.ISO6709="))
        assertTrue(ok(FfmpegParams()).args.none { it.startsWith("location") })
    }

    @Test
    fun noAudioSourceOmitsAudioArgs() {
        val args = ok(FfmpegParams(), landscape.copy(audioCodec = null)).args
        assertTrue("-c:a" !in args)
        assertTrue(args.none { it.startsWith("0:a") })
    }

    @Test
    fun audioCopy() {
        val args = ok(FfmpegParams(audioCodec = AudioCodec.COPY)).args
        assertTrue(args.zipWithNext().contains("-c:a" to "copy"))
    }

    @Test
    fun customArgsAppendedBeforeOutput() {
        val args = ok(FfmpegParams(customArgs = "-x265-params 'log-level=error' -threads 2")).args
        assertEquals(listOf("-x265-params", "log-level=error", "-threads", "2", "out"), args.takeLast(5))
    }

    @Test
    fun customArgsRejected() {
        assertTrue(invalid(FfmpegParams(customArgs = "-i other.mp4")).issues.any { it.code == "custom_forbidden_flag" })
        assertTrue(invalid(FfmpegParams(customArgs = "-an /sdcard/out.mp4")).issues.any { it.code == "custom_output_path" })
        assertTrue(invalid(FfmpegParams(customArgs = "-vf 'unterminated")).issues.any { it.code == "custom_syntax" })
    }

    @Test
    fun replaceModeUsesOnlyCustomArgs() {
        val args = ok(FfmpegParams(customArgs = "-c:v libx264 -crf 20", customArgsReplaceGenerated = true)).args
        assertEquals(listOf("-y", "-i", "in", "-c:v", "libx264", "-crf", "20", "out"), args)
    }

    @Test
    fun copyVideoIgnoresFilters() {
        val r = ok(FfmpegParams(videoCodec = VideoCodec.COPY, maxShortSide = 720, fpsMax = 24))
        assertTrue(r.args.zipWithNext().contains("-c:v" to "copy"))
        assertTrue("-vf" !in r.args)
        assertTrue(r.issues.any { it.code == "copy_ignores_filters" })
    }

    @Test
    fun crfIsValidatedPerCodec() {
        assertTrue(invalid(FfmpegParams(rateControl = RateControl.Crf(52))).issues.any { it.code == "crf_range" })
        ok(FfmpegParams(videoCodec = VideoCodec.VP9, audioCodec = AudioCodec.OPUS, container = Container.WEBM, rateControl = RateControl.Crf(52)))
        assertTrue(invalid(FfmpegParams(rateControl = RateControl.BitrateKbps(2000))).issues.any { it.code == "rc_sw_crf" })
    }

    private val hevcHw = HardwareEncoder(
        VideoCodec.H265, "c2.vendor.hevc.encoder", supportsCq = true, qualityRange = 1..100, bitrateRangeKbps = 1..100_000,
    )
    private val avcHwNoCq = HardwareEncoder(
        VideoCodec.H264, "c2.vendor.avc.encoder", supportsCq = false, qualityRange = null, bitrateRangeKbps = 1..50_000,
    )
    private val deviceOptions = BuildOptions(hardware = HardwareEncoders(mapOf(VideoCodec.H265 to hevcHw, VideoCodec.H264 to avcHwNoCq)))
    private val hwParams = FfmpegParams(encoderKind = EncoderKind.HARDWARE_MEDIACODEC, pixelFormat = PixelFormat.YUV420P)

    @Test
    fun hardwareEncoderIsPinnedByName() {
        val args = ok(hwParams.copy(rateControl = RateControl.BitrateKbps(5000)), options = deviceOptions).args
        assertTrue(args.zipWithNext().containsAll(listOf("-c:v" to "hevc_mediacodec", "-codec_name" to "c2.vendor.hevc.encoder", "-b:v" to "5000k")))
    }

    @Test
    fun hardwareConstantQualityNeedsDeviceSupportAndRange() {
        val cq = hwParams.copy(rateControl = RateControl.ConstantQuality(60))
        val args = ok(cq, options = deviceOptions).args
        assertTrue(args.zipWithNext().containsAll(listOf("-bitrate_mode" to "cq", "-global_quality" to "60")))

        assertTrue(invalid(cq.copy(rateControl = RateControl.ConstantQuality(101)), options = deviceOptions).issues.any { it.code == "cq_range" })
        assertTrue(invalid(cq.copy(rateControl = RateControl.ConstantQuality(0)), options = deviceOptions).issues.any { it.code == "cq_range" })
        assertTrue(invalid(cq.copy(videoCodec = VideoCodec.H264), options = deviceOptions).issues.any { it.code == "rc_hw_cq" })
    }

    @Test
    fun hardwareNeedsAnEncoderOnTheDevice() {
        val p = hwParams.copy(rateControl = RateControl.BitrateKbps(5000))
        val none = BuildOptions(hardware = HardwareEncoders())
        assertTrue(invalid(p, options = none).issues.any { it.code == "hw_unavailable" })
        // Callers that did not probe are not blocked, and get no -codec_name.
        assertTrue("-codec_name" !in ok(p).args)
    }

    @Test
    fun hardwareBitrateOutsideTheDeviceRangeOnlyWarns() {
        val r = ok(hwParams.copy(videoCodec = VideoCodec.H264, rateControl = RateControl.BitrateKbps(80_000)), options = deviceOptions)
        assertTrue(r.issues.any { it.code == "hw_bitrate_range" && it.severity == Severity.WARNING })
    }

    @Test
    fun hardwareEncoderUsesBitrate() {
        val p = FfmpegParams(
            encoderKind = EncoderKind.HARDWARE_MEDIACODEC,
            rateControl = RateControl.PercentOfSource(40),
            pixelFormat = PixelFormat.YUV420P,
        )
        val r = ok(p)
        assertTrue(r.args.zipWithNext().containsAll(listOf("-c:v" to "hevc_mediacodec", "-b:v" to "16000k", "-bitrate_mode" to "vbr")))
        assertTrue(invalid(p.copy(rateControl = RateControl.Crf(26))).issues.any { it.code == "rc_hw_crf" })
        assertTrue(invalid(p.copy(videoCodec = VideoCodec.VP9)).issues.any { it.code == "hw_codec" })
    }

    @Test
    fun threadsOption() {
        val args = ok(FfmpegParams(), options = BuildOptions(threads = 4)).args
        assertEquals(listOf("-y", "-threads", "4", "-i"), args.take(4))
    }
}
