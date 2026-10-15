package com.sdev.newlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sdev.newlauncher.R
import com.sdev.newlauncher.data.AppInfo

/** One row in the A–Z drawer: either a letter header or an app. */
sealed interface DrawerRow {
    data class Header(val letter: String) : DrawerRow
    data class App(val app: AppInfo) : DrawerRow
}

/**
 * Adapter for the A–Z drawer layout. It renders letter headers interleaved with apps;
 * the host uses a [androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup] so
 * headers span the full width, and [StickyHeaderDecoration] pins the current section.
 */
class SectionedAppAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo, View) -> Unit,
    private val onAppDragStart: ((AppInfo, View) -> Unit)? = null
) : ListAdapter<DrawerRow, RecyclerView.ViewHolder>(DIFF) {

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

    override fun getItemViewType(position: Int): Int =
        if (getRow(position) is DrawerRow.Header) TYPE_HEADER else TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_letter_header, parent, false))
        } else {
            AppViewHolder(inflater.inflate(R.layout.item_app, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getRow(position)) {
            is DrawerRow.Header -> (holder as HeaderViewHolder).bind(row.letter)
            is DrawerRow.App -> (holder as AppViewHolder).bind(row.app)
        }
    }

    fun isHeader(position: Int): Boolean = getRowOrNull(position) is DrawerRow.Header

    /** The row at [position], or `null` when the position is out of range. */
    fun rowAt(position: Int): DrawerRow? = getRowOrNull(position)

    /** The text of the header at [position], or `null` when it is not a header. */
    fun headerTextAt(position: Int): String? =
        (getRowOrNull(position) as? DrawerRow.Header)?.letter

    /** Row index of the header that owns [position], or `-1` when there is none. */
    fun headerPositionFor(position: Int): Int {
        if (position !in 0 until itemCount) return -1
        for (i in position downTo 0) {
            if (getRow(i) is DrawerRow.Header) return i
        }
        return -1
    }

    /** Row index of the first header after [position], or `-1` when there is none. */
    fun nextHeaderPosition(position: Int): Int {
        for (i in (position + 1) until itemCount) {
            if (getRow(i) is DrawerRow.Header) return i
        }
        return -1
    }

    private fun getRow(position: Int): DrawerRow = getItem(position)

    private fun getRowOrNull(position: Int): DrawerRow? =
        if (position in 0 until itemCount) getItem(position) else null

    private class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text: TextView = itemView.findViewById(R.id.letter_header)

        fun bind(letter: String) {
            text.text = letter
        }
    }

    private inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(app: AppInfo) {
            AppCell.bind(
                itemView,
                app,
                showLabels,
                iconSizePx,
                0,
                onAppClick,
                onAppLongClick,
                onAppDragStart
            )
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_APP = 1

        val DIFF = object : DiffUtil.ItemCallback<DrawerRow>() {
            override fun areItemsTheSame(oldItem: DrawerRow, newItem: DrawerRow): Boolean = when {
                oldItem is DrawerRow.Header && newItem is DrawerRow.Header ->
                    oldItem.letter == newItem.letter

                oldItem is DrawerRow.App && newItem is DrawerRow.App ->
                    oldItem.app.key == newItem.app.key

                else -> false
            }

            override fun areContentsTheSame(oldItem: DrawerRow, newItem: DrawerRow): Boolean =
                oldItem == newItem
        }
    }
}
