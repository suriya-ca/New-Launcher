package com.sdev.newlauncher.data

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

/**
 * The [AppWidgetHost] that embeds third-party widgets on the home screen.
 *
 * Listening is started/stopped with the activity lifecycle so widget updates arrive while the
 * launcher is visible without keeping a binder alive in the background.
 */
class LauncherAppWidgetHost(context: Context, hostId: Int = APPWIDGET_HOST_ID) :
    AppWidgetHost(context, hostId) {

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo
    ): AppWidgetHostView = LauncherWidgetHostView(context)
}
