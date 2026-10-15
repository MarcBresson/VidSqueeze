package app.squeeze.automation

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.squeeze.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launcher shortcuts ("Run auto compression now", pause, resume) that fire the automation
 * commands through [AutomationTriggerActivity]. They carry the secret token, which only the
 * launcher can read; they exist only while external automation is on.
 */
@Singleton
class AutomationShortcuts @Inject constructor(@ApplicationContext private val context: Context) {

    fun publish(enabled: Boolean, token: String?) {
        if (!enabled || token.isNullOrEmpty()) {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            return
        }
        val shortcuts = listOf(
            shortcut("run", R.string.shortcut_run, AutomationApi.START_AUTO_RUN, token),
            shortcut("pause", R.string.shortcut_pause, AutomationApi.PAUSE_QUEUE, token),
            shortcut("resume", R.string.shortcut_resume, AutomationApi.RESUME_QUEUE, token),
        )
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    private fun shortcut(id: String, label: Int, action: String, token: String): ShortcutInfoCompat {
        val intent = Intent(context, AutomationTriggerActivity::class.java)
            .setAction(action)
            .putExtra(AutomationApi.EXTRA_TOKEN, token)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(label))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
            .setIntent(intent)
            .build()
    }
}
