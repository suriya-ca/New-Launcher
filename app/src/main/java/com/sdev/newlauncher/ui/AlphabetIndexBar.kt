package com.sdev.newlauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.sdev.newlauncher.R
import kotlin.math.min

/**
 * A vertical A–Z strip drawn along the drawer's right edge. Touching or dragging over it
 * reports the letter index, and the touched letter is highlighted.
 */
class AlphabetIndexBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var letters: List<String> = emptyList()

    /** Invoked with the index into the current letters whenever the touched letter changes. */
    var onLetterSelected: ((Int) -> Unit)? = null

    /** The index currently under the finger (or `-1`). */
    var activeIndex: Int = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.index_bar_letter)
        textAlign = Paint.Align.CENTER
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.index_bar_letter_active)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setLetters(letters: List<String>) {
        this.letters = letters
        activeIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty() || height == 0) return

        val step = height.toFloat() / letters.size
        val textSize = min(step * 0.62f, 13f * density)
        letterPaint.textSize = textSize
        activePaint.textSize = textSize

        for (index in letters.indices) {
            val baseline = step * index + step / 2f -
                (letterPaint.descent() + letterPaint.ascent()) / 2f
            val paint = if (index == activeIndex) activePaint else letterPaint
            canvas.drawText(letters[index], width / 2f, baseline, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateActive(event.y)
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateActive(y: Float) {
        if (height == 0) return
        val index = ((y / height) * letters.size).toInt().coerceIn(0, letters.size - 1)
        if (index != activeIndex) {
            activeIndex = index
            onLetterSelected?.invoke(index)
        }
    }
}
