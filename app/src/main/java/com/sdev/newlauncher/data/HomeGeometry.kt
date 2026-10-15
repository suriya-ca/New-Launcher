package com.sdev.newlauncher.data

import kotlin.math.roundToInt

/**
 * Pure placement maths for the home grid. Kept free of Android dependencies so the drop rules
 * (does it fit, where is the nearest free cell) are easy to reason about and reuse.
 */
object HomeGeometry {

    /**
     * Gap between two adjoining home cells, in dp. Lives here because the widget picker and the
     * resize limits both have to allow for it when turning a declared size into whole cells.
     */
    const val CELL_GAP_DP = 4

    /** A resolved top-left cell on a given page. */
    data class Slot(val page: Int, val row: Int, val col: Int)

    /**
     * Whole cells an item of [sizePx] covers on a grid whose cells are [cellPx] across and sit
     * [gapPx] apart, all three in the same unit (dp for a provider's declaration, pixels for the
     * workspace).
     *
     * The size is rounded rather than rounded up, so a widget a shade wider than one cell — the
     * common "1×1 that asks for a little breathing room" case — lands in one cell instead of two.
     * A widget that ends up smaller than it asked for can be grown with the resize handles; one
     * dropped a whole cell too wide could not be shrunk without them.
     */
    fun spanFor(sizePx: Int, cellPx: Int, gapPx: Int = 0): Int {
        if (sizePx <= 0) return 1
        val cell = cellPx.coerceAtLeast(1)
        val gap = gapPx.coerceAtLeast(0)
        return ((sizePx + gap).toDouble() / (cell + gap)).roundToInt().coerceAtLeast(1)
    }

    /** True when [candidate] sits inside the grid and overlaps no other item in [items]. */
    fun fits(items: List<HomeItem>, candidate: HomeItem, columns: Int, rows: Int): Boolean {
        if (candidate.col < 0 || candidate.row < 0) return false
        if (candidate.colEnd > columns || candidate.rowEnd > rows) return false
        return items.none { it.id != candidate.id && it.overlaps(candidate) }
    }

    /**
     * The first row-major free cell for a [colSpan]×[rowSpan] item, searching the current pages
     * first and — when [allowNewPage] — falling back to the top-left corner of a fresh page.
     */
    fun firstFreeSlot(
        items: List<HomeItem>,
        columns: Int,
        rows: Int,
        colSpan: Int = 1,
        rowSpan: Int = 1,
        pages: Int = 1,
        allowNewPage: Boolean = true
    ): Slot? {
        for (page in 0 until pages.coerceAtLeast(1)) {
            freeSlotOnPage(items, columns, rows, page, colSpan, rowSpan)?.let { return it }
        }
        return if (allowNewPage) Slot(pages.coerceAtLeast(1), 0, 0) else null
    }

    /** The first row-major free cell on [page], or null when that page has no room left. */
    fun freeSlotOnPage(
        items: List<HomeItem>,
        columns: Int,
        rows: Int,
        page: Int,
        colSpan: Int = 1,
        rowSpan: Int = 1
    ): Slot? {
        for (row in 0..(rows - rowSpan)) {
            for (col in 0..(columns - colSpan)) {
                val candidate = slotCandidate(page, row, col, rowSpan, colSpan)
                if (fits(items, candidate, columns, rows)) return Slot(page, row, col)
            }
        }
        return null
    }

    /** The free cell nearest to `(row, col)` on [page], ignoring any item with [excludeId]. */
    fun nearestFreeSlot(
        items: List<HomeItem>,
        columns: Int,
        rows: Int,
        page: Int,
        row: Int,
        col: Int,
        colSpan: Int = 1,
        rowSpan: Int = 1,
        excludeId: String? = null
    ): Slot? {
        val others = if (excludeId == null) items else items.filterNot { it.id == excludeId }
        var best: Slot? = null
        var bestDistance = Int.MAX_VALUE
        for (r in 0..(rows - rowSpan)) {
            for (c in 0..(columns - colSpan)) {
                val candidate = slotCandidate(page, r, c, rowSpan, colSpan)
                if (!fits(others, candidate, columns, rows)) continue
                val distance = (r - row) * (r - row) + (c - col) * (c - col)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = Slot(page, r, c)
                }
            }
        }
        return best
    }

    private fun slotCandidate(
        page: Int,
        row: Int,
        col: Int,
        rowSpan: Int,
        colSpan: Int
    ) = HomeItem(
        packageName = "",
        page = page,
        row = row,
        col = col,
        rowSpan = rowSpan,
        colSpan = colSpan
    )
}
