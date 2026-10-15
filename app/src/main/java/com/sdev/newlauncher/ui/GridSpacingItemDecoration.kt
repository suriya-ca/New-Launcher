package com.sdev.newlauncher.ui

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Keeps the drawer grid's column gaps uniform and inserts the configurable row gap.
 *
 * Columns: each cell is inset according to its *real* column index, so the gap between
 * adjacent columns equals [columnSpacingPx] while the outer columns stay flush with the
 * RecyclerView's own padding — which supplies the very same gap. The index is read from the
 * [GridLayoutManager] instead of being derived from the adapter position, so a full-width
 * section header no longer shifts the columns that follow it.
 *
 * Rows: a plain `bottom` inset on every cell, which needs no position bookkeeping at all:
 * consecutive rows are always [rowSpacingPx] apart and the first row keeps the grid's
 * top padding.
 *
 * @param columnSpacingPx the gap between adjacent columns (and, via the grid padding, the
 *   outer edges); `0` lays the columns out edge to edge.
 * @param rowSpacingPx the gap between adjacent rows; `0` stacks them.
 */
class GridSpacingItemDecoration(
    private val columnSpacingPx: Int,
    private val rowSpacingPx: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        outRect.setEmpty()

        val layoutManager = parent.layoutManager as? GridLayoutManager ?: run {
            outRect.bottom = rowSpacingPx
            return
        }
        val span = layoutManager.spanCount.coerceAtLeast(1)

        if (span > 1 && columnSpacingPx > 0) {
            val position = parent.getChildAdapterPosition(view)
            if (position != RecyclerView.NO_POSITION) {
                // Full-width items (section headers) keep the whole row to themselves.
                val lookup = layoutManager.spanSizeLookup
                if (lookup.getSpanSize(position) < span) {
                    val index = lookup.getSpanIndex(position, span).coerceIn(0, span - 1)
                    outRect.left = index * columnSpacingPx / span
                    outRect.right = columnSpacingPx - (index + 1) * columnSpacingPx / span
                }
            }
        }

        outRect.bottom = rowSpacingPx
    }
}
