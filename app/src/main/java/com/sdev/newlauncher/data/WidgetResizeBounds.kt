package com.sdev.newlauncher.data

/**
 * What the home grid allows while a hosted widget is being resized: how small it may get, and
 * which axes may be dragged.
 *
 * Both axes are offered for every widget: a provider's `resizeMode` only says which directions its
 * author laid out for, and honouring it left common widgets frozen on one axis. The two flags stay
 * here so a provider's own limits can be honoured again without touching every caller.
 */
data class WidgetResizeBounds(
    val minColSpan: Int,
    val minRowSpan: Int,
    val horizontal: Boolean,
    val vertical: Boolean
)
