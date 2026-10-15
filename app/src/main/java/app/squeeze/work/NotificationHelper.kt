package app.squeeze.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import app.squeeze.MainActivity
import app.squeeze.R
import app.squeeze.data.BatchSummary
import app.squeeze.finalize.FinalizeAction
import app.squeeze.finalize.FinalizeActivity
import app.squeeze.finalize.FinalizeReceiver
import app.squeeze.finalize.FinalizeReport
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Where the worker is, for the ongoing notification. */
data class QueueProgress(
    val index: Int,
    val total: Int,
    val name: String?,
    /** 0..1, or null for an indeterminate bar. */
    val fraction: Float?,
    val etaMs: Long?,
    val savedBytes: Long,
)

@Singleton
class NotificationHelper @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_progress_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_results_desc)
            },
        )
    }

    fun foregroundInfo(progress: QueueProgress): ForegroundInfo {
        return ForegroundInfo(PROGRESS_ID, progressNotification(progress), foregroundType())
    }

    fun updateProgress(progress: QueueProgress) {
        manager.notify(PROGRESS_ID, progressNotification(progress))
    }

    private fun progressNotification(p: QueueProgress) = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.notif_title, p.index, p.total))
        .setContentText(p.name ?: context.getString(R.string.notif_preparing))
        .setSubText(subText(p))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp())
        .apply {
            if (p.fraction == null) setProgress(0, 0, true) else setProgress(PROGRESS_MAX, (p.fraction * PROGRESS_MAX).toInt(), false)
        }
        .addAction(0, context.getString(R.string.notif_cancel_current), action(QueueActionReceiver.ACTION_CANCEL_CURRENT))
        .addAction(0, context.getString(R.string.notif_cancel_all), action(QueueActionReceiver.ACTION_CANCEL_ALL))
        .build()

    private fun subText(p: QueueProgress): String? {
        val parts = listOfNotNull(
            p.etaMs?.let { context.getString(R.string.notif_sub_eta, formatDuration(it)) },
            p.savedBytes.takeIf { it > 0 }?.let { context.getString(R.string.notif_sub_saved, Formatter.formatShortFileSize(context, it)) },
        )
        return when (parts.size) {
            0 -> null
            1 -> parts[0]
            else -> context.getString(R.string.notif_sub_join, parts[0], parts[1])
        }
    }

    /** Posts the completion notification unless the batch had nothing worth reporting (all cancelled). */
    fun postResultIfAny(finished: app.squeeze.data.FinishedBatch) {
        if (finished.summary.done + finished.summary.failed + finished.summary.skippedNoGain == 0) return
        postResult(finished.batchId, finished.summary)
    }

    /** "12 videos · 8.4 GB → 3.1 GB", plus the three actions when there is something to act on. */
    fun postResult(batchId: Long, summary: BatchSummary) {
        val res = context.resources
        val headline = if (summary.done > 0) {
            val videos = res.getQuantityString(R.plurals.notif_result_videos, summary.done, summary.done)
            val sizes = context.getString(
                R.string.notif_result_saved,
                Formatter.formatShortFileSize(context, summary.originalBytes),
                Formatter.formatShortFileSize(context, summary.originalBytes - summary.savedBytes),
                summary.savedPercent,
            )
            "$videos · $sizes"
        } else {
            context.getString(R.string.notif_result_nothing)
        }
        val extras = listOfNotNull(
            summary.skippedNoGain.takeIf { it > 0 }?.let { context.getString(R.string.notif_result_skipped, it) },
            summary.failed.takeIf { it > 0 }?.let { context.getString(R.string.notif_result_failed, it) },
        )
        notifyResult(batchId, headline, extras, withActions = summary.done > 0)
    }

    /** Replaces the completion notification after results were replaced or saved. */
    fun postFinalized(batchId: Long, report: FinalizeReport, stillPending: Boolean) {
        val res = context.resources
        val done = listOfNotNull(
            report.replaced.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.notif_final_replaced, it, it) },
            report.savedAsCopy.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.notif_final_saved, it, it) },
        ).joinToString(", ")
        val headline = when {
            done.isEmpty() -> context.getString(R.string.notif_final_nothing)
            report.savedBytes > 0 ->
                "$done · " + context.getString(R.string.notif_final_freed, Formatter.formatShortFileSize(context, report.savedBytes))
            else -> done
        }
        val extras = listOfNotNull(
            report.needsConsent.takeIf { it > 0 }?.let { context.getString(R.string.notif_final_consent, it) },
            report.failures.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.notif_final_failed, it.size) },
            report.originalsToDelete.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.notif_final_kept, it.size) },
        )
        notifyResult(batchId, headline, extras, withActions = stillPending)
    }

    fun cancelResult(batchId: Long) = manager.cancel(RESULT_ID_BASE + batchId.toInt())

    fun finalizeForegroundInfo(action: FinalizeAction): ForegroundInfo {
        val title = when (action) {
            FinalizeAction.REPLACE -> R.string.notif_finalize_replacing
            else -> R.string.notif_finalize_saving
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return ForegroundInfo(FINALIZE_ID, notification, foregroundType())
    }

    private fun notifyResult(batchId: Long, headline: String, extras: List<String>, withActions: Boolean) {
        val text = (listOf(headline) + extras).joinToString("\n")
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_result_title))
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
        if (withActions) {
            builder
                .addAction(0, context.getString(R.string.notif_action_reshare), finalizeActivity(batchId, FinalizeAction.SHARE))
                .addAction(0, context.getString(R.string.notif_action_replace), finalizeActivity(batchId, FinalizeAction.REPLACE))
                .addAction(0, context.getString(R.string.notif_action_save), finalizeBroadcast(batchId, FinalizeAction.SAVE))
        }
        manager.notify(RESULT_ID_BASE + batchId.toInt(), builder.build())
    }

    private fun finalizeActivity(batchId: Long, action: FinalizeAction): PendingIntent = PendingIntent.getActivity(
        context, requestCode(batchId, action), FinalizeActivity.intent(context, batchId, action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun finalizeBroadcast(batchId: Long, action: FinalizeAction): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode(batchId, action), FinalizeReceiver.intent(context, batchId, action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun requestCode(batchId: Long, action: FinalizeAction) = batchId.toInt() * FinalizeAction.entries.size + action.ordinal

    private fun foregroundType() = if (Build.VERSION.SDK_INT >= 35) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(name: String): PendingIntent = PendingIntent.getBroadcast(
        context, name.hashCode(),
        Intent(context, QueueActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_RESULTS = "results"
        const val PROGRESS_ID = 1
        const val FINALIZE_ID = 2
        private const val RESULT_ID_BASE = 1000
        private const val PROGRESS_MAX = 1000

        /** "12 min", "1 h 05 min", "40 s": coarse on purpose, the estimate is rough. */
        fun formatDuration(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return when {
                s < 60 -> "$s s"
                s < 3600 -> "${(s + 30) / 60} min"
                else -> "${s / 3600} h ${"%02d".format((s % 3600) / 60)} min"
            }
        }
    }
}
