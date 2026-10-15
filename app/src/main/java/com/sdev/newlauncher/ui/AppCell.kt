package com.sdev.newlauncher.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.sdev.newlauncher.R
import com.sdev.newlauncher.data.AppInfo

/**
 * Shared binding for a single app cell. Every drawer layout (grid, list, pages, A–Z,
 * custom order) plus the home and dock grids render through this, so the settings
 * (labels, icon size) behave identically everywhere.
 *
 * [cellWidthPx] and [iconSizePx] of `0` leave the layout's declared size untouched.
 */
internal object AppCell {

    fun bind(
        itemView: View,
        app: AppInfo,
        showLabels: Boolean,
        iconSizePx: Int,
        cellWidthPx: Int,
        onClick: (AppInfo) -> Unit,
        onLongClick: (AppInfo, View) -> Unit,
        onDragStart: ((AppInfo, View) -> Unit)? = null
    ) {
        val icon = itemView.findViewById<ImageView>(R.id.app_icon)
        val label = itemView.findViewById<TextView>(R.id.app_label)

        icon?.setImageDrawable(app.icon)
        label?.text = app.label
        label?.visibility = if (showLabels) View.VISIBLE else View.GONE

        if (icon != null && iconSizePx > 0) {
            val lp = icon.layoutParams
            if (lp.width != iconSizePx || lp.height != iconSizePx) {
                lp.width = iconSizePx
                lp.height = iconSizePx
                icon.layoutParams = lp
            }
        }

        if (cellWidthPx > 0) {
            val lp = itemView.layoutParams
            if (lp != null && lp.width != cellWidthPx) {
                lp.width = cellWidthPx
                itemView.layoutParams = lp
            }
        }

        itemView.contentDescription = app.label
        itemView.setOnClickListener { onClick(app) }
        itemView.setOnLongClickListener { view ->
            // The drawer lifts the icon into a drag; releasing it without moving still opens the
            // menu, which the host decides once the drag ends.
            if (onDragStart != null) onDragStart.invoke(app, view) else onLongClick(app, view)
            true
        }
    }
}
