package com.sdev.newlauncher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Notifies the launcher when apps are installed, removed or changed so the
 * home screen and app drawer can refresh.
 */
class PackageChangeReceiver(private val onPackageChanged: () -> Unit) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REPLACED -> onPackageChanged()
        }
    }
}
