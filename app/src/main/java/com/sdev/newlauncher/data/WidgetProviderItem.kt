package com.sdev.newlauncher.data

import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable

/** One addable widget, as shown in the home-screen widget picker. */
data class WidgetProviderItem(
    val providerInfo: AppWidgetProviderInfo,
    val label: String,
    val appName: String,
    val packageName: String,
    val preview: Drawable?,
    val minSpanX: Int,
    val minSpanY: Int
)

/** Host id for [LauncherAppWidgetHost]; stable so widgets survive restarts. */
const val APPWIDGET_HOST_ID = 2048
