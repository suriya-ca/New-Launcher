package com.sdev.newlauncher.data

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

/** Enumerates the widgets installed on the device for the home-screen widget picker. */
object WidgetRepository {

    /**
     * All installed widget providers, sorted by app then widget name. [cellWidthDp], [cellHeightDp]
     * and [cellGapDp] are the current home grid measurements, used to turn a provider's minimum
     * size into a number of grid cells.
     */
    fun loadProviders(
        context: Context,
        cellWidthDp: Int,
        cellHeightDp: Int,
        cellGapDp: Int
    ): List<WidgetProviderItem> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyList()
        val packageManager = context.packageManager
        val providers = try {
            manager.installedProviders
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return providers.mapNotNull { info ->
            try {
                val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
                    ?: info.provider.packageName
                val appName = try {
                    val application = packageManager.getApplicationInfo(info.provider.packageName, 0)
                    packageManager.getApplicationLabel(application).toString()
                } catch (_: Exception) {
                    info.provider.packageName
                }
                val preview = try {
                    info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0)
                } catch (_: Exception) {
                    null
                }
                val spans = placementSpans(info, cellWidthDp, cellHeightDp, cellGapDp)

                WidgetProviderItem(
                    providerInfo = info,
                    label = label,
                    appName = appName,
                    packageName = info.provider.packageName,
                    preview = preview,
                    minSpanX = spans.first,
                    minSpanY = spans.second
                )
            } catch (_: Exception) {
                null
            }
        }.sortedWith(compareBy({ it.appName.lowercase() }, { it.label.lowercase() }))
    }

    /** The cells a provider asks for when it is first dropped on the home grid. */
    fun placementSpans(
        info: AppWidgetProviderInfo,
        cellWidthDp: Int,
        cellHeightDp: Int,
        cellGapDp: Int
    ): Pair<Int, Int> =
        spansFor(info.minWidth, info.minHeight, cellWidthDp, cellHeightDp, cellGapDp)

    /**
     * The smallest placement a widget may be resized to, in cells.
     *
     * A provider that offers resizing names the smallest size it can be drawn at, and that is the
     * floor. A provider that does not offer resizing has no such size: it only reports the size it
     * would like to be placed at as its `minWidth`/`minHeight`, and using that as a floor would pin
     * the widget at whatever size it already had — the handles could then never make it smaller.
     * Such a widget goes down to a single cell, and the handles grow it back if it looks too small.
     */
    fun minimumResizeSpans(
        info: AppWidgetProviderInfo,
        cellWidthDp: Int,
        cellHeightDp: Int,
        cellGapDp: Int
    ): Pair<Int, Int> {
        val resizes = info.resizeMode != 0
        return spansFor(
            if (resizes) info.minResizeWidth else 0,
            if (resizes) info.minResizeHeight else 0,
            cellWidthDp,
            cellHeightDp,
            cellGapDp
        )
    }

    private fun spansFor(
        widthDp: Int,
        heightDp: Int,
        cellWidthDp: Int,
        cellHeightDp: Int,
        cellGapDp: Int
    ): Pair<Int, Int> =
        HomeGeometry.spanFor(widthDp, cellWidthDp, cellGapDp).coerceAtMost(MAX_SPAN) to
            HomeGeometry.spanFor(heightDp, cellHeightDp, cellGapDp).coerceAtMost(MAX_SPAN)

    /** Widgets never take more than this many cells of a grid along one axis. */
    private const val MAX_SPAN = 8
}
