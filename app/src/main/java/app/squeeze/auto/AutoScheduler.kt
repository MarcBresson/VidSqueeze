package app.squeeze.auto

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

/** Keeps the periodic scan scheduled exactly when auto compression is on and not "external only". */
@Singleton
class AutoScheduler @Inject constructor(private val workManager: WorkManager) {

    fun apply(config: AutoConfig) {
        if (!config.enabled || config.mode == ScheduleMode.EXTERNAL_ONLY) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresCharging(config.requireCharging)
            .setRequiresBatteryNotLow(true)
            .build()
        // Hourly: cheap when nothing is due, and short windows are not missed by long periods.
        val request = PeriodicWorkRequestBuilder<AutoScanWorker>(SCAN_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val WORK_NAME = "auto-scan"
        const val SCAN_INTERVAL_MINUTES = 60L
    }
}
