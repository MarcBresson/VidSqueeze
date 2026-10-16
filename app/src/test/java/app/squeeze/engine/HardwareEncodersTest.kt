package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareEncodersTest {
    private fun encoder(codec: VideoCodec, name: String, cq: Boolean = false) =
        HardwareEncoder(codec, name, supportsCq = cq, qualityRange = if (cq) 1..100 else null, bitrateRangeKbps = null)

    private fun candidate(codec: VideoCodec, name: String, hardware: Boolean = true, cq: Boolean = false) =
        HardwareEncoders.Candidate(codec, hardware, encoder(codec, name, cq))

    @Test
    fun `software encoders are dropped`() {
        val found = HardwareEncoders.select(listOf(candidate(VideoCodec.H264, "c2.android.avc.encoder", hardware = false)))
        assertTrue(found.byCodec.isEmpty())
        assertTrue(found.codecs.isEmpty())
    }

    @Test
    fun `an encoder with quality mode beats one without`() {
        val found = HardwareEncoders.select(
            listOf(
                candidate(VideoCodec.H265, "plain"),
                candidate(VideoCodec.H265, "with-cq", cq = true),
                candidate(VideoCodec.H264, "avc"),
            ),
        )
        assertEquals("with-cq", found[VideoCodec.H265]?.name)
        assertEquals(listOf(VideoCodec.H264, VideoCodec.H265), found.codecs)
    }

    @Test
    fun `only codecs with an ffmpeg wrapper are offered`() {
        val found = HardwareEncoders.select(listOf(candidate(VideoCodec.AV1, "av1")))
        assertNull(found[VideoCodec.AV1])
    }
}
