package com.sdev.newlauncher.data

import android.content.Context

/**
 * Lightweight persistence for the launcher using [android.content.SharedPreferences].
 *
 * Stores:
 *  - the home placements (apps and widgets with their page/row/column and span) and the
 *    ordered list of dock app package names;
 *  - the user's settings (grid sizes, icon size, labels, dock, drawer look, hidden apps).
 */
class LauncherPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- home layout

    /** Number of icon rows the home grid is divided into. */
    fun getHomeRows(): Int =
        prefs.getInt(KEY_HOME_ROWS, DEFAULT_HOME_ROWS).coerceIn(MIN_HOME_ROWS, MAX_HOME_ROWS)

    fun setHomeRows(rows: Int) =
        prefs.edit().putInt(KEY_HOME_ROWS, rows.coerceIn(MIN_HOME_ROWS, MAX_HOME_ROWS)).apply()

    /** How many home pages exist; always at least one more than the last non-empty page. */
    fun getHomePageCount(): Int = prefs.getInt(KEY_HOME_PAGES, 1).coerceAtLeast(1)

    fun setHomePageCount(count: Int) =
        prefs.edit().putInt(KEY_HOME_PAGES, count.coerceAtLeast(1)).apply()

    /**
     * Removes page [index] and everything on it, pulling the later pages back one so the pages
     * that remain are still consecutive.
     */
    fun deleteHomePage(index: Int) {
        val count = getHomePageCount()
        if (count <= 1 || index !in 0 until count) return
        setHomeItems(
            getHomeItems()
                .filterNot { it.page == index }
                .map { if (it.page > index) it.copy(page = it.page - 1) else it }
        )
        setHomePageCount(count - 1)
    }

    /**
     * Inserts an empty page at [index], pushing the items on that page and every later one a page
     * along, so the pages that remain are still consecutive. Inserting at the end is simply one
     * more page.
     */
    fun insertHomePage(index: Int) {
        val count = getHomePageCount()
        val at = index.coerceIn(0, count)
        setHomeItems(
            getHomeItems().map { if (it.page >= at) it.copy(page = it.page + 1) else it }
        )
        setHomePageCount(count + 1)
    }

    /** Moves page [from] to position [to], sliding the pages in between along with it. */
    fun moveHomePage(from: Int, to: Int) {
        val count = getHomePageCount()
        if (from == to || from !in 0 until count || to !in 0 until count) return
        setHomeItems(
            getHomeItems().map { item ->
                val page = when {
                    item.page == from -> to
                    from < to && item.page in (from + 1)..to -> item.page - 1
                    to < from && item.page in to until from -> item.page + 1
                    else -> item.page
                }
                if (page == item.page) item else item.copy(page = page)
            }
        )
    }

    /** Every home placement, migrating the legacy pinned-app list on first use. */
    fun getHomeItems(): List<HomeItem> {
        val stored = prefs.getString(KEY_HOME_ITEMS, null)
        return if (stored == null) migratePinnedApps() else decodeHomeItems(stored)
    }

    fun setHomeItems(items: List<HomeItem>) =
        prefs.edit().putString(KEY_HOME_ITEMS, encodeHomeItems(items)).apply()

    fun isAppOnHome(packageName: String): Boolean =
        getHomeItems().any { !it.isWidget && it.packageName == packageName }

    /** Places an app in the first free cell, growing the page count when the pages are full. */
    fun addAppToHome(packageName: String, activityName: String): Boolean {
        val items = getHomeItems()
        if (items.any { !it.isWidget && it.packageName == packageName }) return false
        val slot = HomeGeometry.firstFreeSlot(
            items,
            getHomeColumns(),
            getHomeRows(),
            pages = getHomePageCount()
        ) ?: return false

        setHomeItems(
            items + HomeItem(
                packageName = packageName,
                activityName = activityName,
                page = slot.page,
                row = slot.row,
                col = slot.col
            )
        )
        if (slot.page + 1 > getHomePageCount()) setHomePageCount(slot.page + 1)
        return true
    }

    fun removeAppFromHome(packageName: String) {
        val items = getHomeItems()
        val kept = items.filterNot { !it.isWidget && it.packageName == packageName }
        if (kept.size != items.size) setHomeItems(kept)
    }

    /** Removes a single placement, identified by [HomeItem.id]. */
    fun removeHomeItem(id: String) {
        val items = getHomeItems()
        val kept = items.filterNot { it.id == id }
        if (kept.size != items.size) setHomeItems(kept)
    }

    // ---------------------------------------------------------------- dock

    fun getDockApps(): List<String> = readList(KEY_DOCK)

    fun setDockApps(packageNames: List<String>) = writeList(KEY_DOCK, packageNames)

    fun isDocked(packageName: String): Boolean = getDockApps().contains(packageName)

    fun pinDockApp(packageName: String) {
        val current = getDockApps()
        if (current.contains(packageName)) return
        setDockApps((current + packageName).take(getDockCapacity()))
    }

    fun unpinDockApp(packageName: String) {
        setDockApps(getDockApps().filterNot { it == packageName })
    }

    /** Drops home items and dock entries whose package is no longer installed. */
    fun removeMissing(installedPackages: Set<String>) {
        val items = getHomeItems()
        val kept = items.filter { it.packageName in installedPackages }
        if (kept.size != items.size) setHomeItems(kept)

        val dock = getDockApps()
        val newDock = dock.filter { it in installedPackages }
        if (newDock.size != dock.size) setDockApps(newDock)
    }

    // ---------------------------------------------------------------- settings

    fun getHomeColumns(): Int =
        prefs.getInt(KEY_HOME_COLUMNS, DEFAULT_HOME_COLUMNS).coerceIn(MIN_HOME_COLUMNS, MAX_HOME_COLUMNS)

    fun setHomeColumns(columns: Int) =
        prefs.edit().putInt(KEY_HOME_COLUMNS, columns.coerceIn(MIN_HOME_COLUMNS, MAX_HOME_COLUMNS)).apply()

    fun getDrawerColumns(): Int =
        prefs.getInt(KEY_DRAWER_COLUMNS, DEFAULT_DRAWER_COLUMNS).coerceIn(MIN_DRAWER_COLUMNS, MAX_DRAWER_COLUMNS)

    fun setDrawerColumns(columns: Int) =
        prefs.edit().putInt(KEY_DRAWER_COLUMNS, columns.coerceIn(MIN_DRAWER_COLUMNS, MAX_DRAWER_COLUMNS)).apply()

    fun getIconSizeDp(): Int =
        prefs.getInt(KEY_ICON_SIZE, DEFAULT_ICON_SIZE).coerceIn(MIN_ICON_SIZE, MAX_ICON_SIZE)

    fun setIconSizeDp(sizeDp: Int) =
        prefs.edit().putInt(KEY_ICON_SIZE, sizeDp.coerceIn(MIN_ICON_SIZE, MAX_ICON_SIZE)).apply()

    fun getShowLabels(): Boolean = prefs.getBoolean(KEY_SHOW_LABELS, true)

    fun setShowLabels(show: Boolean) = prefs.edit().putBoolean(KEY_SHOW_LABELS, show).apply()

    fun getShowDock(): Boolean = prefs.getBoolean(KEY_SHOW_DOCK, true)

    fun setShowDock(show: Boolean) = prefs.edit().putBoolean(KEY_SHOW_DOCK, show).apply()

    fun getDockCapacity(): Int =
        prefs.getInt(KEY_DOCK_CAPACITY, DEFAULT_DOCK_CAPACITY).coerceIn(MIN_DOCK_CAPACITY, MAX_DOCK_CAPACITY)

    fun setDockCapacity(capacity: Int) {
        val valid = capacity.coerceIn(MIN_DOCK_CAPACITY, MAX_DOCK_CAPACITY)
        prefs.edit().putInt(KEY_DOCK_CAPACITY, valid).apply()
        trimDockToCapacity()
    }

    /** Removes dock entries beyond the current capacity. */
    fun trimDockToCapacity() {
        val dock = getDockApps()
        val capacity = getDockCapacity()
        if (dock.size > capacity) setDockApps(dock.take(capacity))
    }

    fun getDrawerOpacity(): Float = prefs.getFloat(KEY_DRAWER_OPACITY, DEFAULT_DRAWER_OPACITY).coerceIn(0f, 1f)

    fun setDrawerOpacity(opacity: Float) =
        prefs.edit().putFloat(KEY_DRAWER_OPACITY, opacity.coerceIn(0f, 1f)).apply()

    fun getHiddenApps(): Set<String> = readList(KEY_HIDDEN).toSet()

    fun setHiddenApps(packageNames: Set<String>) = writeList(KEY_HIDDEN, packageNames.toList())

    /** Toggles whether an app is hidden from the drawer. */
    fun toggleHidden(packageName: String) {
        val current = getHiddenApps().toMutableSet()
        if (!current.remove(packageName)) current.add(packageName)
        setHiddenApps(current)
    }

    /** Adds [packageName] to the hidden set without ever toggling it back on. */
    fun hideApp(packageName: String) {
        val current = getHiddenApps().toMutableSet()
        if (current.add(packageName)) setHiddenApps(current)
    }

    /** Restores every preference, including the home and dock layout, to its default. */
    fun resetAll() = prefs.edit().clear().apply()

    /**
     * Whether the stored widget spans have already been brought in line with the way a span is
     * worked out today. Kept as a preference so the pass runs once and never overwrites a span the
     * user chose with the resize handles.
     */
    fun isHomeSpansRefit(): Boolean = prefs.getBoolean(KEY_HOME_SPANS_REFIT, false)

    fun setHomeSpansRefit() = prefs.edit().putBoolean(KEY_HOME_SPANS_REFIT, true).apply()

    // ---------------------------------------------------------------- drawer layout

    fun getDrawerLayout(): DrawerLayoutMode =
        DrawerLayoutMode.fromStored(prefs.getInt(KEY_DRAWER_LAYOUT, 0))

    fun setDrawerLayout(mode: DrawerLayoutMode) =
        prefs.edit().putInt(KEY_DRAWER_LAYOUT, DrawerLayoutMode.toStored(mode)).apply()

    fun getDrawerRowSpacing(): Int =
        prefs.getInt(KEY_DRAWER_ROW_SPACING, DEFAULT_DRAWER_ROW_SPACING)
            .coerceIn(MIN_DRAWER_ROW_SPACING, MAX_DRAWER_ROW_SPACING)

    fun setDrawerRowSpacing(dp: Int) =
        prefs.edit()
            .putInt(
                KEY_DRAWER_ROW_SPACING,
                dp.coerceIn(MIN_DRAWER_ROW_SPACING, MAX_DRAWER_ROW_SPACING)
            )
            .apply()

    // ---------------------------------------------------------------- dock apps button

    fun getShowAppsButton(): Boolean = prefs.getBoolean(KEY_SHOW_APPS_BUTTON, true)

    fun setShowAppsButton(show: Boolean) = prefs.edit().putBoolean(KEY_SHOW_APPS_BUTTON, show).apply()

    // ---------------------------------------------------------------- custom order

    fun getCustomOrder(): List<String> = readList(KEY_CUSTOM_ORDER)

    fun setCustomOrder(packageNames: List<String>) = writeList(KEY_CUSTOM_ORDER, packageNames)

    /**
     * Returns [apps] ordered by the stored custom order. Apps not yet in the order are
     * appended alphabetically, and the reconciled order is persisted so that drag-reordering
     * always starts from a stable list.
     */
    fun applyCustomOrder(apps: List<AppInfo>): List<AppInfo> {
        val byPackage = apps.associateBy { it.packageName }
        val stored = getCustomOrder()
        val ordered = ArrayList<AppInfo>(apps.size)
        val seen = HashSet<String>()
        for (pkg in stored) {
            val app = byPackage[pkg] ?: continue
            if (seen.add(pkg)) ordered.add(app)
        }
        ordered.addAll(apps.filter { it.packageName !in seen }.sortedBy { it.label.lowercase() })
        val finalOrder = ordered.map { it.packageName }
        if (finalOrder != stored) setCustomOrder(finalOrder)
        return ordered
    }

    // ---------------------------------------------------------------- internal

    /**
     * Converts the legacy ordered list of pinned package names into explicit home placements,
     * laid out row-major from the top-left. The result is persisted so this only runs once.
     */
    private fun migratePinnedApps(): List<HomeItem> {
        val pinned = readList(KEY_PINNED)
        if (pinned.isEmpty()) return emptyList()

        val columns = getHomeColumns()
        val rows = getHomeRows()
        val items = ArrayList<HomeItem>(pinned.size)
        var page = 0
        var row = 0
        var col = 0
        for (packageName in pinned) {
            if (col >= columns) {
                col = 0
                row++
            }
            if (row >= rows) {
                row = 0
                page++
            }
            items.add(HomeItem(packageName = packageName, page = page, row = row, col = col))
            col++
        }

        setHomeItems(items)
        if (page + 1 > getHomePageCount()) setHomePageCount(page + 1)
        return items
    }

    private fun encodeHomeItems(items: List<HomeItem>): String =
        items.joinToString(SEPARATOR) { item ->
            if (item.isWidget) {
                listOf(
                    WIDGET_TAG, item.appWidgetId, item.packageName,
                    item.page, item.row, item.col, item.rowSpan, item.colSpan
                )
            } else {
                listOf(
                    APP_TAG, item.packageName, item.activityName,
                    item.page, item.row, item.col, item.rowSpan, item.colSpan
                )
            }.joinToString(FIELD_SEPARATOR)
        }

    private fun decodeHomeItems(raw: String): List<HomeItem> {
        if (raw.isBlank()) return emptyList()
        return raw.split(SEPARATOR).mapNotNull { entry ->
            val fields = entry.split(FIELD_SEPARATOR)
            if (fields.size != HOME_FIELD_COUNT) return@mapNotNull null
            val page = fields[3].toIntOrNull() ?: return@mapNotNull null
            val row = fields[4].toIntOrNull() ?: return@mapNotNull null
            val col = fields[5].toIntOrNull() ?: return@mapNotNull null
            val rowSpan = fields[6].toIntOrNull() ?: 1
            val colSpan = fields[7].toIntOrNull() ?: 1
            when (fields[0]) {
                WIDGET_TAG -> HomeItem(
                    packageName = fields[2],
                    appWidgetId = fields[1].toIntOrNull() ?: return@mapNotNull null,
                    page = page, row = row, col = col, rowSpan = rowSpan, colSpan = colSpan
                )
                APP_TAG -> HomeItem(
                    packageName = fields[1],
                    activityName = fields[2],
                    page = page, row = row, col = col, rowSpan = rowSpan, colSpan = colSpan
                )
                else -> null
            }
        }
    }

    private fun readList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    private fun writeList(key: String, values: List<String>) {
        prefs.edit().putString(key, values.joinToString(SEPARATOR)).apply()
    }

    companion object {
        private const val PREFS_NAME = "new_launcher_prefs"
        private const val KEY_PINNED = "pinned_apps"
        private const val KEY_DOCK = "dock_apps"
        private const val KEY_HOME_COLUMNS = "home_columns"
        private const val KEY_DRAWER_COLUMNS = "drawer_columns"
        private const val KEY_ICON_SIZE = "icon_size_dp"
        private const val KEY_SHOW_LABELS = "show_labels"
        private const val KEY_SHOW_DOCK = "show_dock"
        private const val KEY_DOCK_CAPACITY = "dock_capacity"
        private const val KEY_DRAWER_OPACITY = "drawer_opacity"
        private const val KEY_HIDDEN = "hidden_apps"
        private const val KEY_DRAWER_LAYOUT = "drawer_layout"
        private const val KEY_DRAWER_ROW_SPACING = "drawer_row_spacing"
        private const val KEY_SHOW_APPS_BUTTON = "show_apps_button"
        private const val KEY_CUSTOM_ORDER = "custom_order"
        private const val KEY_HOME_ROWS = "home_rows"
        private const val KEY_HOME_PAGES = "home_pages"
        private const val KEY_HOME_ITEMS = "home_items"
        private const val KEY_HOME_SPANS_REFIT = "home_spans_refit"
        private const val SEPARATOR = "|"
        private const val FIELD_SEPARATOR = ":"

        /** `tag:pkg:activity:page:row:col:rowSpan:colSpan` for apps and widgets alike. */
        private const val HOME_FIELD_COUNT = 8
        private const val APP_TAG = "a"
        private const val WIDGET_TAG = "w"

        const val MIN_HOME_COLUMNS = 3
        const val MAX_HOME_COLUMNS = 6
        const val DEFAULT_HOME_COLUMNS = 4

        const val MIN_HOME_ROWS = 3
        const val MAX_HOME_ROWS = 8
        const val DEFAULT_HOME_ROWS = 5

        const val MIN_DRAWER_COLUMNS = 3
        const val MAX_DRAWER_COLUMNS = 7
        const val DEFAULT_DRAWER_COLUMNS = 5

        const val MIN_ICON_SIZE = 40
        const val MAX_ICON_SIZE = 72
        const val DEFAULT_ICON_SIZE = 52

        const val MIN_DOCK_CAPACITY = 1
        const val MAX_DOCK_CAPACITY = 8
        const val DEFAULT_DOCK_CAPACITY = 6

        const val MIN_DRAWER_ROW_SPACING = 0
        const val MAX_DRAWER_ROW_SPACING = 24
        const val DEFAULT_DRAWER_ROW_SPACING = 8

        const val DEFAULT_DRAWER_OPACITY = 0.55f
    }
}
