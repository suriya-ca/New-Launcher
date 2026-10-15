package com.sdev.newlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdev.newlauncher.R
import com.sdev.newlauncher.data.AppInfo

/**
 * Backs the horizontally paged drawer layout: each page is a fixed grid of apps that fits
 * on screen, so the pages themselves only scroll sideways.
 */
class DrawerPagerAdapter(
    private val columns: Int,
    private val columnSpacingPx: Int,
    private val rowSpacingPx: Int,
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo, View) -> Unit,
    private val onAppDragStart: ((AppInfo, View) -> Unit)? = null
) : RecyclerView.Adapter<DrawerPagerAdapter.PageViewHolder>() {

    private var pages: List<List<AppInfo>> = emptyList()

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

    fun submitPages(pages: List<List<AppInfo>>) {
        this.pages = pages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_drawer_page, parent, false)
        val grid = view.findViewById<RecyclerView>(R.id.page_grid)
        grid.layoutManager = GridLayoutManager(parent.context, columns)
        grid.isNestedScrollingEnabled = false
        grid.addItemDecoration(GridSpacingItemDecoration(columnSpacingPx, rowSpacingPx))
        val adapter = AppAdapter(
            onAppClick = onAppClick,
            onAppLongClick = onAppLongClick,
            onAppDragStart = onAppDragStart
        )
        grid.adapter = adapter
        return PageViewHolder(view, adapter)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.adapter.showLabels = showLabels
        holder.adapter.iconSizePx = iconSizePx
        holder.adapter.submitList(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    class PageViewHolder(view: View, val adapter: AppAdapter) : RecyclerView.ViewHolder(view)
}
