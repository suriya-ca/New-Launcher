package com.sdev.newlauncher.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.DragEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.sdev.newlauncher.R
import com.sdev.newlauncher.data.HomeGeometry
import com.sdev.newlauncher.data.HomeItem
import com.sdev.newlauncher.data.WidgetResizeBounds
import kotlin.math.abs
import kotlin.math.floor

/**
 * The home screen surface: a horizontally paged grid of free-positioned items.
 *
 * Every page is laid out side by side inside this single view and switched with a snapping
 * horizontal scroll. Unlike a [androidx.viewpager2.widget.ViewPager2] this keeps all children
 * attached at all times, which matters because widget host views must never be recycled.
 *
 * The workspace only reports taps on empty space and drops; everything about what an item
 * *is* (and what happens when it is tapped) is owned by the caller through [itemViewFactory]
 * and [itemBinder]. Resizing is the exception: it is pure placement maths, so the handles are
 * drawn and dragged here, and only the committed span is handed back through [onItemResize].
 */
class Workspace @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /** Payload carried through a drag that this workspace should accept. */
    class DragState(val item: HomeItem)

    var columns: Int = 4
        set(value) {
            val valid = value.coerceAtLeast(1)
            if (field != valid) {
                field = valid
                requestLayout()
            }
        }

    var rows: Int = 5
        set(value) {
            val valid = value.coerceAtLeast(1)
            if (field != valid) {
                field = valid
                requestLayout()
            }
        }

    var pageCount: Int = 1
        set(value) {
            val valid = value.coerceAtLeast(1)
            if (field != valid) {
                field = valid
                clampScroll()
                requestLayout()
            }
        }

    /** Gap between cells, in pixels. */
    var cellGapPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** While true, every item wobbles and long-pressing one starts a drag instead of a menu. */
    var editing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            for ((view, _) in entries) {
                if (value) Jiggle.start(view) else Jiggle.stop(view)
            }
            if (!value) selectedItemId = null
            invalidate()
        }

    /** Creates the view for a not-yet-rendered item. */
    var itemViewFactory: ((HomeItem) -> View)? = null

    /** Binds (or re-binds) an existing item view. */
    var itemBinder: ((View, HomeItem) -> Unit)? = null

    /** Called when an item view is discarded, so widgets can be released. */
    var itemRecycler: ((View, HomeItem) -> Unit)? = null

    /** Long press on a cell that holds no item. */
    var onEmptyLongPress: (() -> Unit)? = null

    var onPageChanged: ((Int) -> Unit)? = null

    /** `(itemId, page, row, col)` for a completed drop on this workspace. */
    var onItemDrop: ((String, Int, Int, Int) -> Unit)? = null

    /** True while a drag that this workspace accepts is in flight. */
    var onDragStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Screen position of every drag update, so a host can tell a real drag from a finger that
     * only pressed and held — the two end the same way but mean different things.
     */
    var onDragLocation: ((Int, Int) -> Unit)? = null

    /**
     * A drag hovered past the last page: the host appends a page and refreshes, and the workspace
     * scrolls on to it on its next edge tick.
     */
    var onAddPageRequested: (() -> Unit)? = null

    /** `(from, to)` once a page has been dragged to a new position. */
    var onPagesReordered: ((Int, Int) -> Unit)? = null

    /**
     * Called after each layout pass for every item, so callers can sync content that depends on
     * the measured cell size (widgets, mainly).
     */
    var onItemLaidOut: ((View, HomeItem) -> Unit)? = null

    /** How far the widget behind an item may be resized, or null when it must keep its span. */
    var resizeBounds: ((HomeItem) -> WidgetResizeBounds?)? = null

    /** Called with the same placement and a new span once a resize gesture is released. */
    var onItemResize: ((HomeItem) -> Unit)? = null

    /** The item whose resize handles are on screen, if any. */
    var selectedItemId: String? = null
        private set

    private val entries = ArrayList<Pair<View, HomeItem>>()

    private var dragSource: View? = null
    private val placeholderDrawable = ContextCompat.getDrawable(context, R.drawable.bg_cell_placeholder)
    private val invalidPlaceholderDrawable =
        ContextCompat.getDrawable(context, R.drawable.bg_cell_placeholder_invalid)
    private val placeholderBounds = Rect()
    private var placeholderVisible = false

    private val selectionBounds = Rect()
    private val previewBounds = Rect()
    private var previewVisible = false
    private var previewValid = false

    private var resizing = false
    private var resizeItem: HomeItem? = null
    private var resizeCandidate: HomeItem? = null
    private var resizeLimit: WidgetResizeBounds? = null
    private var resizeCorner = CORNER_NONE

    /** Translucent white reads on any wallpaper. */
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99FFFFFF.toInt()
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    /** Faint fill behind the frame drawn around a page while it is being dragged. */
    private val pageFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1FFFFFFF
    }

    /** Barely-there fill showing where the page the user is editing begins and ends. */
    private val editingPageFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x0FFFFFFF
    }

    /** Dashed frame around that page: a boundary to edit inside, not a highlight. */
    private val editingPageFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x59FFFFFF.toInt()
        strokeWidth = resources.displayMetrics.density * 2f
        pathEffect = DashPathEffect(floatArrayOf(dp(6).toFloat(), dp(7).toFloat()), 0f)
    }

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var paging = false
    private var lastReportedPage = 0

    /** The page the current gesture started on; a fling always moves at least one page away from it. */
    private var gestureStartPage = 0

    /** True when this gesture cut a running snap short, so the release has to settle the page. */
    private var settleAfterAnimation = false

    /** In-place page reordering: the page being dragged and how far it has followed the finger. */
    private var pageDragging = false
    private var pageDragFrom = 0
    private var pageDragOffsetX = 0f

    private val longPressRunnable = Runnable {
        // In edit mode an empty spot has no menu, so the same press starts a page drag instead.
        if (editing) startPageDrag() else onEmptyLongPress?.invoke()
    }

    init {
        setWillNotDraw(false)
        setOnDragListener { _, event -> handleDrag(event) }
    }

    // ---------------------------------------------------------------- content

    /**
     * Reconciles the rendered children with [items]. Views are reused by item id so widget host
     * views survive re-layout, and removed views are handed to [itemRecycler].
     */
    fun submit(items: List<HomeItem>) {
        val wanted = items.associateBy { it.id }

        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val (view, item) = iterator.next()
            if (item.id !in wanted) {
                iterator.remove()
                removeView(view)
                itemRecycler?.invoke(view, item)
            }
        }

        for (item in items) {
            val index = entries.indexOfFirst { it.second.id == item.id }
            if (index < 0) {
                val view = itemViewFactory?.invoke(item) ?: continue
                addView(view)
                entries.add(view to item)
                if (editing) Jiggle.start(view)
            } else {
                entries[index] = entries[index].first to item
            }
        }

        for ((view, item) in entries) itemBinder?.invoke(view, item)
        // A page reorder preview is only ever translation; the placement is what counts.
        if (!pageDragging) for ((view, _) in entries) view.translationX = 0f
        if (selectedItemId != null && items.none { it.id == selectedItemId }) selectedItemId = null
        requestLayout()
        invalidate()
    }

    /** The currently settled page. */
    fun currentPage(): Int {
        val width = width
        if (width <= 0) return 0
        return ((scrollX + width / 2) / width).coerceIn(0, pageCount - 1)
    }

    /** Animates to [page]. */
    fun goToPage(page: Int) {
        val width = width
        if (width <= 0) return
        val target = page.coerceIn(0, pageCount - 1)
        scroller.startScroll(scrollX, 0, target * width - scrollX, 0, PAGE_ANIM_MS)
        invalidate()
    }

    /** Starts a drag of [item] using [source] as the drag origin. */
    fun startItemDrag(item: HomeItem, source: View) {
        select(null)
        dragSource = source
        val clip = ClipData.newPlainText(DRAG_LABEL, item.id)
        source.startDragAndDrop(clip, View.DragShadowBuilder(source), DragState(item), 0)
        source.alpha = 0f
        onDragStateChanged?.invoke(true)
    }

    // ---------------------------------------------------------------- layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewWidth = MeasureSpec.getSize(widthMeasureSpec)
        val viewHeight = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(viewWidth, viewHeight)

        val cellWidth = cellWidth(viewWidth)
        val cellHeight = cellHeight(viewHeight)
        for ((view, item) in entries) {
            val width = item.colSpan * cellWidth + (item.colSpan - 1) * cellGapPx
            val height = item.rowSpan * cellHeight + (item.rowSpan - 1) * cellGapPx
            view.measure(
                MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height.coerceAtLeast(0), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val bounds = Rect()
        for ((view, item) in entries) {
            itemBounds(item, bounds)
            view.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            onItemLaidOut?.invoke(view, item)
        }
    }

    private fun cellWidth(viewWidth: Int): Int {
        val usable = viewWidth - paddingLeft - paddingRight - (columns - 1) * cellGapPx
        return (usable / columns).coerceAtLeast(1)
    }

    private fun cellHeight(viewHeight: Int): Int {
        val usable = viewHeight - paddingTop - paddingBottom - (rows - 1) * cellGapPx
        return (usable / rows).coerceAtLeast(1)
    }

    /** Width of one grid cell, in pixels. */
    fun cellWidthPx(): Int = cellWidth(width)

    /** Height of one grid cell, in pixels. */
    fun cellHeightPx(): Int = cellHeight(height)

    // ---------------------------------------------------------------- paging

    override fun scrollTo(x: Int, y: Int) {
        val max = ((pageCount - 1) * width).coerceAtLeast(0)
        super.scrollTo(x.coerceIn(0, max), 0)
    }

    private fun clampScroll() {
        scrollTo(scrollX, 0)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val width = width
        if (width <= 0) return
        val page = ((l + width / 2) / width).coerceIn(0, pageCount - 1)
        if (page != lastReportedPage) {
            lastReportedPage = page
            onPageChanged?.invoke(page)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                paging = false
                gestureStartPage = currentPage()
                // A snap that is still running keeps moving the page under the finger, which reads
                // as the swipe fighting an invisible hand. Stop it, and settle on release instead.
                settleAfterAnimation = !scroller.isFinished
                scroller.abortAnimation()
                beginTrackingVelocity(ev)
                // A handle wins over the item under it: that gesture resizes rather than pages.
                if (editing) {
                    val item = selectedItem()
                    if (item != null) {
                        val corner = handleAt(item, ev.x, ev.y)
                        if (corner != CORNER_NONE && beginResize(item, corner)) return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizing) return false
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (!paging && dx > touchSlop && dx > dy) {
                    paging = true
                    removeCallbacks(longPressRunnable)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> paging = false
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                paging = false
                gestureStartPage = currentPage()
                settleAfterAnimation = !scroller.isFinished
                scroller.abortAnimation()
                beginTrackingVelocity(event)
                // A resize was started from [onInterceptTouchEvent]; it owns the whole gesture.
                if (resizing) return true
                postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizing) {
                    updateResize(event.x, event.y)
                    return true
                }
                if (pageDragging) {
                    updatePageDrag(event.x)
                    return true
                }
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > touchSlop || dy > touchSlop) removeCallbacks(longPressRunnable)
                if (!paging && dx > touchSlop && dx > dy) paging = true
                if (paging) {
                    trackVelocity(event)
                    scrollBy((lastX - event.x).toInt(), 0)
                    lastX = event.x
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (resizing) {
                    finishResize(event.actionMasked == MotionEvent.ACTION_UP)
                    return true
                }
                if (pageDragging) {
                    finishPageDrag(event.actionMasked == MotionEvent.ACTION_UP)
                    return true
                }
                if (paging) {
                    paging = false
                    snapToNearestPage(velocityX())
                } else {
                    // The touch cut a snap short, so this page still has to find a boundary.
                    if (settleAfterAnimation) snapToNearestPage(0f)
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop
                    ) {
                        // A tap on empty space puts the resize handles away.
                        select(null)
                    }
                }
                settleAfterAnimation = false
                stopTrackingVelocity()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Starts velocity tracking at the gesture's own down event, so the release snap has real data. */
    private fun beginTrackingVelocity(event: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.clear()
        tracker.addMovement(event)
    }

    private fun trackVelocity(event: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)
    }

    private fun velocityX(): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.computeCurrentVelocity(1000)
        return tracker.xVelocity
    }

    private fun stopTrackingVelocity() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun snapToNearestPage(velocityX: Float) {
        val viewWidth = width
        if (viewWidth <= 0) return
        val nearest = floor(scrollX / viewWidth.toFloat() + 0.5f).toInt()
        // A flick has to move the page at least one step in the direction it was flicked, even
        // when the finger never crossed the midpoint; a drag that already went further wins.
        val page = when {
            velocityX <= -FLING_VELOCITY -> maxOf(nearest, gestureStartPage + 1)
            velocityX >= FLING_VELOCITY -> minOf(nearest, gestureStartPage - 1)
            else -> nearest
        }.coerceIn(0, pageCount - 1)
        scroller.startScroll(scrollX, 0, page * viewWidth - scrollX, 0, PAGE_ANIM_MS)
        invalidate()
    }

    // ---------------------------------------------------------------- selection & resizing

    /** Puts the resize handles on the item with [id], or takes them off when null. */
    fun select(id: String?) {
        if (selectedItemId == id) return
        selectedItemId = id
        invalidate()
    }

    /** Tapping the selected item again puts its handles away. */
    fun toggleSelection(id: String) {
        select(if (selectedItemId == id) null else id)
    }

    /** The item whose handles are on screen, as long as it is still on the home screen. */
    private fun selectedItem(): HomeItem? {
        val id = selectedItemId ?: return null
        return entries.firstOrNull { it.second.id == id }?.second
    }

    /** Content-space bounds of [item], which is where [onLayout] puts its view too. */
    private fun itemBounds(item: HomeItem, out: Rect) {
        val viewWidth = width
        val cellWidth = cellWidth(viewWidth)
        val cellHeight = cellHeight(height)
        val left = item.page * viewWidth + paddingLeft + item.col * (cellWidth + cellGapPx)
        val top = paddingTop + item.row * (cellHeight + cellGapPx)
        val right = left + item.colSpan * cellWidth + (item.colSpan - 1) * cellGapPx
        val bottom = top + item.rowSpan * cellHeight + (item.rowSpan - 1) * cellGapPx
        out.set(left, top, right, bottom)
    }

    /**
     * Which handle of [item] a view-local touch grabbed, or [CORNER_NONE]. The handles sit just
     * inside the corners and are capped at a quarter of the placement, so a small item keeps a
     * middle strip free for its own long press, and a handle never sits over a neighbour.
     */
    private fun handleAt(item: HomeItem, localX: Float, localY: Float): Int {
        val bounds = selectionBounds
        itemBounds(item, bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return CORNER_NONE
        val x = localX + scrollX
        val y = localY
        if (!bounds.contains(x.toInt(), y.toInt())) return CORNER_NONE

        val touch = handleTouchPx(bounds)
        val inset = dp(HANDLE_INSET_DP)
        val onLeft = x <= bounds.left + inset + touch
        val onRight = x >= bounds.right - inset - touch
        val onTop = y <= bounds.top + inset + touch
        val onBottom = y >= bounds.bottom - inset - touch

        return when {
            onLeft && onTop -> CORNER_TOP_LEFT
            onRight && onTop -> CORNER_TOP_RIGHT
            onLeft && onBottom -> CORNER_BOTTOM_LEFT
            onRight && onBottom -> CORNER_BOTTOM_RIGHT
            else -> CORNER_NONE
        }
    }

    /** Side of one handle's grab box. */
    private fun handleTouchPx(bounds: Rect): Int =
        minOf(dp(HANDLE_TOUCH_DP), bounds.width() / 4, bounds.height() / 4)
            .coerceAtLeast(dp(MIN_HANDLE_TOUCH_DP))

    private fun beginResize(item: HomeItem, corner: Int): Boolean {
        val limits = resizeBounds?.invoke(item) ?: return false
        if (!limits.horizontal && !limits.vertical) return false

        resizing = true
        resizeItem = item
        resizeCandidate = item
        resizeLimit = limits
        resizeCorner = corner
        previewVisible = false
        // The handle owns this gesture: no paging and no drawer fling while it is dragged.
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun updateResize(localX: Float, localY: Float) {
        val item = resizeItem ?: return
        val limits = resizeLimit ?: return

        val candidate = resized(item, limits, localX + scrollX, localY)
        resizeCandidate = candidate
        itemBounds(candidate, previewBounds)
        previewValid = canPlace(candidate)
        previewVisible = true
        invalidate()
    }

    /** The placement [item] becomes when the grabbed corner is pulled to `(contentX, contentY)`. */
    private fun resized(
        item: HomeItem,
        limits: WidgetResizeBounds,
        contentX: Float,
        contentY: Float
    ): HomeItem {
        val viewWidth = width
        val stepX = (cellWidth(viewWidth) + cellGapPx).coerceAtLeast(1)
        val stepY = (cellHeight(height) + cellGapPx).coerceAtLeast(1)
        val colUnder = floor((contentX - item.page * viewWidth - paddingLeft) / stepX).toInt()
        val rowUnder = floor((contentY - paddingTop) / stepY).toInt()

        var col = item.col
        var colSpan = item.colSpan
        var row = item.row
        var rowSpan = item.rowSpan

        if (limits.horizontal) {
            if (grabsLeft()) {
                val limit = (item.colEnd - limits.minColSpan).coerceAtLeast(0)
                col = colUnder.coerceIn(0, limit)
                colSpan = item.colEnd - col
            } else if (grabsRight()) {
                var end = colUnder + 1
                if (end < col + limits.minColSpan) end = col + limits.minColSpan
                colSpan = (end.coerceAtMost(columns) - col).coerceAtLeast(1)
            }
        }
        if (limits.vertical) {
            if (grabsTop()) {
                val limit = (item.rowEnd - limits.minRowSpan).coerceAtLeast(0)
                row = rowUnder.coerceIn(0, limit)
                rowSpan = item.rowEnd - row
            } else if (grabsBottom()) {
                var end = rowUnder + 1
                if (end < row + limits.minRowSpan) end = row + limits.minRowSpan
                rowSpan = (end.coerceAtMost(rows) - row).coerceAtLeast(1)
            }
        }

        return item.copy(row = row, col = col, rowSpan = rowSpan, colSpan = colSpan)
    }

    private fun grabsLeft(): Boolean =
        resizeCorner == CORNER_TOP_LEFT || resizeCorner == CORNER_BOTTOM_LEFT

    private fun grabsRight(): Boolean =
        resizeCorner == CORNER_TOP_RIGHT || resizeCorner == CORNER_BOTTOM_RIGHT

    private fun grabsTop(): Boolean =
        resizeCorner == CORNER_TOP_LEFT || resizeCorner == CORNER_TOP_RIGHT

    private fun grabsBottom(): Boolean =
        resizeCorner == CORNER_BOTTOM_LEFT || resizeCorner == CORNER_BOTTOM_RIGHT

    /** The same rule as [HomeGeometry.fits], walked over the rendered items without copying them. */
    private fun canPlace(candidate: HomeItem): Boolean {
        if (candidate.col < 0 || candidate.row < 0) return false
        if (candidate.colEnd > columns || candidate.rowEnd > rows) return false
        return entries.none { it.second.id != candidate.id && it.second.overlaps(candidate) }
    }

    private fun finishResize(commit: Boolean) {
        val item = resizeItem
        val candidate = resizeCandidate
        val valid = previewValid

        resizing = false
        resizeItem = null
        resizeCandidate = null
        resizeLimit = null
        resizeCorner = CORNER_NONE
        previewVisible = false
        parent?.requestDisallowInterceptTouchEvent(false)

        if (commit && valid && item != null && candidate != null && candidate != item) {
            selectedItemId = candidate.id
            onItemResize?.invoke(candidate)
        }
        invalidate()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- drag & drop

    private fun handleDrag(event: DragEvent): Boolean {
        val state = event.localState as? DragState
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> state != null
            DragEvent.ACTION_DRAG_LOCATION -> {
                if (state != null) {
                    dragItem = state.item
                    dragX = event.x
                    dragY = event.y
                    val screen = IntArray(2)
                    getLocationOnScreen(screen)
                    onDragLocation?.invoke(
                        (event.x + screen[0]).toInt(),
                        (event.y + screen[1]).toInt()
                    )
                    updatePlaceholder(state.item, event.x, event.y)
                    updateEdgeFlip()
                }
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                hidePlaceholder()
                cancelEdgeFlip()
                true
            }
            DragEvent.ACTION_DROP -> {
                if (state == null) return false
                val slot = slotFor(state.item, event.x, event.y)
                hidePlaceholder()
                cancelEdgeFlip()
                if (slot != null) onItemDrop?.invoke(state.item.id, slot.page, slot.row, slot.col)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                hidePlaceholder()
                cancelEdgeFlip()
                dragItem = null
                dragSource?.alpha = 1f
                dragSource = null
                onDragStateChanged?.invoke(false)
                true
            }
            else -> false
        }
    }

    /** The clamped top-left cell for [item] if it were dropped at `(x, y)`. */
    private fun slotFor(item: HomeItem, x: Float, y: Float): HomeGeometry.Slot? {
        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val stepX = cellWidth(viewWidth) + cellGapPx
        val stepY = cellHeight(viewHeight) + cellGapPx
        if (stepX <= 0 || stepY <= 0) return null

        val contentX = x + scrollX - paddingLeft
        val page = (contentX / viewWidth).toInt().coerceIn(0, pageCount - 1)
        val localX = contentX - page * viewWidth
        val col = (localX / stepX).toInt()
        val row = ((y - paddingTop) / stepY).toInt()

        return HomeGeometry.Slot(
            page = page,
            row = row.coerceIn(0, (rows - item.rowSpan).coerceAtLeast(0)),
            col = col.coerceIn(0, (columns - item.colSpan).coerceAtLeast(0))
        )
    }

    private fun updatePlaceholder(item: HomeItem, x: Float, y: Float) {
        val slot = slotFor(item, x, y) ?: return
        itemBounds(
            item.copy(page = slot.page, row = slot.row, col = slot.col),
            placeholderBounds
        )
        placeholderVisible = true
        invalidate()
    }

    private fun hidePlaceholder() {
        if (!placeholderVisible) return
        placeholderVisible = false
        invalidate()
    }

    // ---------------------------------------------------------------- drag edge flipping

    /** The in-flight drag, and where it last was, so a page flip can re-place its placeholder. */
    private var dragItem: HomeItem? = null
    private var dragX = 0f
    private var dragY = 0f
    private var edgeFlipScheduled = false

    private val edgeFlipRunnable = Runnable {
        edgeFlipScheduled = false
        val item = dragItem ?: return@Runnable
        if (flipForDrag()) updatePlaceholder(item, dragX, dragY)
        if (edgeDirection(dragX) != 0) scheduleEdgeFlip(DRAG_FLIP_REPEAT_MS)
    }

    private fun updateEdgeFlip() {
        if (edgeDirection(dragX) == 0) cancelEdgeFlip() else scheduleEdgeFlip(DRAG_FLIP_FIRST_MS)
    }

    private fun scheduleEdgeFlip(delay: Long) {
        if (edgeFlipScheduled) return
        edgeFlipScheduled = true
        postDelayed(edgeFlipRunnable, delay)
    }

    private fun cancelEdgeFlip() {
        if (!edgeFlipScheduled) return
        edgeFlipScheduled = false
        removeCallbacks(edgeFlipRunnable)
    }

    /** `-1` while a drag sits in the left edge band, `1` in the right one, `0` in between. */
    private fun edgeDirection(x: Float): Int {
        val viewWidth = width
        if (viewWidth <= 0) return 0
        val band = dp(DRAG_EDGE_DP)
        return when {
            x < band -> -1
            x > viewWidth - band -> 1
            else -> 0
        }
    }

    /** Flips one page the way the drag is leaning, asking the host for a new page if it ran out. */
    private fun flipForDrag(): Boolean {
        val viewWidth = width
        if (viewWidth <= 0) return false
        val target = currentPage() + edgeDirection(dragX)
        if (target < 0) return false
        if (target >= pageCount) {
            // Past the last page: the host appends one; the next tick scrolls on to it.
            onAddPageRequested?.invoke()
            return false
        }
        scroller.abortAnimation()
        scrollTo(target * viewWidth, 0)
        return true
    }

    // ---------------------------------------------------------------- page reordering

    /** Lifts the page under an empty-space long press so it can be dragged somewhere else. */
    private fun startPageDrag() {
        if (pageDragging || width <= 0) return
        pageDragging = true
        pageDragFrom = currentPage()
        pageDragOffsetX = 0f
        select(null)
        // The page owns this gesture: no paging and no drawer fling while it is dragged.
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
    }

    private fun updatePageDrag(x: Float) {
        pageDragOffsetX = x - downX
        applyPageDragPreview()
    }

    /** The page a release right now would land on. */
    private fun pageDragTarget(): Int {
        val viewWidth = width
        if (viewWidth <= 0) return pageDragFrom
        val steps = floor(pageDragOffsetX / viewWidth + 0.5f).toInt()
        return (pageDragFrom + steps).coerceIn(0, pageCount - 1)
    }

    /** Slides the pages around the lifted one so the drop position is visible before releasing. */
    private fun applyPageDragPreview() {
        val viewWidth = width
        if (viewWidth <= 0) return
        val from = pageDragFrom
        val to = pageDragTarget()
        for ((view, item) in entries) {
            val page = item.page
            view.translationX = when {
                // The lifted page follows the finger instead of jumping between slots.
                page == from -> pageDragOffsetX
                from < to && page in (from + 1)..to -> -viewWidth.toFloat()
                to < from && page in to until from -> viewWidth.toFloat()
                else -> 0f
            }
        }
        invalidate()
    }

    private fun finishPageDrag(commit: Boolean) {
        val from = pageDragFrom
        val to = pageDragTarget()
        pageDragging = false
        pageDragOffsetX = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        for ((view, _) in entries) view.translationX = 0f
        invalidate()
        if (commit && to != from) onPagesReordered?.invoke(from, to)
    }

    /**
     * Outlines one page: a fill plus a frame, shifted sideways while the page follows a drag.
     * Drawn behind the items so a boundary is visible without hiding what sits on the page.
     */
    private fun drawPageFrame(canvas: Canvas, page: Int, offsetX: Float, fill: Paint, frame: Paint) {
        val viewWidth = width
        if (viewWidth <= 0 || page !in 0 until pageCount) return
        val radius = dp(SELECTION_RADIUS_DP).toFloat()
        val left = (page * viewWidth + paddingLeft + offsetX).toFloat()
        val right = (page * viewWidth + viewWidth - paddingRight + offsetX).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, fill)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, frame)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (placeholderVisible) {
            placeholderDrawable?.let {
                it.setBounds(placeholderBounds)
                it.draw(canvas)
            }
        }
        if (pageDragging) {
            // Where the page will land, then the page itself under the finger.
            val target = pageDragTarget()
            if (target != pageDragFrom) {
                drawPageFrame(canvas, target, 0f, editingPageFill, editingPageFrame)
            }
            drawPageFrame(canvas, pageDragFrom, pageDragOffsetX, pageFramePaint, framePaint)
        } else if (editing) {
            // Editing a page is a job done inside its bounds, so show them.
            drawPageFrame(canvas, currentPage(), 0f, editingPageFill, editingPageFrame)
        }
        super.dispatchDraw(canvas)

        // Above the items: a filled placement would hide both of these underneath it.
        if (previewVisible) drawResizePreview(canvas)
        if (!resizing && dragSource == null && editing) selectedItem()?.let { drawSelection(canvas, it) }
    }

    /** The outline that follows a handle drag: usable, or refused where it currently sits. */
    private fun drawResizePreview(canvas: Canvas) {
        val drawable = if (previewValid) placeholderDrawable else invalidPlaceholderDrawable
        drawable?.setBounds(previewBounds)
        drawable?.draw(canvas)
    }

    /** The frame and four corner handles around the placement being resized. */
    private fun drawSelection(canvas: Canvas, item: HomeItem) {
        val bounds = selectionBounds
        itemBounds(item, bounds)
        val radius = dp(SELECTION_RADIUS_DP).toFloat()
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            radius,
            radius,
            framePaint
        )

        val touch = handleTouchPx(bounds)
        val inset = dp(HANDLE_INSET_DP)
        val centre = touch / 2
        val left = bounds.left + inset + centre
        val right = bounds.right - inset - centre
        val top = bounds.top + inset + centre
        val bottom = bounds.bottom - inset - centre
        drawHandle(canvas, left, top)
        drawHandle(canvas, right, top)
        drawHandle(canvas, left, bottom)
        drawHandle(canvas, right, bottom)
    }

    private fun drawHandle(canvas: Canvas, centerX: Int, centerY: Int) {
        val half = dp(HANDLE_DOT_DP) / 2
        val radius = dp(HANDLE_RADIUS_DP).toFloat()
        canvas.drawRoundRect(
            (centerX - half).toFloat(),
            (centerY - half).toFloat(),
            (centerX + half).toFloat(),
            (centerY + half).toFloat(),
            radius,
            radius,
            handlePaint
        )
    }

    private companion object {
        const val DRAG_LABEL = "home-item"
        const val PAGE_ANIM_MS = 220
        const val FLING_VELOCITY = 900f

        /** How close to an edge a drag has to be to roll the page over, and how often it repeats. */
        const val DRAG_EDGE_DP = 48
        const val DRAG_FLIP_FIRST_MS = 350L
        const val DRAG_FLIP_REPEAT_MS = 450L

        /** Which corner of a placement a resize handle grabs. */
        const val CORNER_NONE = -1
        const val CORNER_TOP_LEFT = 0
        const val CORNER_TOP_RIGHT = 1
        const val CORNER_BOTTOM_LEFT = 2
        const val CORNER_BOTTOM_RIGHT = 3

        /** Grab box of a handle, the least it may shrink to, and its inset from the edge. */
        const val HANDLE_TOUCH_DP = 48
        const val MIN_HANDLE_TOUCH_DP = 20
        const val HANDLE_INSET_DP = 2

        /** The dot drawn inside each grab box, and the selection frame's corner radius. */
        const val HANDLE_DOT_DP = 16
        const val HANDLE_RADIUS_DP = 4
        const val SELECTION_RADIUS_DP = 12
    }
}
