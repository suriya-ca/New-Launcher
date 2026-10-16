package com.sdev.newlauncher.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * Finds and reads third-party icon packs.
 *
 * A pack is any installed package that ships an `appfilter.xml` resource, which is the file the
 * pack ecosystem has always used to map a `package/activity` (or a bare package) to one of the
 * pack's drawables. Packs are only discovered on demand, and both the parsed filter and the
 * package's [Resources] are cached because reading them the first time is not cheap.
 */
object IconPackRepository {

    /** An installed icon pack, as offered in the settings picker. */
    data class PackInfo(val packageName: String, val label: String, val icon: Drawable?)

    private const val APPFILTER = "appfilter"
    private val ANDROID_NS: String? = null

    private val resourcesCache = HashMap<String, Resources>()
    private val mappingCache = HashMap<String, Map<String, String>>()

    /** Every installed package that carries an `appfilter.xml`. */
    fun discover(context: Context): List<PackInfo> {
        val packageManager = context.packageManager
        val installed = try {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } catch (_: Throwable) {
            return emptyList()
        }

        val result = mutableListOf<PackInfo>()
        for (info in installed) {
            val packageName = info.packageName
            if (packageName == context.packageName) continue
            if (!hasAppFilter(context, packageName)) continue
            val label = try {
                packageManager.getApplicationLabel(info).toString()
            } catch (_: Throwable) {
                packageName
            }
            val icon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (_: Throwable) {
                null
            }
            result.add(PackInfo(packageName, label, icon))
        }
        return result.sortedBy { it.label.lowercase() }
    }

    /** True when [packPackage] ships an `appfilter.xml`. */
    fun isIconPack(context: Context, packPackage: String): Boolean =
        packPackage.isNotEmpty() && hasAppFilter(context, packPackage)

    /** The pack's drawable for [app], or `null` when the pack does not cover it. */
    fun icon(context: Context, packPackage: String, app: AppInfo): Drawable? {
        val name = drawableName(context, packPackage, app) ?: return null
        return try {
            val resources = resourcesFor(context, packPackage)
            val id = resources.getIdentifier(name, "drawable", packPackage)
            if (id == 0) null else resources.getDrawable(id, null)
        } catch (_: Throwable) {
            null
        }
    }

    /** Drops every cached filter and resource table; the next lookup reads them afresh. */
    fun clearCache() {
        resourcesCache.clear()
        mappingCache.clear()
    }

    private fun drawableName(context: Context, packPackage: String, app: AppInfo): String? {
        val mapping = mapping(context, packPackage)
        if (mapping.isEmpty()) return null
        mapping[app.key.lowercase()]?.let { return it }
        mapping[app.packageName.lowercase()]?.let { return it }
        if (app.activityName.startsWith("${app.packageName}.")) {
            val short = "${app.packageName}/${app.activityName.removePrefix(app.packageName)}"
            mapping[short.lowercase()]?.let { return it }
        }
        return null
    }

    private fun mapping(context: Context, packPackage: String): Map<String, String> {
        mappingCache[packPackage]?.let { return it }
        val mapping = HashMap<String, String>()
        try {
            val resources = resourcesFor(context, packPackage)
            val id = resources.getIdentifier(APPFILTER, "xml", packPackage)
            if (id != 0) {
                val parser = resources.getXml(id)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        val component = parser.getAttributeValue(ANDROID_NS, "component")
                        val drawable = parser.getAttributeValue(ANDROID_NS, "drawable")
                        if (!component.isNullOrEmpty() && !drawable.isNullOrEmpty()) {
                            mapping[normalize(component)] = drawable.removePrefix("@drawable/")
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (_: Throwable) {
            // A malformed or unreadable filter simply means the pack covers nothing.
        }
        mappingCache[packPackage] = mapping
        return mapping
    }

    private fun normalize(component: String): String =
        component.removePrefix("ComponentName{").removeSuffix("}").lowercase()

    private fun hasAppFilter(context: Context, packPackage: String): Boolean = try {
        resourcesFor(context, packPackage).getIdentifier(APPFILTER, "xml", packPackage) != 0
    } catch (_: Throwable) {
        false
    }

    private fun resourcesFor(context: Context, packPackage: String): Resources {
        resourcesCache[packPackage]?.let { return it }
        val resources = context.packageManager.getResourcesForApplication(packPackage)
        resourcesCache[packPackage] = resources
        return resources
    }
}
