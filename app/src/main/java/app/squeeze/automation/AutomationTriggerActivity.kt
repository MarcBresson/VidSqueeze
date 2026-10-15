package app.squeeze.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import app.squeeze.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Invisible activity that runs the same commands as [AutomationReceiver]. Starting an activity
 * gives the app a foreground context, which a bare broadcast from the background does not, so this
 * is the entry point for launchers' app shortcuts and for tools that can only "launch an app".
 */
@AndroidEntryPoint
class AutomationTriggerActivity : ComponentActivity() {
    @Inject lateinit var service: AutomationService
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.action
        val extras = intent.extras
        scope.launch { service.handle(action, extras) }
        finish()
    }
}
