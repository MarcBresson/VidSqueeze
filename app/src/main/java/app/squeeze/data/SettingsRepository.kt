package app.squeeze.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.squeeze.engine.BuiltInPresets
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.dataStore by preferencesDataStore(name = "settings")

/** How the app picks light or dark. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        /** Unknown or missing values fall back to following the system. */
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

/** How long compressed results nobody acted on stay in the app. [NEVER] keeps them until the user decides. */
object Retention {
    const val NEVER = 0
    const val DEFAULT_DAYS = 14
    val CHOICES = listOf(3, 7, DEFAULT_DAYS, 30, NEVER)

    /** Unusable stored values (negative, or not one of the choices) become the default. */
    fun normalize(days: Int?): Int = if (days != null && days in CHOICES) days else DEFAULT_DAYS

    /** The age in milliseconds after which a result is discarded, or null when results are kept. */
    fun maxAgeMs(days: Int): Long? = if (days <= NEVER) null else days * 24L * 60 * 60 * 1000
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val defaultPresetKey = stringPreferencesKey("default_preset_id")
    private val lastUsedPresetKey = stringPreferencesKey("last_used_preset_id")
    private val themeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val retentionKey = intPreferencesKey("staged_retention_days")
    private val onboardingKey = booleanPreferencesKey("onboarding_done")

    /** The preset the share flow starts from when "last used" is unknown. */
    val defaultPresetId: Flow<String> = context.dataStore.data.map { it[defaultPresetKey] ?: BuiltInPresets.SHARE_ID }

    val lastUsedPresetId: Flow<String?> = context.dataStore.data.map { it[lastUsedPresetKey] }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { ThemeMode.fromKey(it[themeKey]) }

    /** Wallpaper-based colours (Android 12+); off gives the fixed Material palette. */
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[dynamicColorKey] ?: true }

    /** Days an unclaimed result stays in the app, see [Retention]. */
    val stagedRetentionDays: Flow<Int> = context.dataStore.data.map { Retention.normalize(it[retentionKey]) }

    /** False until the first-run permissions screen was finished or skipped. */
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[onboardingKey] ?: false }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setDynamicColor(on: Boolean) {
        context.dataStore.edit { it[dynamicColorKey] = on }
    }

    suspend fun setStagedRetentionDays(days: Int) {
        context.dataStore.edit { it[retentionKey] = Retention.normalize(days) }
    }

    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[onboardingKey] = true }
    }

    suspend fun setDefaultPreset(id: String) {
        context.dataStore.edit { it[defaultPresetKey] = id }
    }

    suspend fun setLastUsedPreset(id: String) {
        context.dataStore.edit { it[lastUsedPresetKey] = id }
    }

    /** What the compression sheet opens with: last used, else the default. */
    suspend fun startPresetId(): String =
        context.dataStore.data.first().let { it[lastUsedPresetKey] ?: it[defaultPresetKey] ?: BuiltInPresets.SHARE_ID }
}
