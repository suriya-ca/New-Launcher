package com.sdev.newlauncher.data

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.sdev.newlauncher.R

/**
 * Hosts one widget on the home screen.
 *
 * The platform's own fallback for a widget whose RemoteViews can't be inflated is a plain
 * "Couldn't add widget." text box, which reads as a bug in the launcher. Show the same
 * placeholder used for a widget whose provider has gone away instead.
 */
class LauncherWidgetHostView(context: Context) : AppWidgetHostView(context) {

    override fun getErrorView(): View =
        LayoutInflater.from(context).inflate(R.layout.item_widget_missing, this, false)
}
