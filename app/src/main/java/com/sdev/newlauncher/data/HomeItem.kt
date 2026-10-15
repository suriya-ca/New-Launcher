package com.sdev.newlauncher.data

/**
 * One occupancy on the home grid: either a launchable app or a hosted widget.
 *
 * Both kinds keep an explicit `(page, row, col)` position plus a span, which is what lets the
 * workspace support free positioning with gaps. [appWidgetId] is [NO_WIDGET] for app items.
 */
data class HomeItem(
    val packageName: String,
    val activityName: String = "",
    val appWidgetId: Int = NO_WIDGET,
    val page: Int = 0,
    val row: Int = 0,
    val col: Int = 0,
    val rowSpan: Int = 1,
    val colSpan: Int = 1
) {
    val isWidget: Boolean get() = appWidgetId != NO_WIDGET

    /** Stable identifier, used to diff views while re-laying out and to persist drops. */
    val id: String
        get() = if (isWidget) "widget:$appWidgetId" else "app:$packageName/$activityName"

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
