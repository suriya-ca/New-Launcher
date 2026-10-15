package com.sdev.newlauncher.data

/**
 * What the home grid allows while a hosted widget is being resized: how small it may get, and
 * which axes it may grow along (a provider's `resizeMode`).
 */
data class WidgetResizeBounds(
    val minColSpan: Int,
    val minRowSpan: Int,
    val horizontal: Boolean,
    val vertical: Boolean
)
