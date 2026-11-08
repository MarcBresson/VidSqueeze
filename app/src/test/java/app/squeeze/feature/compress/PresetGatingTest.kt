package app.squeeze.feature.compress

import app.squeeze.engine.FfmpegParams
import app.squeeze.engine.ImageParams
import app.squeeze.engine.MediaKind
import app.squeeze.engine.Preset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetGatingTest {
    private val videoOnly = Preset(id = "v", name = "v", params = FfmpegParams(), imageParams = null)
    private val photoOnly = Preset(id = "p", name = "p", params = null, imageParams = ImageParams())
    private val dual = Preset(id = "b", name = "b", params = FfmpegParams(), imageParams = ImageParams())

    @Test
    fun emptySelectionKeepsEverythingUsable() {
        for (p in listOf(videoOnly, photoOnly, dual)) assertTrue(presetUsable(p, emptySet()))
    }

    @Test
    fun singleKindSelection() {
        assertTrue(presetUsable(dual, setOf(MediaKind.VIDEO)))
        assertTrue(presetUsable(videoOnly, setOf(MediaKind.VIDEO)))
        assertFalse(presetUsable(videoOnly, setOf(MediaKind.IMAGE)))
        assertFalse(presetUsable(photoOnly, setOf(MediaKind.VIDEO)))
    }

    @Test
    fun mixedSelectionNeedsADualPreset() {
        val mixed = setOf(MediaKind.VIDEO, MediaKind.IMAGE)
        assertFalse(presetUsable(videoOnly, mixed))
        assertFalse(presetUsable(photoOnly, mixed))
        assertTrue(presetUsable(dual, mixed))
    }
}
