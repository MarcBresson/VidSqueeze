package app.squeeze.engine

/** A plain-language explanation of one preset parameter. */
data class HelpEntry(
    val title: String,
    /** What the knob does, in one or two sentences a non-technical user understands. */
    val whatItDoes: String,
    /** Concrete recommendation: which option to pick for which situation. */
    val whichToPick: String,
)

enum class PresetHelpKey {
    VIDEO_CODEC, ENCODER, RATE_MODE, RESOLUTION, FPS, AUDIO_CODEC, AUDIO_BITRATE,
    CONTAINER, CUSTOM_ARGS,
    PHOTO_FORMAT, PHOTO_QUALITY, PHOTO_RESOLUTION, JPEG_CHROMA, PNG_DEPTH, WEBP_LOSSLESS, JXL_EFFORT, JXL_MODULAR,
    EXPORT_MODE, NAME_TEMPLATE, KEEP_EXIF, KEEP_LOCATION,
}

/**
 * Single source of truth for in-app parameter help. UI shows entries via HelpIcon.
 * Known limitation: the copy is hardcoded English (not localizable yet), matching the existing
 * hardcoded labels in [ParamOptions]; the [PresetHelpKey] partition makes a later strings.xml
 * migration mechanical.
 */
object PresetHelp {
    /** Common intro shown in every codec dialog, whatever codec is currently selected. */
    private const val CODEC_INTRO =
        "A video codec is the format the picture is compressed into — it decides how much space a " +
            "video takes at the same visual quality. Chips marked \"HW\" use your phone's built-in " +
            "encoder chip: faster and easier on the battery, but files come out somewhat bigger than " +
            "with the software encoder for the same look. "

    val videoCodec: Map<VideoCodec, HelpEntry> = mapOf(
        VideoCodec.COPY to HelpEntry(
            "Copy video",
            CODEC_INTRO + "\"Copy\" skips re-compression: the picture is left untouched, so no space is " +
                "saved but there is zero quality risk.",
            "Right choice when the file is already well compressed.",
        ),
        VideoCodec.H264 to HelpEntry(
            "H.264",
            CODEC_INTRO + "H.264 is the universal standard — it plays on every device, app and TV made " +
                "in the last 15 years. For the same visual quality its files are roughly twice as big " +
                "as H.265's.",
            "Pick it when compatibility matters more than size, e.g. sending to someone with an old device.",
        ),
        VideoCodec.H265 to HelpEntry(
            "H.265 (HEVC)",
            CODEC_INTRO + "H.265 (also called HEVC) is today's best balance: at the same visual quality " +
                "its files are about half the size of H.264's, and it plays on any recent phone, TV or " +
                "computer (roughly 2016+).",
            "The default for archiving your own videos.",
        ),
        VideoCodec.VP9 to HelpEntry(
            "VP9",
            CODEC_INTRO + "VP9 compresses about as well as H.265 — roughly half the size of H.264 at the " +
                "same look — but is slower to compress.",
            "Mainly useful for web embedding.",
        ),
        VideoCodec.AV1 to HelpEntry(
            "AV1",
            CODEC_INTRO + "AV1 is the newest standard: the smallest files of all (a bit smaller than " +
                "H.265) and royalty-free, but compression is several times slower and older devices " +
                "can't hardware-decode it.",
            "Best when size is everything and time isn't.",
        ),
    )

    /** Common intro shown in every rate-control dialog, whatever mode is currently selected. */
    private const val RATE_INTRO =
        "Rate control decides how many bits each second of video gets. Quality modes let the encoder " +
            "spend whatever the chosen look needs — almost always the right approach; fixed bitrate " +
            "modes pin the size per second regardless of the picture. "

    val rateMode: Map<RateMode, HelpEntry> = mapOf(
        RateMode.CRF to HelpEntry(
            "Quality (CRF)",
            RATE_INTRO + "In CRF mode you pick how good it should look (lower number = better), and the " +
                "encoder uses exactly the bits needed.",
            "For H.265, 26 looks about like what most streaming services deliver; 28 is smaller and still " +
                "fine. Below ~20 you're paying file size for quality you can't see.",
        ),
        RateMode.QUALITY to HelpEntry(
            "Constant quality (hardware)",
            RATE_INTRO + "Constant quality is the hardware chip's version of the same idea: you pick a " +
                "quality level and the chip spends what it needs. It is faster, but files come out " +
                "somewhat bigger than the software encoder's quality mode.",
            "Use it when you picked a hardware (HW) codec chip and want speed over size.",
        ),
        RateMode.BITRATE to HelpEntry(
            "Bitrate",
            RATE_INTRO + "In bitrate mode you fix the size per second regardless of what's on screen: " +
                "static scenes waste bits, action scenes run out.",
            "Use only when a target file size is hard-required (e.g. an upload cap).",
        ),
        RateMode.PERCENT to HelpEntry(
            "Percent of source",
            RATE_INTRO + "In percent mode each video keeps a % of its own bitrate — big 4K sources " +
                "shrink a lot, small ones barely.",
            "Handy for batch-shrinking a mixed library.",
        ),
    )

    /** The keys of every knob the video part of the form shows, in form order. */
    val keys: List<PresetHelpKey> = listOf(
        PresetHelpKey.VIDEO_CODEC, PresetHelpKey.ENCODER, PresetHelpKey.RATE_MODE,
        PresetHelpKey.RESOLUTION, PresetHelpKey.FPS, PresetHelpKey.AUDIO_CODEC,
        PresetHelpKey.AUDIO_BITRATE, PresetHelpKey.CONTAINER, PresetHelpKey.CUSTOM_ARGS,
    )

    /** Every entry in the catalog, whatever knob it belongs to. */
    val all: List<HelpEntry>
        get() = videoCodec.values + rateMode.values + listOf(
            encoder, resolution, fps, audioCodec, audioBitrate, container, customArgs,
            photoFormat, photoResolution, jpegChroma, pngDepth, webpLossless, jxlEffort, jxlModular,
            nameTemplate, keepExif, keepLocation,
        ) + photoQuality(ImageFormat.entries.first()) + exportMode(ExportMode.entries.first())

    val encoder = HelpEntry(
        "Encoder speed",
        "How hard the encoder thinks. Slower = smaller file at the same quality. At 'medium' vs 'slow' " +
            "the visual result is identical; only the file size differs (typically 5-15%).",
        "Slow for overnight archive jobs; Medium (default) otherwise; faster settings only when " +
            "compressing something you need right now. Never choose a faster setting \"to save battery\" " +
            "— the size penalty is permanent, the time saved is minutes.",
    )

    val resolution = HelpEntry(
        "Resolution",
        "Caps the video's height/width. 1080p = Full HD (like most YouTube), 720p = HD. Lower resolution " +
            "always saves a lot of space and is limited to the short side, so a portrait video is never stretched.",
        "1080p for everything you watch on a phone, tablet or laptop. 720p for clips you'll send over " +
            "messaging apps — most WhatsApp/Telegram videos are 720p or lower. Keep 4K (2160) only when " +
            "you'll watch on a large TV or might want to crop/zoom later: a 4K file is ~4x the pixels of " +
            "1080p, but on a 6-inch phone screen you physically cannot see the difference. 4K is also " +
            "worth keeping for footage you may edit later, where the extra detail survives cropping.",
    )

    val fps = HelpEntry(
        "Frame rate",
        "Caps frames per second. Phone cameras record 30 or 60 fps; cinema movies are 24 fps — and you " +
            "never felt that. Cutting 60→24 throws away 60% of the frames, and with them a huge part of " +
            "the file size; motion still looks natural, just slightly less \"video-game smooth\".",
        "24 fps for any movie-like or archival footage — biggest savings, no perceived loss for normal " +
            "content. 30 fps is a safe middle. Keep 60 fps only for sports, action footage or gaming " +
            "clips, where fast motion genuinely benefits.",
    )

    val audioCodec = HelpEntry(
        "Audio codec",
        "How the sound is re-encoded.",
        "Copy keeps the original sound untouched. AAC is the universal choice, plays everywhere — " +
            "default. Opus is smaller and better-sounding at low bitrates, but some players (older cars, " +
            "TVs) can't decode it — safe inside MKV/WebM only. MP3 is for ancient device compatibility.",
    )

    val audioBitrate = HelpEntry(
        "Audio bitrate",
        "How many bits per second the sound gets. Almost all music and video streaming sits between 128 " +
            "and 256 kbit/s AAC — YouTube music videos top out around 128-160 kbit/s, and most people " +
            "can't hear a difference above that on phone speakers or earbuds.",
        "128 kbit/s — matches what YouTube and Spotify serve and is transparent for dialogue and music " +
            "on typical playback. 192-256 only for source audio you know is studio-grade and good " +
            "headphones. Below ~96 kbit/s AAC you start hearing muffled, \"underwater\" sound — that's " +
            "the real degradation point, not 128.",
    )

    val container = HelpEntry(
        "Container",
        "The file wrapper that holds the video and audio streams.",
        "Original keeps whatever the source had; it falls back with a notice if the new codecs don't " +
            "fit. MP4 plays on literally everything — the safe pick. MKV holds any codec and multiple " +
            "audio/subtitle tracks; great for archiving, but some smart TVs and messaging apps reject " +
            "it. WebM is the web/AV1 pairing.",
    )

    val customArgs = HelpEntry(
        "Custom arguments",
        "Raw commands appended for power users.",
        "Leave empty — anything here overrides the safe defaults above.",
    )

    // -----------------------------------------------------------------------------------------
    // Photo parameters
    // -----------------------------------------------------------------------------------------

    /** Common intro shown in every photo-quality dialog, whatever format is currently selected. */
    private const val PHOTO_QUALITY_INTRO =
        "Photo quality sets how heavily the picture is compressed: the smaller the file, the more " +
            "detail is thrown away. Which direction the number moves depends on the format. "

    fun photoQuality(format: ImageFormat): HelpEntry = when (format) {
        ImageFormat.JPEG -> jpegQuality
        ImageFormat.WEBP -> HelpEntry(
            "WebP quality",
            PHOTO_QUALITY_INTRO + "For WebP a higher number looks better and costs more space. WebP " +
                "itself is about 25-30% smaller than JPEG at the same look.",
            "85 is a good everyday pick for camera photos; below ~75 artefacts appear around sharp edges. " +
                "The lossless switch keeps every pixel exactly — good for screenshots and graphics, big " +
                "for camera photos.",
        )
        ImageFormat.AVIF -> HelpEntry(
            "AVIF quality (CRF)",
            PHOTO_QUALITY_INTRO + "For AVIF the scale is reversed: a lower CRF number looks better and " +
                "costs more space. AVIF itself is about 50% smaller than JPEG at the same look — slow " +
                "to compress but worth it for archives.",
            "30 is a good default; 40+ starts visibly smearing fine detail.",
        )
        ImageFormat.PNG -> HelpEntry(
            "PNG (lossless)",
            PHOTO_QUALITY_INTRO + "PNG always keeps every pixel exactly, so there is no quality knob — " +
                "its settings only affect file size and save time.",
            "Great for screenshots and graphics, huge for camera photos — pick JPEG, WebP or AVIF for those.",
        )
        ImageFormat.JPEG_XL -> HelpEntry(
            "JPEG XL quality",
            PHOTO_QUALITY_INTRO + "For JPEG XL a higher number looks better and costs more space: " +
                "100 = mathematically lossless, 90 = visually lossless (libjxl's own default). JPEG XL " +
                "itself matches AVIF's savings and can also losslessly re-shrink existing JPEGs, but " +
                "app support is limited today.",
            "90 for archives that should look identical to the original; below 80 treat as lossy.",
        )
    }

    private val jpegQuality = HelpEntry(
        "JPEG quality",
        PHOTO_QUALITY_INTRO + "For JPEG a higher number looks better and costs more space.",
        "85 = visually indistinguishable from the original for camera photos; below ~75 artefacts appear " +
            "around sharp edges; above 95 pays size for nothing.",
    )

    val photoFormat = HelpEntry(
        "Photo format",
        "The file type the compressed photo is saved as.",
        "JPEG is universal but the largest at a given quality — right choice for sharing/printing. " +
            "WebP is about 25-30% smaller than JPEG, supported everywhere modern (Android, iOS 14+, all " +
            "browsers). AVIF is about 50% smaller than JPEG at the same look; compression is slow but " +
            "worth it for archives. PNG is lossless but huge for camera photos — meant for screenshots " +
            "and graphics. JPEG XL matches AVIF's savings, can also losslessly re-shrink existing JPEGs; " +
            "limited app support today.",
    )

    val photoResolution = HelpEntry(
        "Photo resolution",
        "Caps the photo's short side, never upscaling and never stretching.",
        "For photos you print, keep full size; for photos you only view on a phone, 1440-2160 on the " +
            "short side is plenty and saves most of the space.",
    )

    val jpegChroma = HelpEntry(
        "JPEG 4:4:4 chroma",
        "Keeps full colour detail instead of the default 4:2:0 subsampling, which halves colour resolution.",
        "On for screenshots, text and sharp graphics; off (default) for camera photos, where you can't see the difference.",
    )

    val pngDepth = HelpEntry(
        "PNG bit depth",
        "How many shades each colour channel keeps.",
        "16-bit only for professional editing pipelines; 8 for everything else.",
    )

    val webpLossless = HelpEntry(
        "WebP lossless",
        "Exact pixels preserved — quality is ignored.",
        "Big for photos, fine for screenshots/graphics.",
    )

    val jxlEffort = HelpEntry(
        "JPEG XL effort",
        "1-9, like the encoder-speed knob: higher = smaller file, slower.",
        "7 is the libjxl default — leave it unless an archive job can run overnight, then try 8-9.",
    )

    val jxlModular = HelpEntry(
        "JPEG XL modular",
        "Chooses the encoder's internal mode.",
        "On = best for screenshots, flat graphics and true lossless; off (VarDCT) = best for camera photos.",
    )

    // -----------------------------------------------------------------------------------------
    // Export parameters
    // -----------------------------------------------------------------------------------------

    fun exportMode(mode: ExportMode): HelpEntry = when (mode) {
        ExportMode.ASK_WHEN_DONE -> HelpEntry(
            "Ask when done",
            "The app asks for each batch what to do with the files.",
            "The safe default.",
        )
        ExportMode.SAVE_COPIES -> HelpEntry(
            "Save copies",
            "Originals are untouched; compressed versions are saved alongside.",
            "Needs free space for both.",
        )
        ExportMode.REPLACE_ORIGINALS -> HelpEntry(
            "Replace originals",
            "The original file is deleted after compressing — make sure you have a backup.",
            "Saves the most space, but the original is deleted — only use it with a backup you trust (cloud, NAS, second drive).",
        )
    }

    val nameTemplate = HelpEntry(
        "Name template",
        "How compressed files are named.",
        "{original_file_name} and {output_format} are filled in automatically; tap the chips to insert them.",
    )

    val keepExif = HelpEntry(
        "Keep Exif",
        "The photo's metadata: camera model, date, settings.",
        "Harmless and useful for organizing — keep unless you're publishing publicly.",
    )

    val keepLocation = HelpEntry(
        "Keep location",
        "GPS coordinates embedded in photos/videos.",
        "Strip this (off) before posting anything publicly — it reveals where you live. Keeping it is " +
            "fine for a private archive.",
    )

    /** Photo and export knobs in form order. */
    val photoAndExportKeys: List<PresetHelpKey> = listOf(
        PresetHelpKey.PHOTO_FORMAT, PresetHelpKey.PHOTO_QUALITY, PresetHelpKey.PHOTO_RESOLUTION,
        PresetHelpKey.JPEG_CHROMA, PresetHelpKey.PNG_DEPTH, PresetHelpKey.WEBP_LOSSLESS,
        PresetHelpKey.JXL_EFFORT, PresetHelpKey.JXL_MODULAR,
        PresetHelpKey.EXPORT_MODE, PresetHelpKey.NAME_TEMPLATE, PresetHelpKey.KEEP_EXIF,
        PresetHelpKey.KEEP_LOCATION,
    )
}
