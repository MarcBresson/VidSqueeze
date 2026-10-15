package app.squeeze.auto

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Wakes up periodically (while charging, when that gate is on), and when the off-peak window is
 * open queues what [AutoRunner] finds. The encoding itself runs in the normal queue worker.
 */
@HiltWorker
class AutoScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: AutoRunner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = runner.run(RunOptions(Trigger.SCHEDULED))
        Log.i(TAG, result.skippedReason?.let { "Skipped: $it" } ?: "Queued ${result.videos} videos (${result.bytes} bytes)")
        return Result.success()
    }

    private companion object {
        const val TAG = "AutoScanWorker"
    }
}
