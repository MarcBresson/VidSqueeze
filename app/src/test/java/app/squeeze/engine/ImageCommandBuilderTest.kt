package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCommandBuilderTest {
    private val photo = SourceInfo(container = null, width = 4000, height = 3000, videoCodec = "mjpeg", pixFmt = "yuvj420p")
    private val portrait = SourceInfo(width = 3000, height = 4000, videoCodec = "mjpeg", pixFmt = "yuvj420p")

    private fun args(p: ImageParams, s: SourceInfo = photo, orientation: Int = 0): List<String> =
        (ImageCommandBuilder.build(p, s, orientation, "in", "out.${p.format.extension}") as ImageBuildOk).args

    @Test
    fun jpegQualityMapsToInverseQscale() {
        assertEquals(1, ImageCommandBuilder.jpegQscale(100))
        assertEquals(31, ImageCommandBuilder.jpegQscale(1))
        assertEquals(6, ImageCommandBuilder.jpegQscale(85))
        assertEquals(3, ImageCommandBuilder.jpegQscale(95)) // clamp check at the high end
    }

    @Test
    fun jpegArguments() {
        val a = args(ImageParams(format = ImageFormat.JPEG, quality = 85))
        assertTrue(a.contains("-c:v") && a.contains("mjpeg"))
        assertTrue(a.contains("-q:v") && a.contains("6"))
        assertTrue(a.contains("-pix_fmt") && a.contains("yuvj420p"))
        assertTrue(a.contains("-frames:v") && a.contains("1"))
        assertTrue("no metadata from ffmpeg", !a.contains("-map_metadata") || a[a.indexOf("-map_metadata") + 1] == "-1")
        assertTrue(a.first() == "-y")
        assertTrue(a.last() == "out.jpg")
    }

    @Test
    fun jpeg444Chroma() {
        val a = args(ImageParams(format = ImageFormat.JPEG, jpegChroma444 = true))
        assertTrue(a.contains("yuvj444p"))
    }

    @Test
    fun pngCompressionAndDepth() {
        val a8 = args(ImageParams(format = ImageFormat.PNG, pngBitDepth = 8, pngCompression = 9))
        assertTrue(a8.contains("-compression_level") && a8.contains("9"))
        assertTrue(a8.contains("rgb24"))
        val a16 = args(ImageParams(format = ImageFormat.PNG, pngBitDepth = 16))
        assertTrue(a16.contains("rgb48be"))
        assertTrue(a16.last() == "out.png")
    }

    @Test
    fun png16BitFrom8BitSourceWarns() {
        val r = ImageCommandBuilder.build(ImageParams(format = ImageFormat.PNG, pngBitDepth = 16), photo, 0, "in", "out.png") as ImageBuildOk
        assertTrue(r.issues.any { it.severity == Severity.WARNING })
        assertFalse(r.issues.hasErrors)
    }

    @Test
    fun avifStillPicture() {
        val a = args(ImageParams(format = ImageFormat.AVIF, avifCrf = 35, avifEffort = 4))
        assertTrue(a.contains("libaom-av1"))
        assertTrue(a.contains("-still-picture") && a.contains("1"))
        assertTrue(a.contains("-crf") && a.contains("35"))
        assertTrue(a.contains("-cpu-used") && a.contains("4"))
        assertTrue(a.last() == "out.avif")
    }

    @Test
    fun webpAndJxlAreNotFfmpegFormats() {
        for (f in listOf(ImageFormat.WEBP, ImageFormat.JPEG_XL)) {
            val r = ImageCommandBuilder.build(ImageParams(format = f), photo, 0, "in", "out")
            assertTrue(r is ImageBuildInvalid)
        }
    }

    @Test
    fun orientationBecomesTransposeBeforeScale() {
        val p = ImageParams(format = ImageFormat.JPEG, maxShortSide = 2000)
        val a = args(p, orientation = 90)
        val vf = a[a.indexOf("-vf") + 1]
        assertTrue(vf.startsWith("transpose=1"))
        assertTrue(vf.contains("scale="))
        // 4000x3000 landscape limited to a 2000 short side → 2667x2000
        assertTrue("vf=$vf", vf.contains("2667x2000"))
    }

    @Test
    fun rotation180IsDoubleTranspose() {
        val vf = args(ImageParams(format = ImageFormat.JPEG), orientation = 180)[args(ImageParams(format = ImageFormat.JPEG), orientation = 180).indexOf("-vf") + 1]
        assertEquals("transpose=1,transpose=1", vf)
    }

    @Test
    fun noDownscaleWhenWithinLimit() {
        val small = SourceInfo(width = 1000, height = 800, videoCodec = "mjpeg", pixFmt = "yuvj420p")
        val a = args(ImageParams(format = ImageFormat.JPEG, maxShortSide = 2000), small)
        assertFalse(a.contains("-vf"))
    }

    @Test
    fun hdrSourceWarnsButEncodes() {
        val hdr = SourceInfo(width = 100, height = 100, videoCodec = "png", pixFmt = "rgb48le", colorTransfer = SourceInfo.TRANSFER_PQ)
        val r = ImageCommandBuilder.build(ImageParams(format = ImageFormat.JPEG), hdr, 0, "in", "out.jpg") as ImageBuildOk
        assertTrue(r.issues.any { it.severity == Severity.WARNING })
    }
}
