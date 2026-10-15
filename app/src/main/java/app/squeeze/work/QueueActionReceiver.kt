package app.squeeze.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Handles the Cancel actions on the ongoing notification. Not exported. */
@AndroidEntryPoint
class QueueActionReceiver : BroadcastReceiver() {
    @Inject lateinit var queue: QueueManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        when (intent.action) {
            ACTION_CANCEL_CURRENT -> queue.cancelCurrent(pending::finish)
            ACTION_CANCEL_ALL -> queue.cancelAll(pending::finish)
            else -> pending.finish()
        }
    }

    companion object {
        const val ACTION_CANCEL_CURRENT = "app.squeeze.action.CANCEL_CURRENT"
        const val ACTION_CANCEL_ALL = "app.squeeze.action.CANCEL_ALL"
    }
}
