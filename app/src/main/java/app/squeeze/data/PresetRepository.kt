package app.squeeze.data

import app.squeeze.engine.BuiltInPresets
import app.squeeze.engine.EngineJson
import app.squeeze.engine.ExportOptions
import app.squeeze.engine.FfmpegParams
import app.squeeze.engine.ImageParams
import app.squeeze.engine.Preset
import app.squeeze.presets.PresetTransfer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Built-in presets (from code) followed by the user's own (from Room). */
@Singleton
class PresetRepository @Inject constructor(private val dao: PresetDao) {
    internal var clock: () -> Long = System::currentTimeMillis
    internal var newId: () -> String = { "user.${UUID.randomUUID()}" }

    fun observeAll(): Flow<List<Preset>> = dao.observeAll().map { BuiltInPresets.all + it.mapNotNull(::toPreset) }

    suspend fun all(): List<Preset> = BuiltInPresets.all + dao.all().mapNotNull(::toPreset)

    suspend fun get(id: String?): Preset? {
        if (id == null) return null
        BuiltInPresets.all.firstOrNull { it.id == id }?.let { return it }
        return dao.get(id)?.let(::toPreset)
    }

    /** [id] if it still exists, otherwise the built-in Share preset. */
    suspend fun getOrDefault(id: String?): Preset = get(id) ?: BuiltInPresets.share

    /** Stores a new user preset; the name is made unique among all presets. */
    suspend fun create(name: String, params: FfmpegParams?, imageParams: ImageParams?, export: ExportOptions): Preset {
        require(params != null || imageParams != null) { "A preset must handle videos or photos" }
        val preset = Preset(
            id = newId(),
            name = uniqueName(name, excludeId = null),
            builtIn = false,
            params = params,
            imageParams = imageParams,
            export = export,
        )
        dao.upsert(toEntity(preset, createdAt = clock()))
        return preset
    }

    /** Overwrites the settings (and name) of a user preset. Built-in presets cannot be changed. */
    suspend fun update(preset: Preset): Boolean {
        if (BuiltInPresets.all.any { it.id == preset.id }) return false
        val existing = dao.get(preset.id) ?: return false
        dao.upsert(toEntity(preset.copy(name = uniqueName(preset.name, preset.id)), existing.createdAt))
        return true
    }

    suspend fun rename(id: String, name: String): Boolean {
        val preset = get(id)?.takeIf { !it.builtIn } ?: return false
        return update(preset.copy(name = name))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun duplicate(id: String): Preset? {
        val source = get(id) ?: return null
        return create("${source.name} copy", source.params, source.imageParams, source.export)
    }

    suspend fun importAll(presets: List<Preset>) {
        val now = clock()
        dao.upsertAll(presets.mapIndexed { i, p -> toEntity(p.copy(builtIn = false), now + i) })
    }

    private suspend fun uniqueName(name: String, excludeId: String?): String {
        val taken = all().filter { it.id != excludeId }.map { it.name }
        return PresetTransfer.uniqueName(name.trim().ifEmpty { "Preset" }, taken)
    }

    private fun toEntity(p: Preset, createdAt: Long) = PresetEntity(
        id = p.id,
        name = p.name,
        paramsJson = p.params?.let { EngineJson.encodeToString(it) } ?: "{}",
        imageParamsJson = p.imageParams?.let { EngineJson.encodeToString(it) },
        exportJson = EngineJson.encodeToString(p.export),
        createdAt = createdAt,
        updatedAt = clock(),
    )

    private fun toPreset(e: PresetEntity): Preset? = try {
        val params = if (e.paramsJson == "{}") null else EngineJson.decodeFromString<FfmpegParams>(e.paramsJson)
        val imageParams = e.imageParamsJson?.let { EngineJson.decodeFromString<ImageParams>(it) }
        Preset(
            id = e.id,
            name = e.name,
            builtIn = false,
            params = params,
            imageParams = imageParams,
            export = EngineJson.decodeFromString<ExportOptions>(e.exportJson),
        ).takeIf { it.isValid() }
    } catch (ex: Exception) {
        null // an unreadable row must not take the whole list down
    }
}
