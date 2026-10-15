package app.squeeze.auto

import app.squeeze.engine.BuiltInPresets
import app.squeeze.engine.ExportMode
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class AutoOrder { OLDEST_FIRST, LARGEST_FIRST }

/** Everything the user configures for auto compression. */
data class AutoConfig(
    val enabled: Boolean = false,
    val mode: ScheduleMode = ScheduleMode.WINDOWS,
    val windows: List<TimeWindow> = listOf(TimeWindow(DayOfWeek.entries.toSet(), LocalTime.of(1, 0), LocalTime.of(6, 0))),
    val tiers: List<AgeTier> = listOf(
        AgeTier(30, BuiltInPresets.BALANCED_ID),
        AgeTier(365, BuiltInPresets.MAX_SAVINGS_ID),
    ),
    val requireCharging: Boolean = true,
    val minBatteryPercent: Int = 30,
    /** Videos smaller than this are left alone. */
    val minSizeMb: Int = 20,
    /** Videos already lighter than this (kbit/s, video and audio together) are left alone. 0 disables the gate. */
    val minBitrateKbps: Int = 3_000,
    /** Folder names or path prefixes (relative to the storage root) that are never touched. */
    val excludedFolders: List<String> = emptyList(),
    /** Saving copies is the safe default; replacing needs the "media management" special access to run unattended. */
    val exportMode: ExportMode = ExportMode.SAVE_COPIES,
    val order: AutoOrder = AutoOrder.OLDEST_FIRST,
) {
    val gates: AutoGates get() = AutoGates(requireCharging, minBatteryPercent)
    val minSizeBytes: Long get() = minSizeMb * 1024L * 1024L
}

/** The conditions that must hold while an unattended run is going. */
data class AutoGates(val requireCharging: Boolean, val minBatteryPercent: Int) {
    /** Why a run may not go on, or null when all is well. */
    fun problem(state: DeviceState): String? = when {
        requireCharging && !state.charging -> "not charging"
        state.batteryPercent < minBatteryPercent && !state.charging -> "battery below $minBatteryPercent%"
        else -> null
    }
}

data class DeviceState(val charging: Boolean, val batteryPercent: Int)

/** JSON form of [AutoConfig] for DataStore. Unknown keys and missing fields fall back to defaults. */
object AutoConfigJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class WindowDto(val days: List<Int>, val startMinute: Int, val endMinute: Int)

    @Serializable
    private data class Dto(
        val enabled: Boolean = false,
        val mode: ScheduleMode = ScheduleMode.WINDOWS,
        val windows: List<WindowDto>? = null,
        val tiers: List<AgeTier>? = null,
        val requireCharging: Boolean = true,
        val minBatteryPercent: Int = 30,
        val minSizeMb: Int = 20,
        val minBitrateKbps: Int = 3_000,
        val excludedFolders: List<String> = emptyList(),
        val exportMode: ExportMode = ExportMode.SAVE_COPIES,
        val order: AutoOrder = AutoOrder.OLDEST_FIRST,
    )

    fun encode(config: AutoConfig): String = json.encodeToString(
        Dto.serializer(),
        Dto(
            config.enabled, config.mode,
            config.windows.map { WindowDto(it.days.map(DayOfWeek::getValue).sorted(), it.start.toSecondOfDay() / 60, it.end.toSecondOfDay() / 60) },
            config.tiers, config.requireCharging, config.minBatteryPercent, config.minSizeMb, config.minBitrateKbps,
            config.excludedFolders, config.exportMode, config.order,
        ),
    )

    fun decode(text: String?): AutoConfig {
        val defaults = AutoConfig()
        if (text.isNullOrBlank()) return defaults
        val dto = runCatching { json.decodeFromString(Dto.serializer(), text) }.getOrNull() ?: return defaults
        return AutoConfig(
            enabled = dto.enabled,
            mode = dto.mode,
            windows = dto.windows?.map {
                TimeWindow(
                    it.days.mapNotNull { d -> runCatching { DayOfWeek.of(d) }.getOrNull() }.toSet(),
                    LocalTime.of((it.startMinute / 60).coerceIn(0, 23), it.startMinute % 60),
                    LocalTime.of((it.endMinute / 60).coerceIn(0, 23), it.endMinute % 60),
                )
            } ?: defaults.windows,
            tiers = dto.tiers ?: defaults.tiers,
            requireCharging = dto.requireCharging,
            minBatteryPercent = dto.minBatteryPercent.coerceIn(0, 100),
            minSizeMb = dto.minSizeMb.coerceAtLeast(0),
            minBitrateKbps = dto.minBitrateKbps.coerceAtLeast(0),
            excludedFolders = dto.excludedFolders,
            exportMode = dto.exportMode,
            order = dto.order,
        )
    }
}
