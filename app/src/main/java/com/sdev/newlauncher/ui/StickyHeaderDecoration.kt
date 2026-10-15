package com.sdev.newlauncher.ui

import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdev.newlauncher.R

/**
 * Pins the current A–Z section header to the top of the list while its apps scroll by,
 * and pushes it up as the next section's header arrives.
 */
class StickyHeaderDecoration(
    private val adapter: SectionedAppAdapter
) : RecyclerView.ItemDecoration() {

    private var headerView: View? = null

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (adapter.itemCount == 0) return
        val topChild = parent.getChildAt(0) ?: return
        val topPosition = parent.getChildAdapterPosition(topChild)
        if (topPosition == RecyclerView.NO_POSITION) return

        val headerPosition = adapter.headerPositionFor(topPosition)
        if (headerPosition < 0) return
        val letter = adapter.headerTextAt(headerPosition) ?: return

        val view = headerView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_letter_header, parent, false)
            .also { headerView = it }
        view.findViewById<TextView>(R.id.letter_header).text = letter

        val availableWidth = (parent.width - parent.paddingLeft - parent.paddingRight).coerceAtLeast(0)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        var top = parent.paddingTop

        // Slide the pinned header up when the next section's header reaches it.
        val nextHeaderPosition = adapter.nextHeaderPosition(topPosition)
        if (nextHeaderPosition >= 0) {
            val nextView = parent.findViewHolderForAdapterPosition(nextHeaderPosition)?.itemView
            if (nextView != null && nextView.top < top + view.measuredHeight) {
                top = nextView.top - view.measuredHeight
            }
        }

        val restore = canvas.save()
        canvas.translate(parent.paddingLeft.toFloat(), top.toFloat())
        view.draw(canvas)
        canvas.restoreToCount(restore)
    }
}
