package com.sdev.newlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sdev.newlauncher.R
import com.sdev.newlauncher.data.AppInfo
import com.sdev.newlauncher.data.IconScope

/**
 * RecyclerView adapter rendering an app as an icon (+ optional label) cell.
 *
 * @param itemLayoutRes layout to inflate for each cell; must contain `@id/app_icon`
 *   and may contain `@id/app_label`.
 * @param onAppClick invoked on a normal tap.
 * @param onAppLongClick invoked on a long press (used to show the app's context menu).
 *
 * [showLabels], [iconSizePx] and [cellWidthPx] are live settings applied on every bind;
 * changing them rebinds the visible cells.
 */
class AppAdapter(
    @param:LayoutRes private val itemLayoutRes: Int = R.layout.item_app,
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo, View) -> Unit,
    private val onAppDragStart: ((AppInfo, View) -> Unit)? = null
) : ListAdapter<AppInfo, AppAdapter.AppViewHolder>(DIFF) {

    /** Whether app labels are drawn under home/drawer icons. */
    var showLabels: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    /** Icon edge length in px; `0` keeps the layout's declared size. */
    var iconSizePx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    /** Fixed cell width in px; `0` keeps the layout's declared width (used by the dock). */
    var cellWidthPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    /** The surface these cells are drawn on, which decides shape and icon-pack application. */
    var iconScope: IconScope = IconScope.HOME

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(itemLayoutRes, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(app: AppInfo) {
            AppCell.bind(
                itemView,
                app,
                showLabels,
                iconSizePx,
                cellWidthPx,
                onAppClick,
                onAppLongClick,
                onAppDragStart,
                iconScope
            )
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem.label == newItem.label && oldItem.key == newItem.key
        }
    }
}
