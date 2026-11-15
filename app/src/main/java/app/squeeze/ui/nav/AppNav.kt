package app.squeeze.ui.nav

import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.squeeze.feature.auto.AutoScreen
import app.squeeze.feature.compare.CompareScreen
import app.squeeze.feature.history.HistoryScreen
import app.squeeze.feature.presets.PresetEditorScreen
import app.squeeze.feature.presets.PresetsScreen
import app.squeeze.feature.home.HomeScreen
import app.squeeze.feature.settings.AboutScreen
import app.squeeze.feature.settings.CapabilitiesScreen
import app.squeeze.feature.settings.LicenseTextScreen
import app.squeeze.feature.settings.LicensesScreen
import app.squeeze.feature.settings.OnboardingScreen
import app.squeeze.feature.settings.PermissionsScreen
import app.squeeze.feature.settings.SettingsScreen
import app.squeeze.feature.compress.NEW_PRESET_ID

private const val TAG = "NotifNav"

object Routes {
    const val HOME = "home"
    const val PRESETS = "presets"
    const val HISTORY = "history"
    const val AUTO = "auto"
    const val PRESET_EDIT = "presets/{id}"
    const val PRESET_NEW = "presets/new"
    const val COMPARE = "compare/{itemId}"
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val CAPABILITIES = "capabilities"
    const val ABOUT = "about"
    const val LICENSES = "licenses"
    const val LICENSE_TEXT = "licenses/{name}"
    fun licenseText(name: String) = "licenses/${android.net.Uri.encode(name)}"
    fun compare(itemId: Long) = "compare/$itemId"
    fun presetEdit(id: String) = "presets/${android.net.Uri.encode(id)}"
}

@Composable
fun AppNav(
    startWithOnboarding: Boolean = false,
    notificationTarget: Long? = null,
    onTargetConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    val focus = remember { HistoryFocus() }
    val destination = nav.currentBackStackEntryAsState().value?.destination
    val showBar = TopLevelDestination.entries.any { tab -> destination?.hierarchy?.any { it.route == tab.route } == true }
    // A result-notification tap switches to History even while another screen is on top.
    // The NavHost graph is remembered keyed on (startDestination, builder): feeding it the tap
    // values rebuilds the graph when they change, and setGraph resets the back stack to the
    // start destination — History flashed, then the app dropped back to Home. So the graph only
    // ever sees the stable [focus] holder, and the start destination is frozen once.
    LaunchedEffect(notificationTarget) {
        if (notificationTarget != null) {
            Log.d(TAG, "switching to History for batch $notificationTarget")
            focus.set(notificationTarget)
            onTargetConsumed()
            nav.navigateToTab(Routes.HISTORY)
        }
    }
    // The screens inside handle the status bar and, without the bar, the navigation bar themselves.
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = { if (showBar) BottomBar(nav) },
    ) { inner ->
        val bottom = PaddingValues(bottom = inner.calculateBottomPadding())
        NavHost(
            navController = nav,
            startDestination = remember {
                when {
                    startWithOnboarding -> Routes.ONBOARDING
                    notificationTarget != null -> Routes.HISTORY
                    else -> Routes.HOME
                }
            },
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
        ) {
            appGraph(nav, focus)
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController) {
    val destination = nav.currentBackStackEntryAsState().value?.destination
    NavigationBar {
        TopLevelDestination.entries.forEach { tab ->
            NavigationBarItem(
                selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                onClick = { nav.navigateToTab(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

private fun NavGraphBuilder.appGraph(nav: NavHostController, focus: HistoryFocus) {
    composable(Routes.ONBOARDING) {
        OnboardingScreen(onDone = {
            nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
        })
    }
    composable(Routes.HOME) { HomeScreen(onOpenHistory = { nav.navigateToTab(Routes.HISTORY) }) }
    composable(Routes.SETTINGS) {
        SettingsScreen(
            onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
            onOpenCapabilities = { nav.navigate(Routes.CAPABILITIES) },
            onOpenAbout = { nav.navigate(Routes.ABOUT) },
        )
    }
    composable(Routes.PERMISSIONS) { PermissionsScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.CAPABILITIES) { CapabilitiesScreen(onBack = { nav.popBackStack() }) }
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
    composable(Routes.AUTO) { AutoScreen() }
    composable(Routes.HISTORY) {
        HistoryScreen(
            onCompare = { nav.navigate(Routes.compare(it)) },
            focusBatch = focus.batch,
            onFocusConsumed = focus::consume,
        )
    }
    composable(Routes.COMPARE, arguments = listOf(navArgument("itemId") { type = NavType.LongType })) {
        CompareScreen(onBack = { nav.popBackStack() })
    }
    composable(Routes.PRESETS) {
        PresetsScreen(onEdit = { nav.navigate(Routes.presetEdit(it)) }, onNew = { nav.navigate(Routes.PRESET_NEW) })
    }
    composable(Routes.PRESET_NEW) {
        PresetEditorScreen(presetId = NEW_PRESET_ID, onBack = { nav.popBackStack() })
    }
    composable(Routes.PRESET_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
        PresetEditorScreen(presetId = requireNotNull(entry.arguments?.getString("id")), onBack = { nav.popBackStack() })
    }
}

/**
 * The batch History should focus on, set by a result-notification tap and consumed by HistoryScreen.
 * A class with a stable identity (not per-tap values) so the NavHost graph builder capturing it is
 * never invalidated: a rebuilt graph resets the back stack to the start destination.
 */
private class HistoryFocus {
    var batch by mutableStateOf<Long?>(null)
        private set

    fun set(batchId: Long) {
        batch = batchId
    }

    fun consume() {
        batch = null
    }
}

/** Same behaviour as tapping the tab in the bar. */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
