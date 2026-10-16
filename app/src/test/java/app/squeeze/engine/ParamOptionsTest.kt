package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamOptionsTest {
    @Test
    fun `switching codec keeps equivalent quality`() {
        val h264 = FfmpegParams(videoCodec = VideoCodec.H264, rateControl = RateControl.Crf(23))
        assertEquals(RateControl.Crf(28), ParamOptions.withVideoCodec(h264, VideoCodec.H265).rateControl)
        assertEquals(RateControl.Crf(31), ParamOptions.withVideoCodec(h264, VideoCodec.VP9).rateControl)
        // Out of range values are clamped.
        val vp9 = FfmpegParams(videoCodec = VideoCodec.VP9, rateControl = RateControl.Crf(63))
        assertEquals(RateControl.Crf(51), ParamOptions.withVideoCodec(vp9, VideoCodec.H264).rateControl)
    }

    @Test
    fun `bitrate modes survive a codec switch and copy needs no rate control`() {
        val p = FfmpegParams(videoCodec = VideoCodec.H264, rateControl = RateControl.BitrateKbps(3000))
        assertEquals(RateControl.BitrateKbps(3000), ParamOptions.withVideoCodec(p, VideoCodec.H265).rateControl)
        assertTrue(!ParamOptions.usesRateControl(p.copy(videoCodec = VideoCodec.COPY)))
    }

    @Test
    fun `switching to a codec the container cannot hold resets container and audio`() {
        val p = FfmpegParams(videoCodec = VideoCodec.H264, container = Container.MOV, audioCodec = AudioCodec.AAC)
        assertEquals(Container.ORIGINAL, ParamOptions.withVideoCodec(p, VideoCodec.VP9).container)

        val webm = FfmpegParams(videoCodec = VideoCodec.VP9, container = Container.WEBM, audioCodec = AudioCodec.OPUS)
        val switched = ParamOptions.withVideoCodec(webm, VideoCodec.AV1)
        assertEquals(Container.WEBM, switched.container)
    }

    @Test
    fun `container reasons explain incompatibilities`() {
        val p = FfmpegParams(videoCodec = VideoCodec.H265, audioCodec = AudioCodec.AAC)
        assertNull(ParamOptions.containerDisabledReason(Container.MP4, p))
        assertNull(ParamOptions.containerDisabledReason(Container.ORIGINAL, p))
        assertNotNull(ParamOptions.containerDisabledReason(Container.WEBM, p))
        assertNotNull(ParamOptions.containerDisabledReason(Container.AVI, p))
        // Copy never blocks a container: that is checked per source at run time.
        assertNull(ParamOptions.containerDisabledReason(Container.WEBM, p.copy(videoCodec = VideoCodec.COPY, audioCodec = AudioCodec.COPY)))
        assertNotNull(ParamOptions.containerDisabledReason(Container.WEBM, FfmpegParams(videoCodec = VideoCodec.VP9, audioCodec = AudioCodec.AAC)))
    }

    @Test
    fun `quality hints follow the codec scale`() {
        assertEquals(QualityHint.BALANCED, ParamOptions.qualityHint(VideoCodec.H264, 23))
        assertEquals(QualityHint.BALANCED, ParamOptions.qualityHint(VideoCodec.H265, 28))
        assertEquals(QualityHint.NEAR_LOSSLESS, ParamOptions.qualityHint(VideoCodec.H264, 15))
        assertEquals(QualityHint.HIGH, ParamOptions.qualityHint(VideoCodec.H265, 24))
        assertEquals(QualityHint.VERY_SMALL, ParamOptions.qualityHint(VideoCodec.H265, 40))
    }

    @Test
    fun `rate mode switching seeds values`() {
        val crf = FfmpegParams(videoCodec = VideoCodec.H265, rateControl = RateControl.Crf(30))
        val bitrate = ParamOptions.withRateMode(crf, RateMode.BITRATE)
        assertEquals(RateControl.BitrateKbps(4000), bitrate.rateControl)
        assertEquals(RateControl.Crf(31), ParamOptions.withRateMode(bitrate, RateMode.CRF).rateControl)
        assertEquals(RateMode.PERCENT, ParamOptions.rateMode(ParamOptions.withRateMode(crf, RateMode.PERCENT).rateControl))
    }

    private val hevcHw = HardwareEncoder(VideoCodec.H265, "hevc", supportsCq = true, qualityRange = 1..100, bitrateRangeKbps = null)
    private val avcHw = HardwareEncoder(VideoCodec.H264, "avc", supportsCq = false, qualityRange = null, bitrateRangeKbps = null)
    private val device = HardwareEncoders(mapOf(VideoCodec.H265 to hevcHw, VideoCodec.H264 to avcHw))

    @Test
    fun `hardware has no crf and only some devices have quality mode`() {
        val sw = FfmpegParams(videoCodec = VideoCodec.H265)
        assertTrue(ParamOptions.isRateModeAvailable(sw, RateMode.CRF, device))
        assertTrue(!ParamOptions.isRateModeAvailable(sw, RateMode.BITRATE, device))

        val hw = sw.copy(encoderKind = EncoderKind.HARDWARE_MEDIACODEC)
        assertTrue(!ParamOptions.isRateModeAvailable(hw, RateMode.CRF, device))
        assertTrue(ParamOptions.isRateModeAvailable(hw, RateMode.QUALITY, device))
        assertTrue(ParamOptions.isRateModeAvailable(hw, RateMode.BITRATE, device))
        assertTrue(!ParamOptions.isRateModeAvailable(hw.copy(videoCodec = VideoCodec.H264), RateMode.QUALITY, device))
    }

    @Test
    fun `switching to hardware replaces crf by a quality or a bitrate`() {
        val sw = FfmpegParams(videoCodec = VideoCodec.H265, rateControl = RateControl.Crf(28))
        val cq = ParamOptions.withEncoder(sw, VideoCodec.H265, EncoderKind.HARDWARE_MEDIACODEC, device)
        assertEquals(EncoderKind.HARDWARE_MEDIACODEC, cq.encoderKind)
        assertEquals(RateControl.ConstantQuality(60), cq.rateControl) // 1 + 99 * 0.6, rounded

        val bitrate = ParamOptions.withEncoder(sw, VideoCodec.H264, EncoderKind.HARDWARE_MEDIACODEC, device)
        assertEquals(RateControl.BitrateKbps(4_000), bitrate.rateControl)
    }

    @Test
    fun `switching back to software restores a crf and keeps bitrates out`() {
        val hw = FfmpegParams(
            videoCodec = VideoCodec.H265, encoderKind = EncoderKind.HARDWARE_MEDIACODEC,
            rateControl = RateControl.BitrateKbps(3_000),
        )
        val sw = ParamOptions.withEncoder(hw, VideoCodec.H265, EncoderKind.SOFTWARE, device)
        assertEquals(EncoderKind.SOFTWARE, sw.encoderKind)
        assertEquals(RateControl.Crf(31), sw.rateControl)
    }

    @Test
    fun `hardware quality is clamped when the codec changes`() {
        val hw = FfmpegParams(
            videoCodec = VideoCodec.H265, encoderKind = EncoderKind.HARDWARE_MEDIACODEC,
            rateControl = RateControl.ConstantQuality(95),
        )
        val narrow = HardwareEncoders(mapOf(VideoCodec.H264 to avcHw.copy(supportsCq = true, qualityRange = 1..50)))
        assertEquals(
            RateControl.ConstantQuality(50),
            ParamOptions.withEncoder(hw, VideoCodec.H264, EncoderKind.HARDWARE_MEDIACODEC, narrow).rateControl,
        )
    }
}
