package app.squeeze.work

import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.squeeze.data.BatchSource
import app.squeeze.data.QueueRepository
import app.squeeze.data.SourceImporter
import app.squeeze.di.ApplicationScope
import app.squeeze.engine.Preset
import app.squeeze.finalize.BatchCompletion
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the UI and the entry points (share, picker) use to drive the compression queue. */
@Singleton
class QueueManager @Inject constructor(
    private val repository: QueueRepository,
    private val importer: SourceImporter,
    private val workManager: WorkManager,
    private val current: CurrentJob,
    private val completion: BatchCompletion,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Queues [uris] as one batch and makes sure the worker is running. Returns the batch id. */
    suspend fun enqueue(uris: List<Uri>, preset: Preset, source: BatchSource): Long {
        val sources = importer.resolve(uris)
        val batchId = repository.enqueue(source, preset, sources)
        ensureRunning()
        return batchId
    }

    /**
     * Like [enqueue], but survives the caller going away (the share receiver finishes at once and
     * copying a big video takes a while). [onResult] runs on a background thread.
     */
    fun enqueueInBackground(uris: List<Uri>, preset: Preset, source: BatchSource, onResult: (Result<Long>) -> Unit = {}) {
        scope.launch {
            val result = runCatching { enqueue(uris, preset, source) }
            result.exceptionOrNull()?.let { Log.e(TAG, "Could not enqueue $uris", it) }
            onResult(result)
        }
    }

    fun ensureRunning() {
        val request = OneTimeWorkRequestBuilder<CompressionWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** True while the worker is running or about to run. */
    fun observeWorkerActive(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
            infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        }

    /** Stops the worker; the running item goes back to the queue and restarts from scratch on resume. */
    fun pause() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    fun resume() = ensureRunning()

    fun cancelCurrent(onDone: () -> Unit = {}) {
        scope.launch {
            try {
                current.requestCancel()
            } finally {
                onDone()
            }
        }
    }

    fun cancelAll(onDone: () -> Unit = {}) {
        scope.launch {
            try {
                val finished = repository.cancelQueued()
                current.requestCancel()
                finished.forEach { completion.onBatchFinished(it) }
            } finally {
                onDone()
            }
        }
    }

    /** Stops auto compression: its waiting items are cancelled and, if it is encoding right now, that item too. */
    fun cancelAuto(onDone: () -> Unit = {}) {
        scope.launch {
            try {
                val running = current.itemId?.let { repository.isAutoItem(it) } == true
                val finished = repository.cancelQueuedAuto()
                if (running) current.requestCancel()
                finished.forEach { completion.onBatchFinished(it) }
            } finally {
                onDone()
            }
        }
    }

    /** Removes a waiting item, or skips it when it is the one being encoded. */
    fun remove(itemId: Long) {
        scope.launch {
            if (current.itemId == itemId) {
                current.requestCancel()
            } else {
                repository.cancelQueued(itemId)?.let { completion.onBatchFinished(it) }
            }
        }
    }

    companion object {
        const val WORK_NAME = "compression-queue"
        private const val TAG = "QueueManager"
        private const val RETRY_DELAY_SECONDS = 30L
    }
}
