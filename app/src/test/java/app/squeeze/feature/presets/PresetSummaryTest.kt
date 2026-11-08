package app.squeeze.feature.presets

import app.squeeze.engine.FfmpegParams
import app.squeeze.engine.ImageFormat
import app.squeeze.engine.ImageParams
import app.squeeze.engine.JxlParams
import app.squeeze.engine.Preset
import app.squeeze.engine.RateControl
import app.squeeze.engine.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetSummaryTest {
    private fun preset(
        params: FfmpegParams? = null,
        imageParams: ImageParams? = null,
    ) = Preset(id = "p", name = "P", params = params, imageParams = imageParams)

    @Test
    fun videoOnlyPresetShowsVideoSettingsOnly() {
        val p = preset(
            params = FfmpegParams(
                videoCodec = VideoCodec.H265,
                rateControl = RateControl.Crf(20),
            ),
        )
        assertEquals("H.265 · CRF 20", videoSummary(p))
        assertNull(imageSummary(p))
    }

    @Test
    fun dualPresetShowsBothKindsSeparately() {
        val p = preset(
            params = FfmpegParams(
                videoCodec = VideoCodec.H264,
                rateControl = RateControl.Crf(28),
                maxShortSide = 720,
            ),
            imageParams = ImageParams(format = ImageFormat.JPEG, quality = 85),
        )
        assertEquals("H.264 · CRF 28 · 720p", videoSummary(p))
        assertEquals("Jpeg · Q85", imageSummary(p))
    }

    @Test
    fun photosOnlyPresetShowsFormatQualityAndResolution() {
        val p = preset(
            imageParams = ImageParams(
                format = ImageFormat.AVIF,
                avifCrf = 30,
                maxShortSide = 1080,
            ),
        )
        assertEquals("Avif · CRF 30 · 1080p", imageSummary(p))
        assertNull(videoSummary(p))
    }

    @Test
    fun jpegXlLabelUsesItsOwnQuality() {
        val p = preset(imageParams = ImageParams(format = ImageFormat.JPEG_XL, jxl = JxlParams(quality = 90)))
        assertEquals("Jpeg xl · Q90", imageSummary(p))
    }

    @Test
    fun losslessFormatsSayLosslessInsteadOfQuality() {
        assertEquals(
            "Webp · lossless",
            imageSummary(preset(imageParams = ImageParams(format = ImageFormat.WEBP, webpLossless = true))),
        )
        assertEquals(
            "Png · lossless",
            imageSummary(preset(imageParams = ImageParams(format = ImageFormat.PNG))),
        )
        assertEquals(
            "Jpeg xl · lossless",
            imageSummary(preset(imageParams = ImageParams(format = ImageFormat.JPEG_XL, jxl = JxlParams(modular = true)))),
        )
    }
}
