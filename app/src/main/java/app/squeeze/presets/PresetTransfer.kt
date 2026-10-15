package app.squeeze.presets

import app.squeeze.engine.EngineJson
import app.squeeze.engine.Preset
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
private data class PresetBundle(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val presets: List<Preset> = emptyList(),
)

private const val FORMAT = "squeeze-presets"
private const val VERSION = 1

sealed interface ImportResult {
    data class Ok(val presets: List<Preset>) : ImportResult
    data class Invalid(val reason: String) : ImportResult
}

/** Preset import/export as a small versioned JSON file, shared through the system share sheet. */
object PresetTransfer {

    fun export(presets: List<Preset>): String =
        EngineJson.encodeToString(PresetBundle(presets = presets.map { it.copy(builtIn = false) }))

    /**
     * Parses [json] into presets ready to store: every preset gets a fresh id from [newId], is never
     * built in, and gets a name that does not clash with [existingNames] or another imported preset.
     */
    fun import(json: String, existingNames: Collection<String>, newId: () -> String): ImportResult {
        val bundle = try {
            EngineJson.decodeFromString<PresetBundle>(json)
        } catch (e: Exception) {
            return ImportResult.Invalid("This is not a Squeeze preset file.")
        }
        if (bundle.format != FORMAT) return ImportResult.Invalid("This is not a Squeeze preset file.")
        if (bundle.version > VERSION) return ImportResult.Invalid("This file was made by a newer version of Squeeze.")
        if (bundle.presets.isEmpty()) return ImportResult.Invalid("The file contains no presets.")

        val taken = existingNames.mapTo(mutableSetOf()) { it.lowercase() }
        val presets = bundle.presets.map { p ->
            val name = uniqueName(p.name.trim().ifEmpty { "Imported preset" }, taken)
            taken += name.lowercase()
            p.copy(id = newId(), name = name, builtIn = false)
        }
        return ImportResult.Ok(presets)
    }

    /** "Name", then "Name (2)", "Name (3)", ... until not in [taken] (case-insensitive). */
    fun uniqueName(name: String, taken: Collection<String>): String {
        val lower = taken.mapTo(mutableSetOf()) { it.lowercase() }
        if (name.lowercase() !in lower) return name
        var n = 2
        while ("$name ($n)".lowercase() in lower) n++
        return "$name ($n)"
    }
}
