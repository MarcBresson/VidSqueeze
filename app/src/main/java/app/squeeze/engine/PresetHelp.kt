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
    val videoCodec: Map<VideoCodec, HelpEntry> = mapOf(
        VideoCodec.COPY to HelpEntry(
            "Copy video",
            "Leaves the picture untouched — no space is saved but there is zero quality risk.",
            "Right choice when the file is already well compressed.",
        ),
        VideoCodec.H264 to HelpEntry(
            "H.264",
            "The universal standard — plays on every device, app and TV made in the last 15 years. " +
                "Files are roughly 2x bigger than H.265 for the same look.",
            "Pick it when compatibility matters more than size, e.g. sending to someone with an old device.",
        ),
        VideoCodec.H265 to HelpEntry(
            "H.265 (HEVC)",
            "Today's best balance: about half the size of H.264 at the same quality, and it plays on " +
                "any recent phone, TV or computer (roughly 2016+).",
            "The default for archiving your own videos.",
        ),
        VideoCodec.VP9 to HelpEntry(
            "VP9",
            "Same size savings as H.265 but slower to compress.",
            "Mainly useful for web embedding.",
        ),
        VideoCodec.AV1 to HelpEntry(
            "AV1",
            "The newest standard — smallest files of all and royalty-free, but compression is several " +
                "times slower and older devices can't hardware-decode it.",
            "Best when size is everything and time isn't.",
        ),
    )

    val rateMode: Map<RateMode, HelpEntry> = mapOf(
        RateMode.CRF to HelpEntry(
            "Quality (CRF)",
            "You pick how good it should look (lower number = better), and the encoder uses exactly the " +
                "bits needed. Almost always the right mode.",
            "For H.265, 26 looks about like what most streaming services deliver; 28 is smaller and still " +
                "fine. Below ~20 you're paying file size for quality you can't see.",
        ),
        RateMode.QUALITY to HelpEntry(
            "Constant quality (hardware)",
            "Same idea as quality mode, but for your device's built-in encoder — faster, but files come " +
                "out somewhat bigger.",
            "Use it when you picked a hardware codec chip and want speed over size.",
        ),
        RateMode.BITRATE to HelpEntry(
            "Bitrate",
            "You fix the size per second regardless of what's on screen: static scenes waste bits, action " +
                "scenes run out.",
            "Use only when a target file size is hard-required (e.g. an upload cap).",
        ),
        RateMode.PERCENT to HelpEntry(
            "Percent of source",
            "Each video keeps a % of its own bitrate — big 4K sources shrink a lot, small ones barely.",
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
        )

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
}
