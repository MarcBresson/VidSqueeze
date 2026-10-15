package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeEstimatorTest {
    private val fourK = SourceInfo(
        container = Container.MP4, durationMs = 60_000, width = 3840, height = 2160, fps = 60.0,
        videoCodec = "h264", videoBitrateKbps = 60_000, audioCodec = "aac",
    )
    private val bytes = 60_000L * 1000 / 8 * 60 + 960_000 // ≈ 451 MB

    private fun estimate(p: FfmpegParams, s: SourceInfo = fourK, b: Long = bytes) = SizeEstimator.estimateBytes(p, s, b)!!

    @Test
    fun `unknown duration or size gives no estimate`() {
        assertNull(SizeEstimator.estimateBytes(FfmpegParams(), fourK.copy(durationMs = 0), bytes))
        assertNull(SizeEstimator.estimateBytes(FfmpegParams(), fourK, 0))
    }

    @Test
    fun `stream copy keeps the size`() {
        val p = FfmpegParams(videoCodec = VideoCodec.COPY, audioCodec = AudioCodec.COPY)
        assertEquals(bytes, estimate(p))
    }

    @Test
    fun `lower resolution and higher crf shrink the output`() {
        val base = FfmpegParams(videoCodec = VideoCodec.H265, rateControl = RateControl.Crf(26))
        val small = estimate(base.copy(maxShortSide = 720))
        val full = estimate(base)
        assertTrue("$small < $full", small < full)
        assertTrue(estimate(base.copy(rateControl = RateControl.Crf(32))) < full)
        assertTrue(estimate(base.copy(fpsMax = 30)) < full)
    }

    @Test
    fun `h265 beats h264 at the same visual crf offset and result is below the source`() {
        val h264 = estimate(FfmpegParams(videoCodec = VideoCodec.H264, rateControl = RateControl.Crf(23)))
        val h265 = estimate(FfmpegParams(videoCodec = VideoCodec.H265, rateControl = RateControl.Crf(28)))
        assertTrue(h265 < h264)
        assertTrue(h264 < bytes)
    }

    @Test
    fun `explicit bitrate and percent modes are honoured`() {
        val kbps = estimate(FfmpegParams(videoCodec = VideoCodec.H264, rateControl = RateControl.BitrateKbps(4_000), audioCodec = AudioCodec.AAC, audioBitrateK = 128))
        // (4000 + 128) kbit/s for 60 s = ~31 MB
        assertEquals(31_000_000.0, kbps.toDouble(), 1_500_000.0)
        val half = estimate(FfmpegParams(videoCodec = VideoCodec.H265, rateControl = RateControl.PercentOfSource(50), audioCodec = AudioCodec.COPY))
        assertEquals(bytes / 2.0, half.toDouble(), bytes * 0.05)
    }

    @Test
    fun `silent sources carry no audio bytes`() {
        val p = FfmpegParams(videoCodec = VideoCodec.H264, rateControl = RateControl.BitrateKbps(1_000), audioCodec = AudioCodec.AAC, audioBitrateK = 320)
        val silent = SizeEstimator.estimateBytes(p, fourK.copy(audioCodec = null), bytes)!!
        assertNotNull(silent)
        assertEquals(7_575_000.0, silent.toDouble(), 100_000.0) // 1 Mbit/s * 60 s
    }
}
