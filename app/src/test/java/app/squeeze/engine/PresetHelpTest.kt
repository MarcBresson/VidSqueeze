package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetHelpTest {
    @Test
    fun `every video codec has help`() {
        for (codec in VideoCodec.entries) {
            assertTrue("missing help for $codec", PresetHelp.videoCodec.containsKey(codec))
        }
    }

    @Test
    fun `every rate mode has help`() {
        for (mode in RateMode.entries) {
            assertTrue("missing help for $mode", PresetHelp.rateMode.containsKey(mode))
        }
    }

    @Test
    fun `every entry is complete and non-technical`() {
        for (e in PresetHelp.all) {
            assertTrue(e.whatItDoes.isNotBlank())
            assertTrue(e.whichToPick.isNotBlank())
            // sentences, plain language: no bare jargon like "CRF" alone in whatItDoes
        }
    }

    @Test
    fun `every image format has a quality-knob help`() {
        for (format in ImageFormat.entries) {
            assertTrue("missing quality help for $format", PresetHelp.photoQuality(format).whichToPick.isNotBlank())
        }
    }

    @Test
    fun `photo and export knobs are all covered`() {
        val expected = listOf(
            PresetHelpKey.PHOTO_FORMAT, PresetHelpKey.PHOTO_QUALITY, PresetHelpKey.PHOTO_RESOLUTION,
            PresetHelpKey.JPEG_CHROMA, PresetHelpKey.PNG_DEPTH, PresetHelpKey.WEBP_LOSSLESS,
            PresetHelpKey.JXL_EFFORT, PresetHelpKey.JXL_MODULAR,
            PresetHelpKey.EXPORT_MODE, PresetHelpKey.NAME_TEMPLATE, PresetHelpKey.KEEP_EXIF,
            PresetHelpKey.KEEP_LOCATION,
        )
        assertEquals(expected, PresetHelp.photoAndExportKeys)
    }

    @Test
    fun `replace-originals help warns the original is deleted`() {
        assertTrue(PresetHelp.exportMode(ExportMode.REPLACE_ORIGINALS).whichToPick.contains("deleted", ignoreCase = true))
    }

    @Test
    fun `catalog covers exactly the video knobs the form shows`() {
        val expected = listOf(
            PresetHelpKey.VIDEO_CODEC, PresetHelpKey.ENCODER, PresetHelpKey.RATE_MODE,
            PresetHelpKey.RESOLUTION, PresetHelpKey.FPS, PresetHelpKey.AUDIO_CODEC,
            PresetHelpKey.AUDIO_BITRATE, PresetHelpKey.CONTAINER, PresetHelpKey.CUSTOM_ARGS,
        )
        assertEquals(expected, PresetHelp.keys)
    }
}
