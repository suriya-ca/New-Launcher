package com.sdev.newlauncher.ui

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Hosts a third-party widget over one home grid placement.
 *
 * The widget fills its placement — the cells it spans — and its provider lays the content out for
 * the size it is told, so nothing floats in empty margins. Shrinking or growing a placement is the
 * resize handles' job, not the cell's.
 *
 * Edit mode needs a long press on the item to start a drag and a tap to select it for resizing,
 * but an [AppWidgetHostView] swallows both for the widget's own buttons. So a transparent overlay
 * sits on top of the widget and only becomes touchable while the home screen is being edited.
 */
class WidgetCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Transparent touch catcher, visible only in edit mode. */
    val overlay: View = View(context).apply {
        isClickable = true
        isFocusable = true
        visibility = GONE
    }

    var hostView: AppWidgetHostView? = null
        private set

    init {
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Puts [host] behind the edit overlay, filling the placement. */
    fun attachHost(host: AppWidgetHostView) {
        hostView = host
        host.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(host, 0)
    }

    /** Adds a stand-in view shown when the widget's provider is gone. */
    fun setPlaceholder(placeholder: View) {
        placeholder.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(placeholder, 0)
    }

    fun setEditing(editing: Boolean) {
        overlay.visibility = if (editing) VISIBLE else GONE
    }
}
