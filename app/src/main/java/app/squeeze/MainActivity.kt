package app.squeeze

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.squeeze.data.ThemeMode
import app.squeeze.ui.nav.AppNav
import app.squeeze.ui.theme.SqueezeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationTap(intent, via = "onCreate savedState=${savedInstanceState != null}")
        setContent {
            val ui by app.ui.collectAsStateWithLifecycle()
            val notificationTarget by app.notificationTarget.collectAsStateWithLifecycle()
            val state = ui ?: return@setContent
            val dark = when (state.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The system bars follow the app's own light/dark choice, not just the system's.
            DisposableEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            SqueezeTheme(darkTheme = dark, dynamicColor = state.dynamicColor) {
                // The start screen is fixed once: finishing onboarding navigates away, it does not restart the graph.
                AppNav(
                    startWithOnboarding = !state.onboardingDone,
                    notificationTarget = notificationTarget,
                    onTargetConsumed = app::clearNotificationTarget,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationTap(intent, via = "onNewIntent")
    }

    /** Single funnel for the notification-tap extra so a device log shows exactly where a tap lands. */
    private fun handleNotificationTap(intent: Intent, via: String) {
        val batch = NotificationTarget.batchIdFrom(intent)
        Log.d(TAG, "tap via $via: action=${intent.action} flags=0x${Integer.toHexString(intent.flags)} batchId=$batch")
        batch?.let(app::openFromNotification)
    }

    private companion object {
        const val TAG = "NotifNav"
    }
}
