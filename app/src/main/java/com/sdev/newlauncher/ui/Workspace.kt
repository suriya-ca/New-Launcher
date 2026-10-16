package com.sdev.newlauncher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.sdev.newlauncher.R
import com.sdev.newlauncher.data.HomeGeometry
import com.sdev.newlauncher.data.HomeItem
import com.sdev.newlauncher.data.WidgetResizeBounds
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

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
                if (overviewOpen || pageOverview < 1f) {
                    // A page went away under the open overview: the strip has to fit what is left.
                    overviewAnchor = overviewAnchor.coerceIn(overviewAnchorMin(), overviewAnchorMax())
                    applyPagePreview()
                }
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
            if (!value) {
                // The overview only exists to arrange pages, which is a job done in edit mode.
                closeOverviewNow()
                selectedItemId = null
            }
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
     * The ✕ on a page card in the overview was tapped. The host confirms and deletes; this only
     * reports which page was asked for. Kept apart from [onItemRemoveRequested], which is about an
     * item sitting on a page rather than the page itself.
     */
    var onPageDeleteRequested: ((Int) -> Unit)? = null

    /**
     * A ＋ card at either end of the overview strip was tapped, so the host should add a page at
     * the given index: `0` for the card to the left of the pages, `pageCount` for the one to the
     * right. Deliberately not [onAddPageRequested]: that one is the "a drag ran past the last
     * page" path, and the two are reached in completely different ways.
     */
    var onAddPageTapped: ((Int) -> Unit)? = null

    /**
     * Fires when the page overview opens or closes. It is a place the user stays in rather than a
     * gesture that ends, so the host can put its own page chrome away while it is showing.
     */
    var onOverviewChanged: ((Boolean) -> Unit)? = null

    /** Pages the host is willing to hold: the ＋ card is not drawn once [pageCount] reaches this. */
    var maxPageCount: Int = 12

    /**
     * Called after each layout pass for every item, so callers can sync content that depends on
     * the measured cell size (widgets, mainly).
     */
    var onItemLaidOut: ((View, HomeItem) -> Unit)? = null

    /** How far the widget behind an item may be resized, or null when it must keep its span. */
    var resizeBounds: ((HomeItem) -> WidgetResizeBounds?)? = null

    /** Called with the same placement and a new span once a resize gesture is released. */
    var onItemResize: ((HomeItem) -> Unit)? = null

    /**
     * Called when the delete badge on the selected item is tapped. The host confirms and removes;
     * the workspace only reports the request.
     */
    var onItemRemoveRequested: ((HomeItem) -> Unit)? = null

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

    /** True while a touch that began on the delete badge is still deciding to be a tap. */
    private var badgeArmed = false

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

    /** The delete badge: the same dark disc and white cross the page chips use. */
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC000000.toInt()
    }
    private val badgeCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.displayMetrics.density * 2f
        color = 0xFFFFFFFF.toInt()
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

    /**
     * The cards drawn behind the pages while one of them is dragged: plain fills and no frames,
     * so the step back reads as calm rather than as a grid of outlines.
     */
    private val pageCardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x14000000
    }

    /** The page in hand, filled a touch lighter so it reads as picked up. */
    private val pageLiftedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1FFFFFFF
    }

    /** The slot the page in hand is heading for. */
    private val pageTargetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2EFFFFFF
    }

    /**
     * The add card at the end of the strip: a lighter fill than the pages, because it is an action
     * rather than something already there.
     */
    private val pageAddCardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x24FFFFFF
    }

    /** The ＋ drawn on it. */
    private val pageAddGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.displayMetrics.density * 3f
        color = 0xFFFFFFFF.toInt()
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

    /** The slot the dragged page is heading for, held fixed while the home grows back. */
    private var pageDragTo = 0
    private var pageDragOffsetX = 0f

    /** Left to travel between where the finger let go and the slot being settled into. */
    private var pageSettleOffset = 0f

    /**
     * How big the pages are drawn while one of them is dragged: 1 at rest, then
     * [PAGE_OVERVIEW_SCALE] so that the neighbours either side come into view.
     */
    private var pageOverview = 1f
    private var pageOverviewAnimator: ValueAnimator? = null

    /**
     * How far each page has stepped aside for the drag, in slots: 0 in its own slot and ±1 in the
     * one the dragged page started from. Kept as a fraction and animated, so a page slides rather
     * than jumping sideways when the drag crosses into another slot.
     */
    private var pageShifts = FloatArray(0)
    private var pageShiftAnimator: ValueAnimator? = null

    /**
     * True while the overview is open on its own, with nobody's finger on it. A page drag opens it
     * and letting go leaves it open: it is a place to add, remove and rearrange pages, not a
     * gesture that has to be held.
     */
    private var overviewOpen = false

    /**
     * Which page slot the overview strip is lined up on, in pages, and how far it has been panned:
     * the middle of the screen sits over this slot's middle. Fractional while a pan is in flight,
     * and always a whole page at rest. Replaces [scrollX] in every overview calculation, because
     * the strip is moved as a whole while the scroll underneath stays where it was.
     */
    private var overviewAnchor = 0f
    private var overviewAnchorAnimator: ValueAnimator? = null

    /** The zoom back in, which moves [pageOverview] and [overviewAnchor] together. */
    private var overviewCloseAnimator: ValueAnimator? = null

    /** Where the strip was when the finger went down, so a pan stays exactly under it. */
    private var overviewAnchorAtDown = 0f

    /** True once a press on the open strip has moved far enough to count as a pan, not a tap. */
    private var overviewPanning = false

    /** Where a page card sits on screen, worked out once and used for both drawing and hit-testing. */
    private val overviewCardRect = RectF()

    private val longPressRunnable = Runnable {
        when {
            // In the overview a hold lifts the page under it, if there is one there at all.
            overviewOpen -> pageAt(downX, downY).takeIf { it >= 0 }?.let { startPageDrag(it) }
            // In edit mode an empty spot has no menu, so the same press starts a page drag instead.
            editing -> startPageDrag()
            else -> onEmptyLongPress?.invoke()
        }
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
                discard(view, item)
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
        if (pageDragging || pageOverview < 1f) {
            // A page preview is a transform rather than a placement, so it is re-applied to
            // whatever has just changed instead of being thrown away.
            applyPagePreview()
        } else {
            for ((view, _) in entries) {
                view.translationX = 0f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                // An item lifted for a drag is deliberately hidden; nothing else stays dim.
                if (view !== dragSource) view.alpha = 1f
            }
        }
        if (selectedItemId != null && items.none { it.id == selectedItemId }) selectedItemId = null
        requestLayout()
        invalidate()
    }

    /**
     * Takes a discarded item view off the home for good.
     *
     * The wobble must be stopped while the view is still attached. A view removed with an
     * animation still running is not detached by the framework: it is parked in this parent's
     * disappearing-children list and drawn there every frame, and an infinite wobble never ends,
     * so nothing would ever take it out again. That left a frozen, untouchable copy of every
     * widget removed in edit mode sitting on the home until the activity was recreated.
     */
    private fun discard(view: View, item: HomeItem) {
        Jiggle.stop(view)
        view.animate().cancel()
        removeView(view)
        itemRecycler?.invoke(view, item)
    }

    /** The currently settled page. */
    fun currentPage(): Int {
        val width = width
        if (width <= 0) return 0
        // While the overview is open the strip, not the scroll, is what the user is pointing at.
        val page = if (overviewOpen) floor(overviewAnchor + 0.5f) else ((scrollX + width / 2f) / width)
        return page.toInt().coerceIn(0, pageCount - 1)
    }

    /** Whether the page overview is on screen, open on its own rather than under a finger. */
    fun isOverviewOpen(): Boolean = overviewOpen

    /**
     * True when a touch at the given view-local point lands on a home item. The page overview
     * answers false: it is a place to point at pages, not at icons. Used to tell a swipe down
     * that began on an icon from one that began on empty space.
     */
    fun hitsItem(viewX: Float, viewY: Float): Boolean {
        if (pageOverview < 1f || width <= 0 || height <= 0) return false
        val x = (viewX + scrollX).toInt()
        val y = viewY.toInt()
        val bounds = Rect()
        for ((_, item) in entries) {
            itemBounds(item, bounds)
            if (bounds.contains(x, y)) return true
        }
        return false
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
        // A page preview is a transform on top of the children's own positions, and those carry
        // -scrollX. Anything that moves the scroll while the overview is showing — a page added
        // past the last one, a reorder settling — has to have the transform worked out again, or
        // every page would slide sideways by the distance the scroll moved.
        if (overviewOpen || pageOverview < 1f) applyPagePreview()
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
                // The open overview owns every touch from here: the pages underneath must not
                // react to it, and a press on a card has to be able to lift that card.
                if (overviewOpen) return true
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
                // The delete badge and the handles win over the item under them: those gestures
                // remove and resize rather than page. The badge is asked first, since it sits on
                // top of the item's own edge.
                if (editing) {
                    val item = selectedItem()
                    if (item != null) {
                        if (badgeAt(item, ev.x, ev.y)) {
                            badgeArmed = true
                            invalidate()
                            return true
                        }
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
                // The badge owns its gesture too: no long press, so a press there cannot start a
                // drag that would move the item the badge belongs to.
                if (badgeArmed) return true
                if (overviewOpen) {
                    // A press on the open strip can become a tap, a pan, or a hold that lifts a
                    // page. It also stops a close that was still running, so the strip is where the
                    // user last saw it rather than halfway back to full size.
                    cancelOverviewClose()
                    if (pageOverview > PAGE_OVERVIEW_SCALE + 0.001f) shrinkOverview()
                    overviewAnchorAnimator?.cancel()
                    overviewAnchorAtDown = overviewAnchor
                    overviewPanning = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    postDelayed(longPressRunnable, longPressTimeout)
                    return true
                }
                postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizing) {
                    updateResize(event.x, event.y)
                    return true
                }
                // A press that began on the badge is a tap or nothing: it never pages.
                if (badgeArmed) return true
                if (pageDragging) {
                    updatePageDrag(event.x)
                    return true
                }
                if (overviewOpen) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx > touchSlop || dy > touchSlop) removeCallbacks(longPressRunnable)
                    // Sideways moves pan the strip; a sideways pan is the only thing that moves it.
                    if (overviewPanning || (dx > touchSlop && dx > dy)) {
                        overviewPanning = true
                        overviewAnchor = (overviewAnchorAtDown - (event.x - downX) / width)
                            .coerceIn(overviewAnchorMin(), overviewAnchorMax())
                        applyPagePreview()
                    }
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
                if (badgeArmed) {
                    val tapped = event.actionMasked == MotionEvent.ACTION_UP
                    badgeArmed = false
                    invalidate()
                    // Releasing anywhere but on the badge is a change of mind, not a tap.
                    val item = selectedItem()
                    if (tapped && item != null && badgeAt(item, event.x, event.y)) {
                        onItemRemoveRequested?.invoke(item)
                    }
                    return true
                }
                if (pageDragging) {
                    finishPageDrag(event.actionMasked == MotionEvent.ACTION_UP)
                    return true
                }
                if (overviewOpen) {
                    val wasPanning = overviewPanning
                    overviewPanning = false
                    settleAfterAnimation = false
                    // A press that neither panned nor lifted a page is a tap on the strip.
                    if (!wasPanning && event.actionMasked == MotionEvent.ACTION_UP &&
                        abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop
                    ) {
                        handleOverviewTap(event.x, event.y)
                    }
                    stopTrackingVelocity()
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

    /**
     * Whether a view-local touch landed on the delete badge of [item]. The badge is a disc
     * centred on the item's top edge, just inside the selection frame, so it stays clear of the
     * corner handles — which is also why its grab box is capped at a third of the placement.
     */
    private fun badgeAt(item: HomeItem, localX: Float, localY: Float): Boolean {
        val bounds = selectionBounds
        itemBounds(item, bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val half = badgeTouchPx(bounds) / 2
        val centerY = badgeCenterY(bounds)
        return abs(localX + scrollX - bounds.centerX()) <= half && abs(localY - centerY) <= half
    }

    /** Side of the badge's grab box, and where its centre sits inside the placement. */
    private fun badgeTouchPx(bounds: Rect): Int =
        minOf(dp(BADGE_TOUCH_DP), bounds.width() / 3, bounds.height() / 3)
            .coerceAtLeast(dp(MIN_BADGE_TOUCH_DP))

    private fun badgeCenterY(bounds: Rect): Int = bounds.top + dp(HANDLE_INSET_DP) + badgeTouchPx(bounds) / 2

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
            DragEvent.ACTION_DRAG_STARTED -> {
                // A drag is a full-screen gesture of its own: an overview still up would put every
                // page at the wrong size and in the wrong place for where the drop lands.
                closeOverviewNow()
                state != null
            }
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
    private fun startPageDrag() = startPageDrag(currentPage())

    /**
     * Lifts [page] so it can be dragged to another slot, opening the overview around it. Letting go
     * leaves the overview open: the add card, the ✕ on each page and the pan across the strip are
     * all offered from there.
     */
    private fun startPageDrag(page: Int) {
        if (pageDragging || width <= 0) return
        // A settle still running from the last drag commits its reorder as it is cancelled, so it
        // has to be out of the way before this drag works out which page it is starting on.
        cancelOverviewClose()
        pageOverviewAnimator?.cancel()
        pageShiftAnimator?.cancel()
        pageShiftAnimator = null
        pageShifts = FloatArray(pageCount)
        pageDragging = true
        pageDragFrom = page.coerceIn(0, pageCount - 1)
        pageDragTo = pageDragFrom
        pageDragOffsetX = 0f
        pageSettleOffset = 0f
        select(null)
        // The page in hand has to be drawn over the page taking its place, or the two overlap in
        // whatever order the items happened to be added in.
        for ((view, item) in entries) if (item.page == pageDragFrom) view.bringToFront()
        // The page owns this gesture: no paging and no drawer fling while it is dragged.
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        openOverview()
        shrinkOverview()
        applyPagePreview()
    }

    /** Takes the overview on as something that stays open, without moving the strip under it. */
    private fun openOverview() {
        if (overviewOpen) return
        overviewOpen = true
        overviewAnchor = scrollX.toFloat() / width
        onOverviewChanged?.invoke(true)
    }

    /** The furthest the strip may be panned: the add cards themselves may be brought to the middle. */
    private fun overviewAnchorMax(): Float =
        if (pageCount < maxPageCount) pageCount.toFloat() else (pageCount - 1).toFloat()

    /** The nearest the strip may be panned: the add card to the left of the pages, or the first page. */
    private fun overviewAnchorMin(): Float = if (pageCount < maxPageCount) -1f else 0f

    /**
     * The content x the strip is lined up on. While nothing is showing that is simply the scroll;
     * while the overview is up the strip has been panned away from it.
     */
    private fun overviewAnchorPx(): Float =
        if (overviewOpen || pageOverview < 1f) overviewAnchor * width else scrollX.toFloat()

    /**
     * Shrinks the home for a page drag so the pages either side come into view. Only [pageOverview]
     * is animated; every frame of it is drawn by [applyPagePreview], which is also what moves the
     * pages around the one being dragged.
     */
    private fun shrinkOverview() {
        cancelOverviewClose()
        pageOverviewAnimator?.cancel()
        pageOverviewAnimator = ValueAnimator
            .ofFloat(pageOverview, PAGE_OVERVIEW_SCALE)
            .apply {
                duration = PAGE_OVERVIEW_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    pageOverview = it.animatedValue as Float
                    applyPagePreview()
                }
                start()
            }
    }

    private fun updatePageDrag(x: Float) {
        pageDragOffsetX = x - downX
        // What moves the other pages is the slot the drag has crossed into, not the pixel under
        // the finger, so the change there is what slides them and ticks.
        val slot = pageDragTarget()
        if (slot != pageDragTo) {
            pageDragTo = slot
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            animatePageShifts()
        }
        applyPagePreview()
    }

    /**
     * The page a release right now would land on. A page has crossed into the next slot once the
     * finger is more than half a shrunk page along, which is the spacing the user can see.
     */
    private fun pageDragTarget(): Int {
        val viewWidth = width
        if (viewWidth <= 0) return pageDragFrom
        val steps = floor(pageDragOffsetX / (viewWidth * pageOverview) + 0.5f).toInt()
        return (pageDragFrom + steps).coerceIn(0, pageCount - 1)
    }

    /** The slot the pages are heading for, whether or not a finger is still deciding it. */
    private fun pageDragSlot(): Int = pageDragTo

    /**
     * How far a page travels to take a slot [steps] away: one shrunk page per slot crossed.
     *
     * The size used is the one on screen at this instant rather than the settled
     * [PAGE_OVERVIEW_SCALE], so the pages line up with the cards drawn over them on every frame of
     * the shrink. Using the settled size instead made a still near-full-size page lurch half a
     * slot sideways the moment a drag crossed into another slot.
     */
    private fun pageSlotOffset(steps: Int): Float = steps * width * pageOverview

    /** The slot a page is stepping aside for, in slots: 0 in its own, ±1 in the one left behind. */
    private fun pageShiftTarget(page: Int): Float {
        val from = pageDragFrom
        val to = pageDragTo
        return when {
            from < to && page in (from + 1)..to -> -1f
            to < from && page in to until from -> 1f
            else -> 0f
        }
    }

    /** How far a page has stepped aside right now, in slots, eased towards [pageShiftTarget]. */
    private fun pageShift(page: Int): Float = pageShifts.getOrElse(page) { 0f }

    /** Slides the pages towards the slots this drag is putting them in, rather than snapping them. */
    private fun animatePageShifts() {
        val count = pageCount
        if (pageShifts.size != count) {
            pageShifts = FloatArray(count) { pageShifts.getOrElse(it) { 0f } }
        }
        val start = pageShifts.copyOf()
        val end = FloatArray(count) { pageShiftTarget(it) }
        pageShiftAnimator?.cancel()
        if ((0 until count).all { abs(start[it] - end[it]) < 0.001f }) {
            pageShifts = end
            return
        }
        pageShiftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PAGE_SHIFT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                for (index in 0 until count) {
                    pageShifts[index] = start[index] + (end[index] - start[index]) * progress
                }
                applyPagePreview()
            }
            start()
        }
    }

    /**
     * How far a page sits from its slot while another page is dragged over it, in screen pixels.
     * A shrunk page is one slot wide, so the pages in between step across by exactly that much and
     * the lifted page keeps tracking the finger.
     */
    private fun pagePreviewOffset(page: Int): Float {
        if (width <= 0) return 0f
        val from = pageDragFrom
        return if (page == from) {
            // The lifted page follows the finger, then settles into the slot it was let go on.
            if (pageDragging) pageDragOffsetX
            else pageSlotOffset(pageDragTo - from) + pageSettleOffset
        } else {
            pageShift(page) * pageSlotOffset(1)
        }
    }

    /** How far into the shrink the home is: 0 at full size, 1 at the dragged-page size. */
    private val overviewProgress: Float
        get() = ((1f - pageOverview) / (1f - PAGE_OVERVIEW_SCALE)).coerceIn(0f, 1f)

    /** Opacity of the pages that are not the one in hand: they come back as the home grows back. */
    private val pagePreviewAlpha: Float
        get() = 1f - (1f - PAGE_OVERVIEW_ALPHA) * overviewProgress

    /**
     * Shrinks every page towards the middle of the screen and slides the pages around the lifted
     * one, so where it will land is visible before it is let go.
     *
     * The items are transformed one by one rather than by scaling this view: a view is clipped to
     * its own bounds in the space its own scale produces, and the whole point of the preview is to
     * show what lies outside those bounds. An item is shrunk about its own middle and then moved to
     * where that middle belongs, which is what an overall scale would do to it.
     */
    private fun applyPagePreview() {
        if (width <= 0) return
        val bounds = Rect()
        val middleX = width / 2f
        val middleY = height / 2f
        val anchorPx = overviewAnchorPx()
        // Children are laid out in content space and the canvas then carries -scrollX, so their
        // translations have to undo wherever the scroll has got to and put the strip where the
        // overview says it is instead. Both terms vanish when the overview is shut.
        val scrollLift = pageOverview * (scrollX - anchorPx)
        for ((view, item) in entries) {
            itemBounds(item, bounds)
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
            view.scaleX = pageOverview
            view.scaleY = pageOverview
            // Where the item's middle sits once the page has been shrunk, plus the distance its
            // page is travelling sideways: all three are lengths in the space this view draws in.
            view.translationX = (pageOverview - 1f) * (bounds.centerX() - scrollX - middleX) +
                scrollLift +
                pagePreviewOffset(item.page)
            view.translationY = (pageOverview - 1f) * (bounds.centerY() - middleY)
            // Everything but the page in hand steps back while that page is being dragged. An open
            // overview nobody is holding shows every page at full strength: it is a place to read
            // the pages and pick one, not a drag in progress.
            view.alpha = if (!pageDragging || item.page == pageDragFrom) 1f else pagePreviewAlpha
        }
        invalidate()
    }

    /** Puts every item back to full size, exactly where it was laid out. */
    private fun clearPagePreview() {
        pageShiftAnimator?.cancel()
        pageShiftAnimator = null
        pageShifts = FloatArray(0)
        for ((view, _) in entries) {
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
        }
        invalidate()
    }

    // ---------------------------------------------------------------- page overview controls

    /**
     * Zooms back in on [page], or on the slot the strip is pointing at when no page is given, and
     * closes the overview. [pageOverview] and the strip's anchor travel together, so whatever sat
     * in the middle of the strip is what fills the screen at the end.
     */
    fun closeOverview(page: Int = -1) {
        if (!overviewOpen || width <= 0) return
        cancelOverviewClose()
        pageOverviewAnimator?.cancel()
        pageShiftAnimator?.cancel()
        pageShiftAnimator = null
        pageShifts = FloatArray(0)
        pageDragging = false
        pageDragOffsetX = 0f
        pageSettleOffset = 0f
        pageDragTo = pageDragFrom
        val target = (if (page in 0 until pageCount) page else overviewAnchor.roundToInt())
            .coerceIn(0, pageCount - 1)

        val overviewFrom = pageOverview
        val anchorFrom = overviewAnchor
        overviewCloseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PAGE_OVERVIEW_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                pageOverview = overviewFrom + (1f - overviewFrom) * progress
                overviewAnchor = anchorFrom + (target - anchorFrom) * progress
                applyPagePreview()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // A close cut short by a new gesture is nulled out before it is cancelled, so
                    // the listener has to ignore that one rather than finish an abandoned close.
                    if (animation !== overviewCloseAnimator) return
                    overviewCloseAnimator = null
                    finishOverviewClose(target)
                }
            })
            start()
        }
    }

    /**
     * Drops the overview where it stands, with no animation. For the changes that cannot wait for
     * one: leaving edit mode, and a drag arriving that has to be laid out on an unscaled home.
     */
    private fun closeOverviewNow() {
        val wasOpen = overviewOpen
        val wasShowing = pageOverview
        cancelOverviewClose()
        pageOverviewAnimator?.cancel()
        pageShiftAnimator?.cancel()
        pageShiftAnimator = null
        pageShifts = FloatArray(0)
        pageDragging = false
        pageDragOffsetX = 0f
        pageSettleOffset = 0f
        pageDragTo = pageDragFrom
        if (!wasOpen && wasShowing >= 1f) return

        val target = overviewAnchor.roundToInt().coerceIn(0, pageCount - 1)
        overviewOpen = false
        pageOverview = 1f
        overviewAnchor = target.toFloat()
        clearPagePreview()
        scrollTo(target * width, 0)
        if (wasOpen) onOverviewChanged?.invoke(false)
    }

    /** Cancels a close still in flight without letting it finish, so the overview can carry on. */
    private fun cancelOverviewClose() {
        val running = overviewCloseAnimator ?: return
        overviewCloseAnimator = null
        running.cancel()
    }

    /** The last frame of a close: the scroll catches up with the strip, now back at full size. */
    private fun finishOverviewClose(target: Int) {
        overviewOpen = false
        pageOverview = 1f
        overviewAnchor = target.toFloat()
        clearPagePreview()
        scrollTo(target * width, 0)
        onOverviewChanged?.invoke(false)
    }

    /** Slides the strip to [page], which is how a page just added is brought into view. */
    private fun animateOverviewAnchorTo(page: Float) {
        overviewAnchorAnimator?.cancel()
        val from = overviewAnchor
        val target = page.coerceIn(overviewAnchorMin(), overviewAnchorMax())
        if (abs(from - target) < 0.001f) return
        overviewAnchorAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = PAGE_ANIM_MS.toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                overviewAnchor = it.animatedValue as Float
                applyPagePreview()
            }
            start()
        }
    }

    /** What a tap on the open overview does: remove a page, add one, or pick the page to open. */
    private fun handleOverviewTap(x: Float, y: Float) {
        val deletePage = closeCardAt(x, y)
        if (deletePage >= 0) {
            if (pageCount > 1) {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onPageDeleteRequested?.invoke(deletePage)
            }
            return
        }
        val addAt = addCardAt(x, y)
        if (addAt >= 0) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onAddPageTapped?.invoke(addAt)
            // The host has already added the page, so the strip follows it and the new, empty card
            // is what comes into the middle rather than the page that was there before.
            animateOverviewAnchorTo(if (addAt == 0) 0f else (pageCount - 1).toFloat())
            return
        }
        val page = pageAt(x, y)
        if (page >= 0) closeOverview(page)
    }

    /** Where a page's card sits on screen, which is what both its drawing and its hit-test use. */
    private fun pageCardRect(page: Int, offsetX: Float, out: RectF) {
        val scale = pageOverview
        val anchorPx = overviewAnchorPx()
        val middleX = width / 2f
        val middleY = height / 2f
        val left = page * width + paddingLeft
        val right = page * width + width - paddingRight
        val top = paddingTop
        val bottom = height - paddingBottom
        out.set(
            middleX + scale * (left - anchorPx - middleX) + offsetX,
            middleY + scale * (top - middleY),
            middleX + scale * (right - anchorPx - middleX) + offsetX,
            middleY + scale * (bottom - middleY)
        )
    }

    /** Centre of a page card's ✕, in view coordinates: what drawing and hit-testing both use. */
    private fun pageCloseX(rect: RectF): Float =
        rect.right - dp(PAGE_CLOSE_INSET_DP) - dp(PAGE_CLOSE_DOT_DP) / 2f

    private fun pageCloseY(rect: RectF): Float =
        rect.top + dp(PAGE_CLOSE_INSET_DP) + dp(PAGE_CLOSE_DOT_DP) / 2f

    /** The page whose ✕ covers the view-local point, or -1. The only page left cannot be deleted. */
    private fun closeCardAt(x: Float, y: Float): Int {
        if (width <= 0 || pageCount <= 1) return -1
        val half = dp(PAGE_CLOSE_TOUCH_DP) / 2f
        for (page in 0 until pageCount) {
            pageCardRect(page, pagePreviewOffset(page), overviewCardRect)
            if (abs(x - pageCloseX(overviewCardRect)) <= half &&
                abs(y - pageCloseY(overviewCardRect)) <= half
            ) {
                return page
            }
        }
        return -1
    }

    /**
     * Which add card the view-local point is on, and the index a page added there would go to:
     * `0` for the card to the left of the pages, [pageCount] for the one to the right, and -1 when
     * the point is on neither. Both go away once the host will not take another page.
     */
    private fun addCardAt(x: Float, y: Float): Int {
        if (width <= 0 || pageCount >= maxPageCount) return -1
        pageCardRect(-1, 0f, overviewCardRect)
        if (overviewCardRect.contains(x, y)) return 0
        pageCardRect(pageCount, 0f, overviewCardRect)
        if (overviewCardRect.contains(x, y)) return pageCount
        return -1
    }

    /** The page whose card covers the view-local point, or -1 when the press is between cards. */
    private fun pageAt(x: Float, y: Float): Int {
        if (width <= 0 || pageOverview >= 1f) return -1
        for (page in 0 until pageCount) {
            pageCardRect(page, pagePreviewOffset(page), overviewCardRect)
            if (overviewCardRect.contains(x, y)) return page
        }
        return -1
    }

    private fun finishPageDrag(commit: Boolean) {
        val from = pageDragFrom
        val to = pageDragTo
        val reordering = commit && to != from
        pageDragging = false
        pageDragTo = if (reordering) to else from
        // Whatever is left between the finger and the slot the page is going to is animated away,
        // so letting go never snaps the page.
        pageSettleOffset = pageDragOffsetX - pageSlotOffset(pageDragTo - from)
        pageDragOffsetX = 0f
        // The pages that stepped aside follow the one in hand back or into place.
        animatePageShifts()
        performHapticFeedback(
            if (reordering) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
        )
        parent?.requestDisallowInterceptTouchEvent(false)
        settlePageDrag {
            if (reordering) {
                onPagesReordered?.invoke(from, to)
                // The page that was moved is the one the user's eye followed across the strip, so
                // it is the one that takes the middle — the same thing scrolling to it does once
                // the overview is shut.
                if (overviewOpen) animateOverviewAnchorTo(to.toFloat())
            }
        }
    }

    /**
     * Settles the page just let go: it slides into the slot the drag was heading for while the
     * overview stays open around it. A committed reorder is only applied when that has finished:
     * by then every page is already drawn where the reorder puts it, so applying it cannot be seen
     * as a jump. The overview only grows back when the user leaves it.
     */
    private fun settlePageDrag(onSettled: () -> Unit) {
        if (!overviewOpen) {
            // Nothing should get here — the overview opens with every page drag — but if it does,
            // the home has to come back to full size rather than stay shrunk with no way out.
            growHomeBack(onSettled)
            return
        }
        cancelOverviewClose()
        pageOverviewAnimator?.cancel()
        val overviewFrom = pageOverview
        val settleFrom = pageSettleOffset
        pageOverviewAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PAGE_OVERVIEW_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                pageOverview = overviewFrom + (PAGE_OVERVIEW_SCALE - overviewFrom) * progress
                pageSettleOffset = settleFrom * (1f - progress)
                applyPagePreview()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pageOverview = PAGE_OVERVIEW_SCALE
                    pageSettleOffset = 0f
                    pageDragOffsetX = 0f
                    pageDragTo = pageDragFrom
                    pageShiftAnimator?.cancel()
                    pageShiftAnimator = null
                    pageShifts = FloatArray(0)
                    applyPagePreview()
                    onSettled()
                }
            })
            start()
        }
    }

    /** Grows the home back to full size and hands the lifted page to its slot, as at rest. */
    private fun growHomeBack(onSettled: () -> Unit) {
        pageOverviewAnimator?.cancel()
        val overviewFrom = pageOverview
        val settleFrom = pageSettleOffset
        pageOverviewAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PAGE_OVERVIEW_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                pageOverview = overviewFrom + (1f - overviewFrom) * progress
                pageSettleOffset = settleFrom * (1f - progress)
                applyPagePreview()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pageOverview = 1f
                    pageSettleOffset = 0f
                    pageDragOffsetX = 0f
                    pageDragTo = pageDragFrom
                    clearPagePreview()
                    onSettled()
                }
            })
            start()
        }
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

    /**
     * One page as a card: its own slot shrunk about the middle of the screen by [pageOverview],
     * then shifted sideways by [offsetX]. The shrink is worked out in screen space by
     * [pageCardRect] and put back into content space to be drawn, because this canvas already
     * carries -scrollX for everything it is handed.
     */
    private fun drawPageCard(canvas: Canvas, page: Int, offsetX: Float, fill: Paint) {
        if (width <= 0 || page !in 0 until pageCount) return
        val radius = dp(SELECTION_RADIUS_DP).toFloat()
        pageCardRect(page, offsetX, overviewCardRect)
        canvas.drawRoundRect(
            overviewCardRect.left + scrollX,
            overviewCardRect.top,
            overviewCardRect.right + scrollX,
            overviewCardRect.bottom,
            radius,
            radius,
            fill
        )
    }

    /**
     * The add cards: one immediately before the first page and one immediately after the last, so
     * the way to make a page is always at whichever end the strip has been panned to. Each is a
     * lighter fill than the pages, because it is an action rather than something already there,
     * with a ＋ on it and nothing else — no ✕, since there is no page there to remove.
     */
    private fun drawAddCards(canvas: Canvas) {
        if (width <= 0 || pageCount >= maxPageCount) return
        drawAddCard(canvas, pageCount)
        drawAddCard(canvas, -1)
    }

    /** The card in slot [page], which is one past the end of the pages in either direction. */
    private fun drawAddCard(canvas: Canvas, page: Int) {
        val radius = dp(SELECTION_RADIUS_DP).toFloat()
        pageCardRect(page, 0f, overviewCardRect)
        val rect = overviewCardRect
        canvas.drawRoundRect(
            rect.left + scrollX,
            rect.top,
            rect.right + scrollX,
            rect.bottom,
            radius,
            radius,
            pageAddCardFill
        )
        val centreX = (rect.left + rect.right) / 2f + scrollX
        val centreY = (rect.top + rect.bottom) / 2f
        val arm = dp(PAGE_ADD_ARM_DP) / 2f
        canvas.drawLine(centreX - arm, centreY, centreX + arm, centreY, pageAddGlyphPaint)
        canvas.drawLine(centreX, centreY - arm, centreX, centreY + arm, pageAddGlyphPaint)
    }

    /**
     * The ✕ on a page card: how a page is removed without leaving the overview. The disc and cross
     * are the delete badge's, so removing a page looks the same as removing the thing on it. It is
     * dimmed rather than hidden when it cannot be used, so the one remaining page still reads as
     * a page you are on rather than a button that is broken.
     */
    private fun drawPageClose(canvas: Canvas, page: Int, offsetX: Float) {
        if (width <= 0) return
        pageCardRect(page, offsetX, overviewCardRect)
        val rect = overviewCardRect
        if (rect.right < 0f || rect.left > width) return
        val centreX = pageCloseX(rect) + scrollX
        val centreY = pageCloseY(rect)
        val enabled = pageCount > 1
        badgePaint.alpha = if (enabled) 0xCC else 0x4D
        badgeCrossPaint.alpha = if (enabled) 0xFF else 0x4D
        val radius = dp(PAGE_CLOSE_DOT_DP) / 2f
        canvas.drawCircle(centreX, centreY, radius, badgePaint)
        val arm = radius * 0.38f
        canvas.drawLine(centreX - arm, centreY - arm, centreX + arm, centreY + arm, badgeCrossPaint)
        canvas.drawLine(centreX + arm, centreY - arm, centreX - arm, centreY + arm, badgeCrossPaint)
        badgePaint.alpha = 0xCC
        badgeCrossPaint.alpha = 0xFF
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (placeholderVisible) {
            placeholderDrawable?.let {
                it.setBounds(placeholderBounds)
                it.draw(canvas)
            }
        }
        val overviewShowing = pageDragging || pageOverview < 1f
        if (overviewShowing) {
            // Every page as a card: the rest plain, the target lit, the lifted one on top.
            val target = pageDragSlot()
            for (page in 0 until pageCount) {
                if (page == pageDragFrom) continue
                val fill = if (page == target) pageTargetFill else pageCardFill
                drawPageCard(canvas, page, pagePreviewOffset(page), fill)
            }
            drawPageCard(canvas, pageDragFrom, pagePreviewOffset(pageDragFrom), pageLiftedFill)
            drawAddCards(canvas)
        } else if (editing) {
            // Editing a page is a job done inside its bounds, so show them.
            drawPageFrame(canvas, currentPage(), 0f, editingPageFill, editingPageFrame)
        }
        super.dispatchDraw(canvas)

        // Above the items: a filled placement would hide both of these underneath it.
        if (previewVisible) drawResizePreview(canvas)
        if (!overviewShowing && !resizing && dragSource == null && editing) {
            selectedItem()?.let { drawSelection(canvas, it) }
        }
        // And above those: the ✕ has to be reachable even on a page that holds a big widget.
        if (overviewShowing) {
            for (page in 0 until pageCount) drawPageClose(canvas, page, pagePreviewOffset(page))
        }
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
        drawBadge(canvas, bounds)
    }

    /**
     * The delete badge on the selected item: the way to remove it without aiming a drag at the
     * Remove target. It grows a little while pressed, so the tap has visible feedback.
     */
    private fun drawBadge(canvas: Canvas, bounds: Rect) {
        val centerX = bounds.centerX().toFloat()
        val centerY = badgeCenterY(bounds).toFloat()
        val radius = (dp(BADGE_DOT_DP) / 2f) * if (badgeArmed) 1.15f else 1f
        canvas.drawCircle(centerX, centerY, radius, badgePaint)
        val arm = radius * 0.38f
        canvas.drawLine(centerX - arm, centerY - arm, centerX + arm, centerY + arm, badgeCrossPaint)
        canvas.drawLine(centerX + arm, centerY - arm, centerX - arm, centerY + arm, badgeCrossPaint)
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

        /**
         * How far the home shrinks while a page is dragged, how far the untouched pages step back,
         * and how long both take.
         */
        const val PAGE_OVERVIEW_SCALE = 0.7f
        const val PAGE_OVERVIEW_ALPHA = 0.4f
        const val PAGE_OVERVIEW_MS = 180L

        /** How long a page takes to slide aside when the slot a drag is heading for changes. */
        const val PAGE_SHIFT_MS = 140L

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

        /** The delete badge's grab box, its least size, and the disc drawn inside it. */
        const val BADGE_TOUCH_DP = 44
        const val MIN_BADGE_TOUCH_DP = 28
        const val BADGE_DOT_DP = 24

        /** The ✕ on a page card: its disc, how far it sits into the corner, and its grab box. */
        const val PAGE_CLOSE_DOT_DP = 24
        const val PAGE_CLOSE_INSET_DP = 10
        const val PAGE_CLOSE_TOUCH_DP = 48

        /** The ＋ on the add card: the length of each arm. */
        const val PAGE_ADD_ARM_DP = 34
    }
}
