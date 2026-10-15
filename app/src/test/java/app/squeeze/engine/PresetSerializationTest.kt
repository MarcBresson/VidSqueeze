package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetSerializationTest {
    @Test
    fun builtInsRoundTrip() {
        for (preset in BuiltInPresets.all) {
            val json = EngineJson.encodeToString(Preset.serializer(), preset)
            assertEquals(preset, EngineJson.decodeFromString(Preset.serializer(), json))
        }
    }

    @Test
    fun rateControlIsPolymorphic() {
        val p = FfmpegParams(rateControl = RateControl.PercentOfSource(40))
        val json = EngineJson.encodeToString(FfmpegParams.serializer(), p)
        assertTrue(json, json.contains("\"type\":\"bitrate_percent\""))
        assertEquals(p, EngineJson.decodeFromString(FfmpegParams.serializer(), json))
    }

    @Test
    fun unknownKeysAndMissingFieldsTolerated() {
        val p = EngineJson.decodeFromString(FfmpegParams.serializer(), """{"fpsMax":30,"future":"x"}""")
        assertEquals(30, p.fpsMax)
        assertEquals(VideoCodec.H265, p.videoCodec)
    }

    @Test
    fun builtInsBuildValidCommands() {
        val src = SourceInfo(container = Container.MP4, width = 3840, height = 2160, fps = 60.0, videoCodec = "hevc", audioCodec = "aac")
        for (preset in BuiltInPresets.all) {
            val r = FfmpegCommandBuilder.build(preset.params, src, "in", "out", preset.export)
            assertTrue("${preset.name}: $r", r is BuildResult.Ok)
        }
    }
}
