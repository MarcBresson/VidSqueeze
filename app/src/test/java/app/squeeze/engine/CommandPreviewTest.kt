package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPreviewTest {
    @Test
    fun `built-in presets preview without errors against the sample clip`() {
        BuiltInPresets.all.forEach {
            val preview = CommandPreview.of(it, null)
            assertFalse("${it.name}: ${preview.issues}", preview.hasErrors)
            assertNotNull(preview.command)
            assertTrue(preview.command!!.contains("<input>"))
        }
    }

    @Test
    fun `incompatible container is reported and has no command`() {
        val preset = BuiltInPresets.share.copy(params = BuiltInPresets.share.params.copy(container = Container.WEBM))
        val preview = CommandPreview.of(preset, null)
        assertTrue(preview.hasErrors)
        assertNull(preview.command)
    }

    @Test
    fun `uses the real source when given`() {
        val vertical = CommandPreview.SAMPLE_SOURCE.copy(width = 1080, height = 1920)
        val a = CommandPreview.of(BuiltInPresets.share, vertical)
        assertEquals(Container.MP4, a.container)
        assertNotNull(a.command)
    }

    @Test
    fun `forbidden custom args surface as errors`() {
        val preset = BuiltInPresets.share.copy(params = BuiltInPresets.share.params.copy(customArgs = "-i other.mp4"))
        assertTrue(CommandPreview.of(preset, null).hasErrors)
    }
}
