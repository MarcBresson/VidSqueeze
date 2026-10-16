package app.squeeze.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.squeeze.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What a launcher shortcut asks [ShortcutActivity] to do. */
enum class ShortcutAction(val id: String) {
    RUN("run"),
    PAUSE("pause"),
    RESUME("resume");

    companion object {
        fun fromId(id: String?): ShortcutAction? = entries.firstOrNull { it.id == id }

        /** Pause and resume are always offered; "run" only makes sense while auto compression is on. */
        fun available(autoEnabled: Boolean): List<ShortcutAction> =
            if (autoEnabled) listOf(RUN, PAUSE, RESUME) else listOf(PAUSE, RESUME)
    }
}

/**
 * Launcher shortcuts shown when the app icon is long-pressed ("Run auto compression now", pause,
 * resume). They start [ShortcutActivity], which is not exported: only the launcher can fire them.
 */
@Singleton
class AppShortcuts @Inject constructor(@ApplicationContext private val context: Context) {

    fun publish(autoEnabled: Boolean) {
        val shortcuts = ShortcutAction.available(autoEnabled).map(::shortcut)
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    private fun shortcut(action: ShortcutAction): ShortcutInfoCompat {
        val label = when (action) {
            ShortcutAction.RUN -> R.string.shortcut_run
            ShortcutAction.PAUSE -> R.string.shortcut_pause
            ShortcutAction.RESUME -> R.string.shortcut_resume
        }
        val intent = Intent(context, ShortcutActivity::class.java).setAction(ACTION).putExtra(EXTRA_ID, action.id)
        return ShortcutInfoCompat.Builder(context, action.id)
            .setShortLabel(context.getString(label))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut))
            .setIntent(intent)
            .build()
    }

    companion object {
        const val ACTION = "app.squeeze.action.SHORTCUT"
        const val EXTRA_ID = "shortcut"
    }
}
