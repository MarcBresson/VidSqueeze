package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecContainerMatrixTest {
    private val src = SourceInfo(container = Container.MOV, videoCodec = "hevc", audioCodec = "aac")

    @Test
    fun originalContainerKeptWhenCompatible() {
        val r = CodecContainerMatrix.resolveContainer(FfmpegParams(), src)
        assertEquals(Container.MOV, r.container)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun originalFallsBackWithNotice() {
        val r = CodecContainerMatrix.resolveContainer(
            FfmpegParams(videoCodec = VideoCodec.VP9, audioCodec = AudioCodec.OPUS),
            src,
        )
        assertEquals(Container.MP4, r.container)
        assertEquals("container_fallback", r.issues.single().code)
    }

    @Test
    fun explicitIncompatibleContainerIsError() {
        val r = CodecContainerMatrix.resolveContainer(
            FfmpegParams(videoCodec = VideoCodec.H265, container = Container.WEBM, audioCodec = AudioCodec.OPUS),
            src,
        )
        assertNull(r.container)
        assertTrue(r.issues.hasErrors)
    }

    @Test
    fun webmAudioMustBeOpus() {
        val r = CodecContainerMatrix.resolveContainer(
            FfmpegParams(videoCodec = VideoCodec.VP9, container = Container.WEBM, audioCodec = AudioCodec.AAC),
            src,
        )
        assertTrue(r.issues.any { it.code == "container_audio" })
    }

    @Test
    fun copiedAudioCheckedAgainstSource() {
        val params = FfmpegParams(videoCodec = VideoCodec.VP9, container = Container.WEBM, audioCodec = AudioCodec.COPY)
        assertTrue(CodecContainerMatrix.resolveContainer(params, src).issues.hasErrors) // aac can't go in webm
        assertNull(CodecContainerMatrix.resolveContainer(params, src.copy(audioCodec = "opus")).issues.firstOrNull())
    }

    @Test
    fun copiedUnknownCodecOnlyFitsMkv() {
        val r = CodecContainerMatrix.resolveContainer(
            FfmpegParams(videoCodec = VideoCodec.COPY, audioCodec = AudioCodec.COPY),
            SourceInfo(container = Container.AVI, videoCodec = "mpeg4", audioCodec = "mp3"),
        )
        assertEquals(Container.MKV, r.container)
    }

    @Test
    fun hdrCarriage() {
        assertTrue(CodecContainerMatrix.carriesHdr(VideoCodec.H265, Container.MP4, EncoderKind.SOFTWARE))
        assertTrue(!CodecContainerMatrix.carriesHdr(VideoCodec.H264, Container.MP4, EncoderKind.SOFTWARE))
        assertTrue(!CodecContainerMatrix.carriesHdr(VideoCodec.H265, Container.MP4, EncoderKind.HARDWARE_MEDIACODEC))
    }

    @Test
    fun pixelFormatRules() {
        val hw = CodecContainerMatrix.pixelFormatIssues(PixelFormat.YUV422P, VideoCodec.H264, EncoderKind.HARDWARE_MEDIACODEC, Container.MP4)
        assertTrue(hw.hasErrors)
        val webm = CodecContainerMatrix.pixelFormatIssues(PixelFormat.YUV444P, VideoCodec.AV1, EncoderKind.SOFTWARE, Container.WEBM)
        assertTrue(webm.hasErrors)
        val ok = CodecContainerMatrix.pixelFormatIssues(PixelFormat.YUV420P10LE, VideoCodec.H265, EncoderKind.SOFTWARE, Container.MP4)
        assertTrue(ok.isEmpty())
    }
}
