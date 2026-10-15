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
}
