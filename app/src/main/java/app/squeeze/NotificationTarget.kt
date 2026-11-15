package app.squeeze

import android.content.Intent

/** Reads the batch a result notification wants History to focus on. */
object NotificationTarget {
    const val EXTRA_BATCH_ID = "app.squeeze.extra.BATCH_ID"

    /** Marks the intent a result-notification tap fires; some OEM builds drop extras from action-less intents. */
    const val ACTION_OPEN_HISTORY = "app.squeeze.action.OPEN_HISTORY"

    private const val NO_BATCH = -1L

    /** The batch id carried by a result-notification tap, or null for any other launch. */
    fun batchIdFrom(intent: Intent): Long? = batchId(intent.getLongExtra(EXTRA_BATCH_ID, NO_BATCH))

    /** Validates the raw extra: [NO_BATCH] (and anything negative) means "no batch". */
    fun batchId(raw: Long): Long? = raw.takeIf { it >= 0 }
}
