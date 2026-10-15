package app.squeeze.automation

import android.content.Context
import android.content.Intent
import app.squeeze.data.AutoSettingsRepository
import app.squeeze.data.BatchEntity
import app.squeeze.data.FinishedBatch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Opt-in outgoing events so a routine can chain actions ("when compression finishes, notify me").
 * They carry no file names or paths, only counts and sizes.
 */
@Singleton
class AutomationEvents @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AutoSettingsRepository,
) {
    suspend fun batchStarted(batch: BatchEntity, items: Int) {
        if (!settings.automationEvents.first()) return
        context.sendBroadcast(
            Intent(AutomationApi.EVENT_BATCH_STARTED)
                .putExtra("batch_id", batch.id).putExtra("source", batch.source.name).putExtra("items", items),
        )
    }

    suspend fun batchFinished(finished: FinishedBatch, source: String?) {
        if (!settings.automationEvents.first()) return
        context.sendBroadcast(
            Intent(AutomationApi.EVENT_BATCH_FINISHED)
                .putExtra("batch_id", finished.batchId)
                .putExtra("source", source)
                .putExtra("status", finished.status.name)
                .putExtra("items", finished.summary.done)
                .putExtra("bytes_saved", finished.summary.savedBytes),
        )
    }
}
