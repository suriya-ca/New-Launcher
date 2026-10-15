package com.sdev.newlauncher.data

import android.graphics.drawable.Drawable

/**
 * A launchable application entry shown on the home screen or in the app drawer.
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean
) {
    /** Stable identifier used for persistence and diffing. */
    val key: String
        get() = "$packageName/$activityName"
}
