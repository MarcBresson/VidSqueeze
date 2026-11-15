package app.squeeze.presets

import app.squeeze.engine.BuiltInPresets

/** One-line "best for" blurb shown under each built-in preset name. */
object BuiltInDescriptions {
    fun forId(id: String): String = when (id) {
        BuiltInPresets.SHARE_ID -> "Small files that play anywhere — best for sending videos to friends or messaging apps."
        BuiltInPresets.BALANCED_ID -> "Good quality at a much smaller size — the safe everyday choice."
        BuiltInPresets.MAX_SAVINGS_ID -> "Smallest files it gets, quality takes a back seat — for long videos you keep, not watch carefully."
        BuiltInPresets.LOSSLESS_ISH_ID -> "Looks the same as the original, still saves space — for videos you want to archive."
        else -> ""
    }
}
