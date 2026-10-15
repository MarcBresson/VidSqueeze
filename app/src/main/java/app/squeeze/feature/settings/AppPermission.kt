package app.squeeze.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings

/** Everything the app can ask the user for, in the order the permissions screen shows it. */
enum class AppPermission(
    /** The runtime permission behind it, or null when it is granted on a system settings page. */
    val runtime: String?,
    /** Recommended: the main features (auto compression, suggestions, in-place replace) need it; sharing and picking work without. */
    val recommended: Boolean,
) {
    VIDEOS(Manifest.permission.READ_MEDIA_VIDEO, recommended = true),
    LOCATION(Manifest.permission.ACCESS_MEDIA_LOCATION, recommended = false),
    NOTIFICATIONS(Manifest.permission.POST_NOTIFICATIONS, recommended = false),
    MANAGE_MEDIA(null, recommended = false),
    BATTERY(null, recommended = false),
}

/** Reads the current state of each [AppPermission] and builds the system settings intents. */
object AppPermissions {
    fun isGranted(context: Context, permission: AppPermission): Boolean = when (permission) {
        AppPermission.MANAGE_MEDIA -> MediaStore.canManageMedia(context)
        AppPermission.BATTERY ->
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        else -> context.checkSelfPermission(requireNotNull(permission.runtime)) == PackageManager.PERMISSION_GRANTED
    }

    fun snapshot(context: Context): Map<AppPermission, Boolean> =
        AppPermission.entries.associateWith { isGranted(context, it) }

    /** The system page where a permission that cannot be asked in-app (or was refused for good) is changed. */
    fun settingsIntent(context: Context, permission: AppPermission): Intent = when (permission) {
        AppPermission.MANAGE_MEDIA -> Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, packageUri(context))
        AppPermission.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> appDetails(context)
    }

    fun appDetails(context: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)
}
