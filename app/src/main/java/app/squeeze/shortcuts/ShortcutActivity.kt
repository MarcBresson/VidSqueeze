package app.squeeze.shortcuts

import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import app.squeeze.R
import app.squeeze.auto.AutoRunner
import app.squeeze.auto.RunOptions
import app.squeeze.auto.Trigger
import app.squeeze.di.ApplicationScope
import app.squeeze.work.QueueManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible activity behind the launcher shortcuts. Starting an activity gives the app a
 * foreground context, which a bare broadcast from the background does not. Not exported, so
 * nothing but the launcher can reach it.
 */
@AndroidEntryPoint
class ShortcutActivity : ComponentActivity() {
    @Inject lateinit var runner: AutoRunner
    @Inject lateinit var queue: QueueManager
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = ShortcutAction.fromId(intent.getStringExtra(AppShortcuts.EXTRA_ID))
        val context = applicationContext
        if (action != null) scope.launch { toast(context, perform(context, action)) }
        finish()
    }

    private suspend fun perform(context: Context, action: ShortcutAction): String = when (action) {
        ShortcutAction.PAUSE -> { queue.pause(); context.getString(R.string.shortcut_paused) }
        ShortcutAction.RESUME -> { queue.resume(); context.getString(R.string.shortcut_resumed) }
        ShortcutAction.RUN -> {
            val result = runner.run(RunOptions(Trigger.EXTERNAL))
            val reason = result.skippedReason
            when {
                reason != null -> context.getString(R.string.shortcut_run_skipped, reason)
                result.videos == 0 -> context.getString(R.string.shortcut_run_nothing)
                else -> context.resources.getQuantityString(R.plurals.shortcut_run_queued, result.videos, result.videos, Formatter.formatShortFileSize(context, result.bytes))
            }
        }
    }

    private suspend fun toast(context: Context, text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
