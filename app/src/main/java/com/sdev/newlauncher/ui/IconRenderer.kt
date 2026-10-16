package com.sdev.newlauncher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.sdev.newlauncher.data.AppInfo
import com.sdev.newlauncher.data.IconPackRepository
import com.sdev.newlauncher.data.IconScope
import com.sdev.newlauncher.data.IconShape
import com.sdev.newlauncher.data.LauncherPrefs

/**
 * The one place that answers "which drawable should this app's icon be right now": the system
 * icon, the active icon pack's override for it, and the mask it is drawn inside — each of those
 * switchable per [IconScope].
 *
 * Results are cached because a mask is rebuilt per drawable and a drawer rebind asks for the same
 * icons over and over; the cache is keyed on everything that changes the answer.
 */
internal object IconRenderer {

    private const val CACHE_SIZE = 512

    private val cache = LruCache<String, Drawable>(CACHE_SIZE)

    /** The icon for [app] as it should appear on [scope]. */
    fun icon(context: Context, app: AppInfo, scope: IconScope): Drawable? {
        val prefs = LauncherPrefs(context)
        val pack = if (prefs.isPackAppliedTo(scope)) prefs.getIconPack() else ""
        val shape = if (prefs.isShapeAppliedTo(scope)) prefs.getIconShape() else IconShape.NONE
        return resolve(context, prefs, app, pack, shape)
    }

    /**
     * The icon for [app] with an explicit pack and shape, bypassing the per-scope switches. Used
     * by the settings previews, which show a shape or a pack whether or not it is applied.
     */
    fun resolve(
        context: Context,
        prefs: LauncherPrefs,
        app: AppInfo,
        packPackage: String,
        shape: IconShape
    ): Drawable? {
        val themed = if (packPackage.isNotEmpty()) IconPackRepository.icon(context, packPackage, app) else null
        val source = themed ?: app.icon ?: return null
        if (shape == IconShape.NONE) return source

        val scalePercent = prefs.getIconShapeScalePercent()
        val cornerPercent = prefs.getIconShapeCornerPercent()
        val key = "${app.key}|$packPackage|${shape.name}|$scalePercent|$cornerPercent|${themed != null}"
        cache.get(key)?.let { return it }
        val masked = MaskedIconDrawable(source, shape, cornerPercent, scalePercent / 100f)
        cache.put(key, masked)
        return masked
    }

    /** Forgets every masked icon; call after the shape, its scale or the icon pack changes. */
    fun clearCache() {
        cache.evictAll()
    }
}
