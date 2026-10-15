package app.squeeze.data

import app.squeeze.engine.EngineJson
import app.squeeze.engine.Preset
import kotlinx.serialization.decodeFromString

/** The preset as it was when the batch was queued, or null if the stored JSON is unreadable. */
fun BatchEntity.decodePreset(): Preset? =
    try {
        EngineJson.decodeFromString<Preset>(presetSnapshotJson)
    } catch (e: Exception) {
        null
    }
