package com.sdev.newlauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.DragEvent
import androidx.appcompat.widget.AppCompatEditText

/**
 * An editable text field that stays out of everyone else's drag and drop.
 *
 * An editable [android.widget.TextView] answers [DragEvent.ACTION_DRAG_STARTED] with `true` all by
 * itself, which makes it — and every one of its ancestors — a drop target. The drawer and the
 * widget sheet are full-screen overlays holding a search field, so that alone is enough for the
 * overlay to shadow the home grid: the framework then hands every drag position and the final drop
 * to the overlay, which has nothing to do with them there, and the home never hears about the
 * widget dragged out of the picker or the app dragged out of the drawer.
 *
 * Refusing drag events restores the intended order, and the search field loses nothing: it never
 * wanted dragged text dropped into it in the first place.
 */
class NoDragEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    override fun onDragEvent(event: DragEvent): Boolean = false
}
