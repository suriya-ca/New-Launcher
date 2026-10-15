package com.sdev.newlauncher.ui

import com.sdev.newlauncher.data.WidgetProviderItem

/** Row types shown in the widget picker sheet. */
sealed interface WidgetRow {
    data class Header(val appName: String) : WidgetRow
    data class Widget(val item: WidgetProviderItem) : WidgetRow
}