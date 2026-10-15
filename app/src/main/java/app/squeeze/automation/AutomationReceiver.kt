package app.squeeze.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.squeeze.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The broadcast entry point of the automation API (Tasker, MacroDroid, Automate, `adb shell am
 * broadcast`, Routine+ "Send intent"). Exported, so every command must carry the secret token.
 */
@AndroidEntryPoint
class AutomationReceiver : BroadcastReceiver() {
    @Inject lateinit var service: AutomationService
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                val outcome = service.handle(intent.action, intent.extras)
                pending.setResultCode(if (outcome.accepted) 1 else 0)
                pending.setResultData(outcome.detail)
            } finally {
                pending.finish()
            }
        }
    }
}
