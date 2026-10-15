package app.squeeze.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.squeeze.auto.AutoConfig
import app.squeeze.auto.AutoConfigJson
import app.squeeze.auto.AutoGates
import app.squeeze.auto.AutomationLogEntry
import app.squeeze.auto.AutomationLogJson
import app.squeeze.auto.DeviceState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Limits of the auto run in progress: it stops when the deadline passes or a gate no longer holds. */
data class RunLimits(val deadlineMs: Long?, val gates: AutoGates?) {
    fun stopReason(now: Long, device: DeviceState): String? = when {
        deadlineMs != null && now >= deadlineMs -> "the off-peak window ended"
        else -> gates?.problem(device)
    }
}

/** DataStore-backed auto compression settings, the run in progress, and the automation API's secret and log. */
@Singleton
class AutoSettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val configKey = stringPreferencesKey("auto_config")
    private val hasLimitsKey = booleanPreferencesKey("auto_run_active")
    private val deadlineKey = longPreferencesKey("auto_run_deadline")
    private val gateChargingKey = booleanPreferencesKey("auto_run_require_charging")
    private val gateBatteryKey = intPreferencesKey("auto_run_min_battery")
    private val automationEnabledKey = booleanPreferencesKey("automation_enabled")
    private val automationTokenKey = stringPreferencesKey("automation_token")
    private val automationEventsKey = booleanPreferencesKey("automation_events")
    private val automationLogKey = stringPreferencesKey("automation_log")

    val config: Flow<AutoConfig> = context.dataStore.data.map { AutoConfigJson.decode(it[configKey]) }

    suspend fun currentConfig(): AutoConfig = config.first()

    suspend fun update(change: (AutoConfig) -> AutoConfig) {
        context.dataStore.edit { it[configKey] = AutoConfigJson.encode(change(AutoConfigJson.decode(it[configKey]))) }
    }

    suspend fun runLimits(): RunLimits? = context.dataStore.data.first().let { prefs ->
        if (prefs[hasLimitsKey] != true) return null
        val gates = prefs[gateBatteryKey]?.let { AutoGates(prefs[gateChargingKey] ?: true, it) }
        RunLimits(prefs[deadlineKey]?.takeIf { it > 0 }, gates)
    }

    suspend fun setRunLimits(limits: RunLimits?) {
        context.dataStore.edit {
            if (limits == null) {
                it[hasLimitsKey] = false
            } else {
                it[hasLimitsKey] = true
                it[deadlineKey] = limits.deadlineMs ?: 0
                if (limits.gates == null) {
                    it.remove(gateBatteryKey)
                } else {
                    it[gateChargingKey] = limits.gates.requireCharging
                    it[gateBatteryKey] = limits.gates.minBatteryPercent
                }
            }
        }
    }

    // --- automation API ---

    val automationEnabled: Flow<Boolean> = context.dataStore.data.map { it[automationEnabledKey] == true }
    val automationToken: Flow<String?> = context.dataStore.data.map { it[automationTokenKey] }
    val automationEvents: Flow<Boolean> = context.dataStore.data.map { it[automationEventsKey] == true }
    val automationLog: Flow<List<AutomationLogEntry>> = context.dataStore.data.map { AutomationLogJson.decode(it[automationLogKey]) }

    /** Turning automation on creates the secret if there is none yet. */
    suspend fun setAutomationEnabled(enabled: Boolean, newToken: () -> String) {
        context.dataStore.edit {
            it[automationEnabledKey] = enabled
            if (enabled && it[automationTokenKey].isNullOrEmpty()) it[automationTokenKey] = newToken()
        }
    }

    suspend fun regenerateToken(newToken: String) {
        context.dataStore.edit { it[automationTokenKey] = newToken }
    }

    suspend fun setAutomationEvents(enabled: Boolean) {
        context.dataStore.edit { it[automationEventsKey] = enabled }
    }

    suspend fun appendLog(entry: AutomationLogEntry) {
        context.dataStore.edit { it[automationLogKey] = AutomationLogJson.encode(AutomationLogJson.append(AutomationLogJson.decode(it[automationLogKey]), entry)) }
    }

    suspend fun clearLog() {
        context.dataStore.edit { it.remove(automationLogKey) }
    }
}
