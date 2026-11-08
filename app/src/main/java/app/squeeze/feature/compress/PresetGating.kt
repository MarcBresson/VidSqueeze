package app.squeeze.feature.compress

import app.squeeze.engine.MediaKind
import app.squeeze.engine.Preset

/** Pure gating logic for the preset picker; JVM-testable. */
internal fun presetUsable(preset: Preset, selectionKinds: Set<MediaKind>): Boolean =
    selectionKinds.isEmpty() || selectionKinds.all { it in preset.kinds }
