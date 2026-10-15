package app.squeeze

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.squeeze.auto.AutoScheduler
import app.squeeze.automation.AutomationShortcuts
import app.squeeze.data.AutoSettingsRepository
import app.squeeze.data.Retention
import app.squeeze.data.SettingsRepository
import app.squeeze.work.ItemProcessor
import app.squeeze.di.ApplicationScope
import app.squeeze.finalize.Finalizer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class SqueezeApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var finalizer: Finalizer
    @Inject lateinit var autoScheduler: AutoScheduler
    @Inject lateinit var shortcuts: AutomationShortcuts
    @Inject lateinit var autoSettings: AutoSettingsRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var processor: ItemProcessor
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Compressed results nobody acted on are not kept forever (Settings: after 3 to 30 days, or never).
        scope.launch {
            Retention.maxAgeMs(settings.stagedRetentionDays.first())?.let { finalizer.discardOlderThan(it) }
            finalizer.pruneEmptyStagingDirs(processor.stagingDir)
        }
        // The scan is scheduled whenever auto compression is on, and only then.
        scope.launch {
            autoSettings.config
                .map { Triple(it.enabled, it.mode, it.requireCharging) }
                .distinctUntilChanged()
                .collect { autoScheduler.apply(autoSettings.currentConfig()) }
        }
        scope.launch {
            combine(autoSettings.automationEnabled, autoSettings.automationToken, ::Pair)
                .distinctUntilChanged()
                .collect { (enabled, token) -> shortcuts.publish(enabled, token) }
        }
    }
}
