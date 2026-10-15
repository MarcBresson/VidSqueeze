package app.squeeze.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.squeeze.feature.auto.AutoScreen
import app.squeeze.feature.history.HistoryScreen
import app.squeeze.feature.presets.PresetEditorScreen
import app.squeeze.feature.presets.PresetsScreen
import app.squeeze.feature.queue.QueueScreen
import app.squeeze.feature.settings.AboutScreen
import app.squeeze.feature.settings.LicenseTextScreen
import app.squeeze.feature.settings.LicensesScreen
import app.squeeze.feature.settings.OnboardingScreen
import app.squeeze.feature.settings.PermissionsScreen
import app.squeeze.feature.settings.SettingsScreen

object Routes {
    const val QUEUE = "queue"
    const val PRESETS = "presets"
    const val HISTORY = "history"
    const val AUTO = "auto"
    const val PRESET_EDIT = "presets/{id}"
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val ABOUT = "about"
    const val LICENSES = "licenses"
    const val LICENSE_TEXT = "licenses/{name}"
    fun licenseText(name: String) = "licenses/${android.net.Uri.encode(name)}"
    fun presetEdit(id: String) = "presets/${android.net.Uri.encode(id)}"
}

@Composable
fun AppNav(startWithOnboarding: Boolean = false) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = if (startWithOnboarding) Routes.ONBOARDING else Routes.QUEUE) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                nav.navigate(Routes.QUEUE) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.QUEUE) { QueueScreen(
                onOpenPresets = { nav.navigate(Routes.PRESETS) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) },
                onOpenAuto = { nav.navigate(Routes.AUTO) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            ) }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
                onOpenAuto = { nav.navigate(Routes.AUTO) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.PERMISSIONS) { PermissionsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { nav.popBackStack() },
                onOpenLicenses = { nav.navigate(Routes.LICENSES) },
                onOpenText = { nav.navigate(Routes.licenseText(it)) },
            )
        }
        composable(Routes.LICENSES) {
            LicensesScreen(onBack = { nav.popBackStack() }, onOpenText = { nav.navigate(Routes.licenseText(it)) })
        }
        composable(Routes.LICENSE_TEXT, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
            LicenseTextScreen(rawName = requireNotNull(entry.arguments?.getString("name")), onBack = { nav.popBackStack() })
        }
        composable(Routes.AUTO) { AutoScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.HISTORY) { HistoryScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PRESETS) {
            PresetsScreen(onBack = { nav.popBackStack() }, onEdit = { nav.navigate(Routes.presetEdit(it)) })
        }
        composable(Routes.PRESET_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            PresetEditorScreen(presetId = requireNotNull(entry.arguments?.getString("id")), onBack = { nav.popBackStack() })
        }
    }
}
