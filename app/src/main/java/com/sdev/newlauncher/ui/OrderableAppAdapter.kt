package com.sdev.newlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sdev.newlauncher.R
import com.sdev.newlauncher.data.AppInfo
import com.sdev.newlauncher.data.IconScope

/**
 * Adapter for the custom-order drawer layout. Unlike [AppAdapter] it owns a mutable
 * list so a drag that stays over the list can move cells in place with `notifyItemMoved`.
 */
class OrderableAppAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo, View) -> Unit,
    private val onAppDragStart: ((AppInfo, View) -> Unit)? = null
) : RecyclerView.Adapter<OrderableAppAdapter.AppViewHolder>() {

    private val items = mutableListOf<AppInfo>()

    var showLabels: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    var iconSizePx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    /** The surface these cells are drawn on, which decides shape and icon-pack application. */
    var iconScope: IconScope = IconScope.HOME

    fun submit(apps: List<AppInfo>) {
        items.clear()
        items.addAll(apps)
        notifyDataSetChanged()
    }

    /** Moves an item within the list and reports the change to the RecyclerView. */
    fun moveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in items.indices || toPosition !in items.indices) return false
        val moved = items.removeAt(fromPosition)
        items.add(toPosition, moved)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    /** The current order, for persistence once a drag finishes. */
    fun order(): List<AppInfo> = items.toList()

    /** Position of [app] in the current order, or `-1` when it is not in it. */
    fun indexOf(app: AppInfo): Int = items.indexOfFirst { it.key == app.key }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(app: AppInfo) {
            AppCell.bind(
                itemView,
                app,
                showLabels,
                iconSizePx,
                0,
                onAppClick,
                onAppLongClick,
                onAppDragStart,
                iconScope
            )
        }
    }
}
