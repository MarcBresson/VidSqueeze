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
            val r = FfmpegCommandBuilder.build(preset.params!!, src, "in", "out", preset.export)
            assertTrue("${preset.name}: $r", r is BuildResult.Ok)
        }
    }

    @Test
    fun oldPresetJsonDecodesAsVideoOnly() {
        // Written by 0.7.0: only `params`, no imageParams, no kind fields.
        val json = """{"id":"x","name":"Old","builtIn":true,"params":{"rateControl":{"type":"crf","value":26},
            |"videoCodec":"H265"},"export":{"mode":"ASK_WHEN_DONE"}}""".trimMargin()
        val p = EngineJson.decodeFromString(Preset.serializer(), json)
        assertEquals(setOf(MediaKind.VIDEO), p.kinds)
        assertTrue(p.isValid())
    }

    @Test
    fun dualPresetRoundTrips() {
        val dual = Preset(
            id = "d", name = "Dual",
            params = FfmpegParams(videoCodec = VideoCodec.H264),
            imageParams = ImageParams(format = ImageFormat.AVIF, avifCrf = 40),
        )
        val json = EngineJson.encodeToString(Preset.serializer(), dual)
        assertEquals(dual, EngineJson.decodeFromString(Preset.serializer(), json))
        assertEquals(setOf(MediaKind.VIDEO, MediaKind.IMAGE), dual.kinds)
    }

    @Test
    fun imageParamsRoundTrip() {
        val p = ImageParams(format = ImageFormat.JPEG_XL, jxl = JxlParams(effort = 9, quality = 100, modular = true, colorspace = JxlColorspace.SRGB))
        val json = EngineJson.encodeToString(ImageParams.serializer(), p)
        assertEquals(p, EngineJson.decodeFromString(ImageParams.serializer(), json))
    }

    @Test
    fun presetWithoutAnyKindIsInvalid() {
        val empty = Preset(id = "e", name = "E", params = null, imageParams = null)
        assertEquals(emptySet<MediaKind>(), empty.kinds)
        assertTrue(!empty.isValid())
    }
}
