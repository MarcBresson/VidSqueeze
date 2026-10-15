package app.squeeze.presets

import app.squeeze.engine.BuiltInPresets
import app.squeeze.engine.FfmpegParams
import app.squeeze.engine.Preset
import app.squeeze.engine.RateControl
import app.squeeze.engine.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetTransferTest {
    private var counter = 0
    private fun newId() = "new-${++counter}"

    private val custom = Preset(
        id = "user.1", name = "Mine",
        params = FfmpegParams(videoCodec = VideoCodec.AV1, rateControl = RateControl.Crf(33), customArgs = "-tune film"),
    )

    @Test
    fun `round trip keeps settings but assigns new ids and drops built-in flag`() {
        val json = PresetTransfer.export(listOf(custom, BuiltInPresets.share))
        val result = PresetTransfer.import(json, emptyList(), ::newId) as ImportResult.Ok

        assertEquals(2, result.presets.size)
        assertEquals(custom.params, result.presets[0].params)
        assertEquals(listOf("new-1", "new-2"), result.presets.map { it.id })
        assertTrue(result.presets.none { it.builtIn })
        assertEquals(BuiltInPresets.share.params, result.presets[1].params)
    }

    @Test
    fun `imported names are made unique`() {
        val json = PresetTransfer.export(listOf(custom, custom))
        val result = PresetTransfer.import(json, listOf("mine"), ::newId) as ImportResult.Ok
        assertEquals(listOf("Mine (2)", "Mine (3)"), result.presets.map { it.name })
    }

    @Test
    fun `garbage and foreign json are rejected`() {
        assertTrue(PresetTransfer.import("not json", emptyList(), ::newId) is ImportResult.Invalid)
        assertTrue(PresetTransfer.import("""{"hello":"world"}""", emptyList(), ::newId) is ImportResult.Invalid)
        assertTrue(PresetTransfer.import("""{"format":"other","presets":[]}""", emptyList(), ::newId) is ImportResult.Invalid)
    }

    @Test
    fun `newer file versions and empty files are rejected`() {
        assertTrue(PresetTransfer.import("""{"format":"squeeze-presets","version":99,"presets":[]}""", emptyList(), ::newId) is ImportResult.Invalid)
        assertTrue(PresetTransfer.import("""{"format":"squeeze-presets","version":1,"presets":[]}""", emptyList(), ::newId) is ImportResult.Invalid)
    }

    @Test
    fun `unknown keys from newer minor versions are ignored`() {
        val json = PresetTransfer.export(listOf(custom)).replace("\"customArgs\"", "\"futureField\":1,\"customArgs\"")
        assertTrue(PresetTransfer.import(json, emptyList(), ::newId) is ImportResult.Ok)
    }

    @Test
    fun `unique name leaves free names alone`() {
        assertEquals("A", PresetTransfer.uniqueName("A", listOf("B")))
        assertFalse(PresetTransfer.uniqueName("A", listOf("a")) == "A")
    }
}
