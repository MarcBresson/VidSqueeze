package app.squeeze.engine

import kotlinx.serialization.json.Json

/** Shared JSON configuration for preset storage and import/export. */
val EngineJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

object BuiltInPresets {
    const val SHARE_ID = "builtin.share"
    const val BALANCED_ID = "builtin.balanced"
    const val MAX_SAVINGS_ID = "builtin.max_savings"
    const val LOSSLESS_ISH_ID = "builtin.lossless_ish"

    val share = Preset(
        id = SHARE_ID,
        name = "Share (WhatsApp-friendly)",
        builtIn = true,
        params = FfmpegParams(
            videoCodec = VideoCodec.H264,
            rateControl = RateControl.Crf(28),
            maxShortSide = 720,
            container = Container.MP4,
        ),
        export = ExportOptions(keepLocation = false),
    )

    val balanced = Preset(
        id = BALANCED_ID,
        name = "Balanced archive",
        builtIn = true,
        params = FfmpegParams(
            videoCodec = VideoCodec.H265,
            rateControl = RateControl.Crf(26),
            maxShortSide = 1080,
        ),
    )

    val maxSavings = Preset(
        id = MAX_SAVINGS_ID,
        name = "Max savings",
        builtIn = true,
        params = FfmpegParams(
            videoCodec = VideoCodec.H265,
            rateControl = RateControl.Crf(30),
            encoderPreset = EncoderPreset.SLOW,
            maxShortSide = 720,
            fpsMax = 24,
        ),
    )

    val losslessIsh = Preset(
        id = LOSSLESS_ISH_ID,
        name = "Lossless-ish",
        builtIn = true,
        params = FfmpegParams(
            videoCodec = VideoCodec.H265,
            rateControl = RateControl.Crf(20),
            encoderPreset = EncoderPreset.SLOW,
        ),
    )

    val all: List<Preset> = listOf(share, balanced, maxSavings, losslessIsh)
}
