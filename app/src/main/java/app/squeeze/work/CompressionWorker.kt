package app.squeeze.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.squeeze.auto.DeviceStateReader
import app.squeeze.automation.AutomationEvents
import app.squeeze.data.AutoSettingsRepository
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.BatchSource
import app.squeeze.data.ItemResult
import app.squeeze.data.QueueRepository
import app.squeeze.data.decodePreset
import app.squeeze.engine.Preset
import app.squeeze.engine.Progress
import app.squeeze.finalize.BatchCompletion
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drains the queue one file at a time under a foreground notification. Everything it knows lives
 * in Room, so a killed or stopped worker simply resumes from the table.
 */
@HiltWorker
class CompressionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: QueueRepository,
    private val processor: ItemProcessor,
    private val notifications: NotificationHelper,
    private val completion: BatchCompletion,
    private val current: CurrentJob,
    private val autoSettings: AutoSettingsRepository,
    private val device: DeviceStateReader,
    private val events: AutomationEvents,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(QueueProgress(1, 1, null, null, null, 0))

    override suspend fun doWork(): Result {
        notifications.createChannels()
        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            // Started from the background without an exemption: keep going as ordinary work.
            Log.w(TAG, "Could not start the foreground service", e)
        }
        repository.recoverInterrupted()

        var finished = 0
        var savedBytes = 0L
        while (true) {
            val item = repository.nextQueued() ?: break
            val batch = repository.batch(item.batchId)
            val unattended = batch?.source == BatchSource.AUTO
            if (unattended) {
                val reason = autoStopReason()
                if (reason != null) {
                    // Window over, charger gone, ...: what is left is picked up again by the next scan.
                    Log.i(TAG, "Stopping auto compression: $reason")
                    repository.cancelQueuedAuto().forEach { completion.onBatchFinished(it) }
                    continue
                }
            }
            if (batch != null && batch.startedAt == null) events.batchStarted(batch, repository.itemCount(batch.id))
            val result = batch?.decodePreset()?.let { preset ->
                runItem(item, preset, index = finished + 1, total = finished + repository.activeCount(), savedBytes = savedBytes, unattended = unattended)
            } ?: ItemResult.Failed("The batch or its preset could not be read.", null).also {
                Log.e(TAG, "Unreadable preset snapshot for batch ${item.batchId}")
            }

            if (result is ItemResult.Done) savedBytes += (item.originalSize - result.outputSize).coerceAtLeast(0)
            finished++
            deleteImportedSource(item)
            repository.complete(item.id, result)?.let { completion.onBatchFinished(it) }
        }
        return Result.success()
    }

    /** Why the unattended run must end now, or null to go on. */
    private suspend fun autoStopReason(): String? =
        autoSettings.runLimits()?.stopReason(System.currentTimeMillis(), device.read())

    private suspend fun watchLimits() {
        while (true) {
            delay(WATCH_INTERVAL_MS)
            if (autoStopReason() != null) {
                current.requestCancel()
                break
            }
        }
    }

    /** Throws [CancellationException] when the worker is stopped; the item is back in the queue by then. */
    private suspend fun runItem(item: BatchItemEntity, preset: Preset, index: Int, total: Int, savedBytes: Long, unattended: Boolean): ItemResult {
        repository.markRunning(item)
        current.begin(item.id)
        val dbThrottle = ProgressThrottle()
        val notificationThrottle = ProgressThrottle()
        var durationMs = item.durationMs
        notifications.updateProgress(QueueProgress(index, total, item.displayName, null, null, savedBytes))
        try {
            val outcome = coroutineScope {
                // An unattended run also ends in the middle of a file: unplugging or the end of the window.
                val watcher = if (unattended) launch { watchLimits() } else null
                try {
                    processor.process(
                        item, preset,
                        onProbed = { durationMs = it; repository.setProbed(item.id, it) },
                        onProgress = { p ->
                            if (notificationThrottle.tryAcquire()) {
                                val eta = Progress.etaMs(p.processedMs, durationMs, p.speed)
                                notifications.updateProgress(QueueProgress(index, total, item.displayName, p.fraction, eta, savedBytes))
                            }
                            // The callback runs on FFmpegKit's thread; the write is asynchronous.
                            if (dbThrottle.tryAcquire()) launch { repository.setProgress(item.id, p.fraction) }
                        },
                    )
                } finally {
                    watcher?.cancel()
                }
            }
            return when (outcome) {
                is ItemOutcome.Done -> ItemResult.Done(outcome.output.absolutePath, outcome.outputSize, outcome.command, outcome.durationMs)
                is ItemOutcome.NoGain -> ItemResult.NoGain(outcome.outputSize, outcome.command, outcome.durationMs)
                is ItemOutcome.Failed -> ItemResult.Failed(outcome.message, outcome.command)
                ItemOutcome.Cancelled -> ItemResult.Cancelled
            }
        } catch (e: CancellationException) {
            // Paused, stopped by the system or timed out: not the item's fault, run it again later.
            withContext(NonCancellable) { repository.recoverInterrupted() }
            throw e
        } finally {
            current.end()
        }
    }

    private fun deleteImportedSource(item: BatchItemEntity) {
        if (item.isImported) Uri.parse(item.sourceUri).path?.let { File(it).delete() }
    }

    private companion object {
        const val TAG = "CompressionWorker"
        const val WATCH_INTERVAL_MS = 15_000L
    }
}
