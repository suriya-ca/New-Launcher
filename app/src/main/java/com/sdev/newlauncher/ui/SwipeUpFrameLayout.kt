package com.sdev.newlauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * A [FrameLayout] that reports upward/downward swipe (fling) gestures without
 * stealing those touches from scrollable children: it never intercepts, it only
 * observes. This lets a single root view drive both "open the app drawer"
 * (swipe up) and "close the app drawer" (swipe down) while inner lists keep
 * scrolling normally.
 */
class SwipeUpFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Invoked when the user performs an upward swipe. */
    var onSwipeUp: (() -> Unit)? = null

    /** Invoked when the user performs a downward swipe. */
    var onSwipeDown: (() -> Unit)? = null

    private val minDistancePx: Float = 80f * resources.displayMetrics.density
    private val minVelocityPx: Float = 300f * resources.displayMetrics.density

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val deltaY = start.y - e2.y // positive when swiping up, negative when swiping down
            val vertical = abs(deltaY) > abs(e2.x - start.x)
            if (!vertical || abs(deltaY) < minDistancePx) return false
            return when {
                deltaY > 0 && velocityY < -minVelocityPx -> {
                    onSwipeUp?.invoke()
                    true
                }
                deltaY < 0 && velocityY > minVelocityPx -> {
                    onSwipeDown?.invoke()
                    true
                }
                else -> false
            }
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        // Never intercept: children keep full control of the gesture.
        return false
    }
}
