package com.sdev.newlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the list of launchable apps and performs app actions (launch / info / uninstall).
 *
 * Uses [LauncherApps] when available (correct for a launcher, handles work profiles) and
 * falls back to [PackageManager] otherwise.
 */
object AppRepository {

    /**
     * Fully-qualified name of our own settings activity. It is surfaced in the app drawer
     * as a normal launchable app (see its LAUNCHER intent-filter in AndroidManifest.xml),
     * while every other component of our own package stays hidden.
     */
    private const val OWN_SETTINGS_ACTIVITY = "com.sdev.newlauncher.SettingsActivity"

    /**
     * Returns every launchable activity on the device, excluding our own package
     * (except [OWN_SETTINGS_ACTIVITY]), sorted alphabetically by label.
     */
    suspend fun loadApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<AppInfo>()
        val seen = mutableSetOf<String>()
        val packageManager = context.packageManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val myUser: UserHandle = Process.myUserHandle()
        val density = context.resources.displayMetrics.densityDpi

        if (launcherApps != null) {
            val activities = try {
                launcherApps.getActivityList(null, myUser)
            } catch (e: Throwable) {
                emptyList()
            }

            for (activity in activities) {
                val pkg = activity.applicationInfo.packageName
                val activityName = activity.name
                if (pkg == context.packageName && activityName != OWN_SETTINGS_ACTIVITY) continue
                val key = "$pkg/$activityName"
                if (!seen.add(key)) continue

                val label = try {
                    activity.label?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                } catch (e: Throwable) {
                    pkg
                }

                val icon = try {
                    activity.getBadgedIcon(density)
                } catch (e: Throwable) {
                    null
                }

                result.add(
                    AppInfo(
                        label = label,
                        packageName = pkg,
                        activityName = activityName,
                        icon = icon,
                        isSystemApp = activity.applicationInfo.isSystemApp()
                    )
                )
            }
        }

        // Fallback: query the package manager directly.
        if (result.isEmpty()) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfos = try {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } catch (e: Throwable) {
                emptyList()
            }

            for (resolveInfo in resolveInfos) {
                val activityInfo = resolveInfo.activityInfo ?: continue
                val pkg = activityInfo.packageName
                val activityName = activityInfo.name
                if (pkg == context.packageName && activityName != OWN_SETTINGS_ACTIVITY) continue
                val key = "$pkg/$activityName"
                if (!seen.add(key)) continue

                val label = try {
                    resolveInfo.loadLabel(packageManager).toString().takeIf { it.isNotBlank() } ?: pkg
                } catch (e: Throwable) {
                    pkg
                }

                val icon = try {
                    resolveInfo.loadIcon(packageManager)
                } catch (e: Throwable) {
                    null
                }

                result.add(
                    AppInfo(
                        label = label,
                        packageName = pkg,
                        activityName = activityName,
                        icon = icon,
                        isSystemApp = activityInfo.applicationInfo?.isSystemApp() ?: false
                    )
                )
            }
        }

        result.sortedBy { it.label.lowercase() }
    }

    /** Launches the given app. */
    fun launch(context: Context, app: AppInfo) {
        launchComponent(context, app.packageName, app.activityName)
    }

    private fun launchComponent(context: Context, packageName: String, activityName: String) {
        // LauncherApps.startMainActivity (and a MAIN/LAUNCHER intent) uses
        // FLAG_ACTIVITY_RESET_TASK_IF_NEEDED. Because our settings activity shares the home
        // task's affinity while the home activity is singleTask, that flag misroutes the
        // launch back to the running home activity. A plain explicit intent avoids it.
        if (packageName == context.packageName) {
            try {
                context.startActivity(
                    Intent().apply {
                        component = ComponentName(packageName, activityName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
                return
            } catch (e: Throwable) {
                // fall through to the generic approaches below
            }
        }

        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (launcherApps != null) {
            try {
                launcherApps.startMainActivity(
                    ComponentName(packageName, activityName),
                    Process.myUserHandle(),
                    null,
                    null
                )
                return
            } catch (e: Throwable) {
                // fall through to the intent-based approach
            }
        }

        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(packageName, activityName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
            )
        } catch (e: Throwable) {
            Toast.makeText(context, "Failed to launch: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the system "App info" screen for the given app. */
    fun openAppInfo(context: Context, app: AppInfo) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", app.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(context, "Unable to open app info", Toast.LENGTH_SHORT).show()
        }
    }

    /** Requests the system uninstall flow for the given app. */
    fun requestUninstall(context: Context, app: AppInfo) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.fromParts("package", app.packageName, null)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(context, "Unable to uninstall", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
}
