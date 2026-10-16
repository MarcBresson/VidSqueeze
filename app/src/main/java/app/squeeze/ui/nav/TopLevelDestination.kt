package app.squeeze.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import app.squeeze.R

/** The five tabs of the bottom navigation bar, in display order. */
enum class TopLevelDestination(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    AUTO(Routes.AUTO, R.string.nav_auto, Icons.Filled.AutoAwesome),
    HISTORY(Routes.HISTORY, R.string.nav_history, Icons.Filled.History),
    PRESETS(Routes.PRESETS, R.string.nav_presets, Icons.Filled.Tune),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
}
