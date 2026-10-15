package com.sdev.newlauncher.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import com.sdev.newlauncher.R

/**
 * Context used to host third-party widgets.
 *
 * RemoteViews inflate the provider's layout with the host context's [LayoutInflater]. Because the
 * launcher runs on AppCompat, that inflater replaces `<ImageView>` with
 * [androidx.appcompat.widget.AppCompatImageView], and RemoteViews then refuses the reflected
 * setters such a widget declares ("view: androidx.appcompat.widget.AppCompatImageView can't use
 * method with RemoteViews: setBackgroundResource(int)"), which leaves the whole widget
 * unrenderable. Widgets are therefore inflated through the plain application inflater.
 *
 * Everything but the inflater is delegated to [base], so themes, window services and
 * PendingIntent handling behave exactly as they do for the rest of the launcher.
 */
class WidgetContext(base: Context) : ContextThemeWrapper(base, R.style.Theme_NewLauncher) {

    private var inflater: LayoutInflater? = null

    override fun getSystemService(name: String): Any? {
        if (name != LAYOUT_INFLATER_SERVICE) return super.getSystemService(name)
        inflater?.let { return it }
        // The application inflater has no AppCompat/Material view factory installed (that factory
        // lives on the activity's own inflater), so widget layouts keep their platform view classes.
        return LayoutInflater.from(applicationContext).cloneInContext(this).also { inflater = it }
    }

    private companion object {
        const val LAYOUT_INFLATER_SERVICE = Context.LAYOUT_INFLATER_SERVICE
    }
}
