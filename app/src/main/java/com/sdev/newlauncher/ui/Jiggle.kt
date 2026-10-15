package com.sdev.newlauncher.ui

import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation

/**
 * The subtle wobble home icons get while the screen is in edit mode, so it is obvious that
 * items can be dragged and removed.
 */
internal object Jiggle {

    private const val DEGREES = 1.5f
    private const val DURATION_MS = 180L

    fun start(view: View) {
        val animation = RotateAnimation(
            -DEGREES,
            DEGREES,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f
        ).apply {
            duration = DURATION_MS
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        view.startAnimation(animation)
    }

    fun stop(view: View) = view.clearAnimation()
}
