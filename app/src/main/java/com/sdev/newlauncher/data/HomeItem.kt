package com.sdev.newlauncher.data

/**
 * One occupancy on the home grid: a launchable app, a hosted widget, or a folder of apps.
 *
 * Every kind keeps an explicit `(page, row, col)` position plus a span, which is what lets the
 * workspace support free positioning with gaps. [appWidgetId] is [NO_WIDGET] for app and folder
 * items; [folderId] is empty for everything but a folder.
 */
data class HomeItem(
    val packageName: String,
    val activityName: String = "",
    val appWidgetId: Int = NO_WIDGET,
    val page: Int = 0,
    val row: Int = 0,
    val col: Int = 0,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    val folderId: String = ""
) {
    val isWidget: Boolean get() = appWidgetId != NO_WIDGET

    /** True when this placement is a folder's cell rather than an app or a widget. */
    val isFolder: Boolean get() = !isWidget && folderId.isNotEmpty()

    /** True when this placement launches an app directly. */
    val isApp: Boolean get() = !isWidget && !isFolder

    /** Stable identifier, used to diff views while re-laying out and to persist drops. */
    val id: String
        get() = when {
            isWidget -> "widget:$appWidgetId"
            isFolder -> "folder:$folderId"
            else -> "app:$packageName/$activityName"
        }

    /**
     * The `package/activity` key of the app this placement stands for. Folder children are keyed
     * the same way, which is what lets a folder hold apps that are hidden from the drawer.
     */
    val appKey: String get() = "$packageName/$activityName"

    /** One past the last column this item occupies. */
    val colEnd: Int get() = col + colSpan

    /** One past the last row this item occupies. */
    val rowEnd: Int get() = row + rowSpan

    /** True when this item and [other] share at least one cell on the same page. */
    fun overlaps(other: HomeItem): Boolean =
        page == other.page &&
            col < other.colEnd && other.col < colEnd &&
            row < other.rowEnd && other.row < rowEnd

    companion object {
        const val NO_WIDGET = -1
    }
}
