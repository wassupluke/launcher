package de.jrpie.android.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.UserHandle
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import de.jrpie.android.launcher.actions.TorchManager
import de.jrpie.android.launcher.apps.AbstractAppInfo
import de.jrpie.android.launcher.apps.AbstractDetailedAppInfo
import de.jrpie.android.launcher.apps.isPrivateSpaceLocked
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.migratePreferencesToNewVersion
import de.jrpie.android.launcher.preferences.resetPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess


const val APP_WIDGET_HOST_ID = 42


class Application : android.app.Application() {
    val apps = MutableLiveData<List<AbstractDetailedAppInfo>>()
    val privateSpaceLocked = MutableLiveData<Boolean>()
    lateinit var appWidgetHost: AppWidgetHost
    lateinit var appWidgetManager: AppWidgetManager

    private val profileAvailabilityBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // TODO: only update specific apps
            // The intent carries Intent.EXTRA_USER identifying which managed/private-space profile
            // became available or unavailable. Instead of reloading the entire app list, we should
            // only add or remove the apps belonging to that specific user profile, which would avoid
            // an expensive full scan (getActivityList across all profiles) on every profile toggle.
            loadApps()
        }
    }

    // TODO: only update specific apps
    // Currently every package change (add/remove/update) triggers a full reload of the app list via
    // getApps(), which queries LauncherApps.getActivityList() for every user profile and re-sorts
    // the result. This is correct but wasteful. The ideal approach for onPackageAdded/Removed/Changed
    // would be to surgically insert, remove, or refresh just the affected package's entries in the
    // existing apps LiveData list, avoiding the cost of a full scan. The full-reload fallback is kept
    // as a safe baseline while targeted updates are not yet implemented.
    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p0: String?, p1: UserHandle?) {
            loadApps()
        }

        override fun onPackageAdded(p0: String?, p1: UserHandle?) {
            loadApps()
        }

        override fun onPackageChanged(p0: String?, p1: UserHandle?) {
            loadApps()
        }

        override fun onPackagesAvailable(p0: Array<out String>?, p1: UserHandle?, p2: Boolean) {
            // TODO: call loadApps() (or a targeted reload for the listed packages).
            // This callback fires when a set of packages that were previously unavailable (e.g.
            // because the user profile hosting them was in quiet mode) becomes available again.
            // Without handling it, the app list will not update when a managed or private-space
            // profile is unlocked / quiet mode is toggled off, so apps from that profile will
            // remain absent until the user triggers a reload some other way.
            // The boolean parameter (p2) indicates whether the packages are being replaced
            // (i.e. returning from replacement during an update).
        }

        override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) {
            // TODO: call loadApps() (or a targeted reload for the listed packages).
            // This callback fires when the device policy controller (DPC) or Digital Wellbeing
            // suspends a set of packages. Note: suspension is separate from quiet mode —
            // suspended apps remain visible and launchable in the app list, but when tapped the
            // system intercepts the launch and shows a suspension dialog instead of starting the
            // app. Without handling it, the app list will not reflect the new suspension state.
            // A launcher could call loadApps() and then filter on
            // UserManager.isPackageSuspended() / ApplicationInfo.FLAG_SUSPENDED to hide or
            // visually mark suspended apps, or simply reload so the list is fresh.
        }

        override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) {
            // TODO: call loadApps() (or a targeted reload for the listed packages).
            // Counterpart to onPackagesSuspended: fires when a previously suspended set of packages
            // is restored to normal. Without handling it, apps that were hidden or marked as
            // suspended by an earlier callback will remain in that state in the list even after the
            // suspension is lifted.
        }

        override fun onPackagesUnavailable(p0: Array<out String>?, p1: UserHandle?, p2: Boolean) {
            // TODO: call loadApps() (or a targeted reload for the listed packages).
            // This callback fires when packages become unavailable because the profile hosting them
            // enters quiet mode (e.g. managed profile paused, private space locked). Without
            // handling it, apps from the affected profile will continue to appear in the launcher's
            // app list after the profile is paused, making them appear launchable when they are not.
            // The boolean parameter indicates whether the packages are being replaced (e.g. during
            // an update-while-unavailable scenario).
        }

        override fun onPackageLoadingProgressChanged(
            packageName: String,
            user: UserHandle,
            progress: Float
        ) {
            // TODO: surface incremental installation progress in the app list (API 31+).
            // This callback fires while a package is being installed incrementally (via the
            // Incremental File System). Until progress reaches 1.0f the app may appear in the
            // launcher but will fail to launch if required components are not yet on-device.
            // A full implementation would update a progress indicator on the corresponding app
            // entry in the list, or suppress the entry until installation is complete.
        }

        override fun onShortcutsChanged(
            packageName: String,
            shortcuts: MutableList<ShortcutInfo>,
            user: UserHandle
        ) {
            // TODO: call loadApps() when pinned shortcuts are affected by this change.
            // This callback fires when the dynamic or pinned shortcuts published by a package
            // change (e.g. the app revokes or republishes them). Pinned shortcuts are stored in
            // LauncherPreferences and surfaced in the app list as DetailedPinnedShortcutInfo
            // entries. If a shortcut the user has pinned is revoked, its entry should be removed
            // from the list (or marked as invalid) so the user is not left with a broken launcher
            // binding. Currently the list is not refreshed on shortcut changes, so stale shortcut
            // entries can persist until the next full reload.
        }
    }

    var torchManager: TorchManager? = null
    private var customAppNames: HashMap<AbstractAppInfo, String>? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, pref ->
        if (pref == getString(R.string.settings_apps_custom_names_key)) {
            customAppNames = LauncherPreferences.apps().customNames()
        } else if (pref == LauncherPreferences.apps().keys().pinnedShortcuts()) {
            loadApps()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // TODO: re-enable Material You dynamic color theming once the resource ID crash is resolved.
        // DynamicColors.applyToActivitiesIfAvailable(this) currently throws an
        // "Invalid resource ID 0x00000000" error at runtime, likely because the launcher's minimal
        // theme does not declare the attribute slots that the Material dynamic-color overlay expects
        // to override. Until the theme is extended to include those attributes (or a workaround is
        // found), this call is commented out and dynamic colors remain unavailable to users on
        // Android 12+ devices that support them.
        // DynamicColors.applyToActivitiesIfAvailable(this)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            sendCrashNotification(this@Application, throwable)
            exitProcess(1)
        }


        if (Build.VERSION.SDK_INT >= VERSION_CODES.M) {
            torchManager = TorchManager(this)
        }

        appWidgetHost = AppWidgetHost(this.applicationContext, APP_WIDGET_HOST_ID)
        appWidgetManager = AppWidgetManager.getInstance(this.applicationContext)


        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        LauncherPreferences.init(preferences, this.resources)

        // Try to restore old preferences
        migratePreferencesToNewVersion(this)

        // First time opening the app: set defaults
        // The tutorial is started from HomeActivity#onStart, as starting it here is blocked by android
        if (!LauncherPreferences.internal().started()) {
            resetPreferences(this)
        }


        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(listener)


        val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        launcherApps.registerCallback(launcherAppsCallback)

        if (Build.VERSION.SDK_INT >= VERSION_CODES.N) {
            val filter = IntentFilter().also {
                if (Build.VERSION.SDK_INT >= VERSION_CODES.VANILLA_ICE_CREAM) {
                    it.addAction(Intent.ACTION_PROFILE_AVAILABLE)
                    it.addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                } else {
                    it.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                    it.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                }
            }
            ContextCompat.registerReceiver(
                this, profileAvailabilityBroadcastReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        if (Build.VERSION.SDK_INT >= VERSION_CODES.N_MR1) {
            removeUnusedShortcuts(this)
        }
        loadApps()

        createNotificationChannels(this)
    }

    fun getCustomAppNames(): HashMap<AbstractAppInfo, String> {
        return (customAppNames ?: LauncherPreferences.apps().customNames() ?: HashMap())
            .also { customAppNames = it }
    }

    private fun loadApps() {
        privateSpaceLocked.postValue(isPrivateSpaceLocked(this))
        CoroutineScope(Dispatchers.Default).launch {
            apps.postValue(getApps(packageManager, applicationContext))
        }
    }
}
