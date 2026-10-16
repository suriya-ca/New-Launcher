package com.sdev.newlauncher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
        getHomeItems().any { it.isApp && it.packageName == packageName } ||
            getFolders().any { folder -> folder.children.any { it.substringBefore('/') == packageName } }

    /** Places an app in the first free cell, growing the page count when the pages are full. */
    fun addAppToHome(packageName: String, activityName: String): Boolean {
        if (isAppOnHome(packageName)) return false
        val items = getHomeItems()
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
        val kept = items.filterNot { it.isApp && it.packageName == packageName }
        if (kept.size != items.size) setHomeItems(kept)
        // Being taken off the home screen means out of a folder too, not out of the app's cell only.
        removePackageFromAllFolders(packageName)
    }

    /** Drops a folder placement whose folder record has gone, and a record whose cell has gone. */
    fun pruneFolders() {
        val items = getHomeItems()
        val folders = getFolders()
        if (folders.isEmpty() && items.none { it.isFolder }) return

        val keptFolders = folders.filter { folder -> items.any { it.isFolder && it.folderId == folder.id } }
        if (keptFolders.size != folders.size) setFolders(keptFolders)

        val recordIds = keptFolders.map { it.id }.toSet()
        val keptItems = items.filterNot { it.isFolder && it.folderId !in recordIds }
        if (keptItems.size != items.size) setHomeItems(keptItems)
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
        // A folder is not tied to a package of its own: it stays, and only the apps inside it
        // that have gone are dropped below.
        val kept = items.filter { it.isFolder || it.packageName in installedPackages }
        if (kept.size != items.size) setHomeItems(kept)

        val folders = getFolders()
        if (folders.isNotEmpty()) {
            val pruned = folders.map { folder ->
                val children = folder.children.filter { it.substringBefore('/') in installedPackages }
                if (children == folder.children) folder else folder.copy(children = children)
            }
            if (pruned != folders) setFolders(pruned)
        }

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

    // ---------------------------------------------------------------- icon shape & icon packs

    fun getIconShape(): IconShape =
        IconShape.fromStored(prefs.getInt(KEY_ICON_SHAPE, IconShape.NONE.ordinal))

    fun setIconShape(shape: IconShape) =
        prefs.edit().putInt(KEY_ICON_SHAPE, IconShape.toStored(shape)).apply()

    /** How much of the cell an app icon fills, as a percentage (80–120). */
    fun getIconShapeScalePercent(): Int =
        prefs.getInt(KEY_ICON_SHAPE_SCALE, DEFAULT_ICON_SHAPE_SCALE).coerceIn(80, 120)

    fun setIconShapeScalePercent(percent: Int) =
        prefs.edit().putInt(KEY_ICON_SHAPE_SCALE, percent.coerceIn(80, 120)).apply()

    /** The corner radius of the [IconShape.ROUNDED] shape, as a percentage of the icon's side. */
    fun getIconShapeCornerPercent(): Int =
        prefs.getInt(KEY_ICON_SHAPE_CORNER, DEFAULT_ICON_SHAPE_CORNER).coerceIn(8, 50)

    fun setIconShapeCornerPercent(percent: Int) =
        prefs.edit().putInt(KEY_ICON_SHAPE_CORNER, percent.coerceIn(8, 50)).apply()

    /** Whether the chosen shape is applied to icons on [scope]. */
    fun isShapeAppliedTo(scope: IconScope): Boolean =
        prefs.getBoolean(scopeKey(KEY_ICON_SHAPE_SCOPE, scope), true)

    fun setShapeAppliedTo(scope: IconScope, enabled: Boolean) =
        prefs.edit().putBoolean(scopeKey(KEY_ICON_SHAPE_SCOPE, scope), enabled).apply()

    /** The package name of the icon pack in use, or `""` for the system icons. */
    fun getIconPack(): String = prefs.getString(KEY_ICON_PACK, "") ?: ""

    fun setIconPack(packageName: String) =
        prefs.edit().putString(KEY_ICON_PACK, packageName).apply()

    /** Whether the chosen icon pack is applied to icons on [scope]. */
    fun isPackAppliedTo(scope: IconScope): Boolean =
        prefs.getBoolean(scopeKey(KEY_ICON_PACK_SCOPE, scope), true)

    fun setPackAppliedTo(scope: IconScope, enabled: Boolean) =
        prefs.edit().putBoolean(scopeKey(KEY_ICON_PACK_SCOPE, scope), enabled).apply()

    private fun scopeKey(prefix: String, scope: IconScope): String = "${prefix}_${scope.name.lowercase()}"

    // ---------------------------------------------------------------- gestures

    fun isSwipeDownEnabled(): Boolean = prefs.getBoolean(KEY_SWIPE_DOWN_ENABLED, true)

    fun setSwipeDownEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_SWIPE_DOWN_ENABLED, enabled).apply()

    fun getSwipeDownAction(): SwipeDownAction =
        SwipeDownAction.fromStored(prefs.getInt(KEY_SWIPE_DOWN_ACTION, 0))

    fun setSwipeDownAction(action: SwipeDownAction) =
        prefs.edit().putInt(KEY_SWIPE_DOWN_ACTION, SwipeDownAction.toStored(action)).apply()

    /** When true, the gesture only fires from empty home space, never over an icon or widget. */
    fun isSwipeDownEmptySpaceOnly(): Boolean = prefs.getBoolean(KEY_SWIPE_DOWN_EMPTY_ONLY, true)

    fun setSwipeDownEmptySpaceOnly(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_SWIPE_DOWN_EMPTY_ONLY, enabled).apply()

    fun isHapticsEnabled(): Boolean = prefs.getBoolean(KEY_HAPTICS, false)

    fun setHapticsEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_HAPTICS, enabled).apply()

    // ---------------------------------------------------------------- blur

    fun isBlurEnabled(): Boolean = prefs.getBoolean(KEY_BLUR_ENABLED, false)

    fun setBlurEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BLUR_ENABLED, enabled).apply()

    fun isBlurTargetEnabled(target: BlurTarget): Boolean =
        prefs.getBoolean("${KEY_BLUR_TARGET}_${target.name.lowercase()}", true)

    fun setBlurTargetEnabled(target: BlurTarget, enabled: Boolean) =
        prefs.edit().putBoolean("${KEY_BLUR_TARGET}_${target.name.lowercase()}", enabled).apply()

    /** Blur strength in dp (4–32). */
    fun getBlurRadiusDp(): Int = prefs.getInt(KEY_BLUR_RADIUS, 12).coerceIn(4, 32)

    fun setBlurRadiusDp(radiusDp: Int) =
        prefs.edit().putInt(KEY_BLUR_RADIUS, radiusDp.coerceIn(4, 32)).apply()

    // ---------------------------------------------------------------- folders

    fun areFoldersEnabled(): Boolean = prefs.getBoolean(KEY_FOLDERS_ENABLED, true)

    fun setFoldersEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_FOLDERS_ENABLED, enabled).apply()

    fun getFolderPreview(): FolderPreview =
        FolderPreview.fromStored(prefs.getInt(KEY_FOLDER_PREVIEW, 0))

    fun setFolderPreview(preview: FolderPreview) =
        prefs.edit().putInt(KEY_FOLDER_PREVIEW, FolderPreview.toStored(preview)).apply()

    fun getFolderBackground(): FolderBackground =
        FolderBackground.fromStored(prefs.getInt(KEY_FOLDER_BACKGROUND, 0))

    fun setFolderBackground(background: FolderBackground) =
        prefs.edit().putInt(KEY_FOLDER_BACKGROUND, FolderBackground.toStored(background)).apply()

    fun getFolderOpenStyle(): FolderOpenStyle =
        FolderOpenStyle.fromStored(prefs.getInt(KEY_FOLDER_OPEN_STYLE, 0))

    fun setFolderOpenStyle(style: FolderOpenStyle) =
        prefs.edit().putInt(KEY_FOLDER_OPEN_STYLE, FolderOpenStyle.toStored(style)).apply()

    /** Columns used by the grid inside an open folder. */
    fun getFolderGridColumns(): Int = prefs.getInt(KEY_FOLDER_COLUMNS, 4).coerceIn(2, 6)

    fun setFolderGridColumns(columns: Int) =
        prefs.edit().putInt(KEY_FOLDER_COLUMNS, columns.coerceIn(2, 6)).apply()

    /** How many apps one folder may hold. */
    fun getFolderMaxApps(): Int = prefs.getInt(KEY_FOLDER_MAX_APPS, 12).coerceIn(4, 36)

    fun setFolderMaxApps(count: Int) =
        prefs.edit().putInt(KEY_FOLDER_MAX_APPS, count.coerceIn(4, 36)).apply()

    /** Whether a folder's name is drawn under its cell, as for app labels. */
    fun showFolderLabels(): Boolean = prefs.getBoolean(KEY_FOLDER_LABELS, true)

    fun setShowFolderLabels(show: Boolean) =
        prefs.edit().putBoolean(KEY_FOLDER_LABELS, show).apply()

    /** Whether deleting a folder puts its apps back on the home screen or removes them too. */
    fun isUnfoldOnDelete(): Boolean = prefs.getBoolean(KEY_FOLDER_UNFOLD, true)

    fun setUnfoldOnDelete(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_FOLDER_UNFOLD, enabled).apply()

    fun getFolders(): List<LauncherFolder> {
        val raw = prefs.getString(KEY_HOME_FOLDERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = obj.optString("name")
                val children = obj.optJSONArray("children")?.let { kids ->
                    (0 until kids.length()).mapNotNull { kids.optString(it).takeIf { s -> s.isNotEmpty() } }
                }.orEmpty()
                LauncherFolder(id, name, children)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setFolders(folders: List<LauncherFolder>) {
        val array = JSONArray()
        for (folder in folders) {
            val obj = JSONObject()
            obj.put("id", folder.id)
            obj.put("name", folder.name)
            obj.put("children", JSONArray(folder.children))
            array.put(obj)
        }
        prefs.edit().putString(KEY_HOME_FOLDERS, array.toString()).apply()
    }

    /** All drawer (app-list) folders. */
    fun getDrawerFolders(): List<DrawerFolder> {
        val raw = prefs.getString(KEY_DRAWER_FOLDERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = obj.optString("name")
                val children = obj.optJSONArray("children")?.let { kids ->
                    (0 until kids.length()).mapNotNull { kids.optString(it).takeIf { s -> s.isNotEmpty() } }
                }.orEmpty()
                DrawerFolder(id, name, children)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setDrawerFolders(folders: List<DrawerFolder>) {
        val array = JSONArray()
        for (folder in folders) {
            val obj = JSONObject()
            obj.put("id", folder.id)
            obj.put("name", folder.name)
            obj.put("children", JSONArray(folder.children))
            array.put(obj)
        }
        prefs.edit().putString(KEY_DRAWER_FOLDERS, array.toString()).apply()
    }

    fun drawerFolderById(id: String): DrawerFolder? = getDrawerFolders().firstOrNull { it.id == id }

    fun upsertDrawerFolder(folder: DrawerFolder) {
        val folders = getDrawerFolders().toMutableList()
        val index = folders.indexOfFirst { it.id == folder.id }
        if (index >= 0) folders[index] = folder else folders.add(folder)
        setDrawerFolders(folders)
    }

    fun renameDrawerFolder(id: String, name: String) {
        val folders = getDrawerFolders().toMutableList()
        val index = folders.indexOfFirst { it.id == id }
        if (index < 0) return
        folders[index] = folders[index].copy(name = name)
        setDrawerFolders(folders)
    }

    fun removeDrawerFolderRecord(id: String) {
        val folders = getDrawerFolders()
        val kept = folders.filterNot { it.id == id }
        if (kept.size != folders.size) setDrawerFolders(kept)
    }

    fun newDrawerFolderId(): String {
        val used = getDrawerFolders().map { it.id }.toSet()
        var n = 1
        while ("d$n" in used) n++
        return "d$n"
    }

    fun addAppToDrawerFolder(folderId: String, appKey: String): Boolean {
        val folders = getDrawerFolders().toMutableList()
        val index = folders.indexOfFirst { it.id == folderId }
        if (index < 0) return false
        val folder = folders[index]
        if (appKey in folder.children) return false
        if (folder.children.size >= getFolderMaxApps()) return false
        folders[index] = folder.copy(children = folder.children + appKey)
        setDrawerFolders(folders)
        return true
    }

    fun removeAppFromDrawerFolder(folderId: String, appKey: String) {
        val folders = getDrawerFolders().toMutableList()
        val index = folders.indexOfFirst { it.id == folderId }
        if (index < 0) return
        val folder = folders[index]
        if (appKey !in folder.children) return
        folders[index] = folder.copy(children = folder.children - appKey)
        setDrawerFolders(folders)
    }

    fun removeAppFromAllDrawerFolders(appKey: String) {
        val folders = getDrawerFolders()
        if (folders.none { appKey in it.children }) return
        setDrawerFolders(folders.map { if (appKey in it.children) it.copy(children = it.children - appKey) else it })
    }

    /** Takes every child of [packageName] out of every drawer folder. */
    fun removePackagesFromDrawerFolders(packageName: String) {
        val prefix = packageName + "/"
        setDrawerFolders(getDrawerFolders().map {
            it.copy(children = it.children.filterNot { k -> k.startsWith(prefix) })
        })
    }

    /** Forggets any empty drawer folder, pruning its record. */
    fun getDrawerFolderCount(): Int = getDrawerFolders().size

    /** Adds or replaces [folder], keyed on its id. */
    fun upsertFolder(folder: LauncherFolder) {
        val folders = getFolders().toMutableList()
        val index = folders.indexOfFirst { it.id == folder.id }
        if (index >= 0) folders[index] = folder else folders.add(folder)
        setFolders(folders)
    }

    fun renameFolder(id: String, name: String) {
        val folders = getFolders().toMutableList()
        val index = folders.indexOfFirst { it.id == id }
        if (index < 0) return
        folders[index] = folders[index].copy(name = name)
        setFolders(folders)
    }

    /** Forgets a folder record; the placements that point at it are removed by the caller. */
    fun removeFolderRecord(id: String) {
        val folders = getFolders()
        val kept = folders.filterNot { it.id == id }
        if (kept.size != folders.size) setFolders(kept)
    }

    /** A folder id that is not in use yet. */
    fun newFolderId(): String {
        val used = getFolders().map { it.id }.toSet()
        var n = 1
        while ("f$n" in used) n++
        return "f$n"
    }

    /** Files [appKey] into folder [folderId]; refuses when the folder is full. */
    fun addAppToFolder(folderId: String, appKey: String): Boolean {
        val folders = getFolders().toMutableList()
        val index = folders.indexOfFirst { it.id == folderId }
        if (index < 0) return false
        val folder = folders[index]
        if (appKey in folder.children) return false
        if (folder.children.size >= getFolderMaxApps()) return false
        folders[index] = folder.copy(children = folder.children + appKey)
        setFolders(folders)
        return true
    }

    fun removeAppFromFolder(folderId: String, appKey: String) {
        val folders = getFolders().toMutableList()
        val index = folders.indexOfFirst { it.id == folderId }
        if (index < 0) return
        val folder = folders[index]
        if (appKey !in folder.children) return
        folders[index] = folder.copy(children = folder.children - appKey)
        setFolders(folders)
    }

    /** Takes [appKey] out of every folder (used when an app is hidden or uninstalled). */
    fun removeAppFromAllFolders(appKey: String) {
        val folders = getFolders()
        if (folders.none { appKey in it.children }) return
        setFolders(folders.map { if (appKey in it.children) it.copy(children = it.children - appKey) else it })
    }

    /** Takes every child of [packageName] out of every folder. */
    fun removePackageFromAllFolders(packageName: String) {
        val folders = getFolders()
        val pruned = folders.map { folder ->
            val children = folder.children.filterNot { it.substringBefore('/') == packageName }
            if (children == folder.children) folder else folder.copy(children = children)
        }
        if (pruned != folders) setFolders(pruned)
    }

    fun folderContaining(appKey: String): LauncherFolder? =
        getFolders().firstOrNull { appKey in it.children }

    // ---------------------------------------------------------------- drawer categories & tabs

    fun areDrawerTabsEnabled(): Boolean = prefs.getBoolean(KEY_DRAWER_TABS_ENABLED, false)

    fun setDrawerTabsEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_DRAWER_TABS_ENABLED, enabled).apply()

    fun showAllDrawerTab(): Boolean = prefs.getBoolean(KEY_DRAWER_TAB_ALL, true)

    fun setShowAllDrawerTab(show: Boolean) =
        prefs.edit().putBoolean(KEY_DRAWER_TAB_ALL, show).apply()

    fun showHiddenDrawerTab(): Boolean = prefs.getBoolean(KEY_DRAWER_TAB_HIDDEN, false)

    fun setShowHiddenDrawerTab(show: Boolean) =
        prefs.edit().putBoolean(KEY_DRAWER_TAB_HIDDEN, show).apply()

    fun getDrawerTabPosition(): DrawerTabPosition =
        DrawerTabPosition.fromStored(prefs.getInt(KEY_DRAWER_TAB_POSITION, 0))

    fun setDrawerTabPosition(position: DrawerTabPosition) =
        prefs.edit().putInt(KEY_DRAWER_TAB_POSITION, DrawerTabPosition.toStored(position)).apply()

    /** Whether a search inside a category stays in that category or covers every app. */
    fun isDrawerTabSearchScoped(): Boolean = prefs.getBoolean(KEY_DRAWER_TAB_SEARCH_SCOPE, true)

    fun setDrawerTabSearchScoped(withinTab: Boolean) =
        prefs.edit().putBoolean(KEY_DRAWER_TAB_SEARCH_SCOPE, withinTab).apply()

    fun getDrawerCategories(): List<DrawerCategory> {
        val raw = prefs.getString(KEY_DRAWER_CATEGORIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = obj.optString("name")
                val packages = obj.optJSONArray("packages")?.let { list ->
                    (0 until list.length()).mapNotNull { list.optString(it).takeIf { s -> s.isNotEmpty() } }
                }.orEmpty()
                DrawerCategory(id, name, packages)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setDrawerCategories(categories: List<DrawerCategory>) {
        val array = JSONArray()
        for (category in categories) {
            val obj = JSONObject()
            obj.put("id", category.id)
            obj.put("name", category.name)
            obj.put("packages", JSONArray(category.packages))
            array.put(obj)
        }
        prefs.edit().putString(KEY_DRAWER_CATEGORIES, array.toString()).apply()
    }

    fun upsertDrawerCategory(category: DrawerCategory) {
        val categories = getDrawerCategories().toMutableList()
        val index = categories.indexOfFirst { it.id == category.id }
        if (index >= 0) categories[index] = category else categories.add(category)
        setDrawerCategories(categories)
    }

    fun deleteDrawerCategory(id: String) {
        val categories = getDrawerCategories()
        val kept = categories.filterNot { it.id == id }
        if (kept.size != categories.size) setDrawerCategories(kept)
    }

    fun newDrawerCategoryId(): String {
        val used = getDrawerCategories().map { it.id }.toSet()
        var n = 1
        while ("c$n" in used) n++
        return "c$n"
    }

    /** Files [packageName] into category [id]. An app can belong to several categories. */
    fun assignAppToCategory(id: String, packageName: String) {
        val categories = getDrawerCategories().toMutableList()
        val index = categories.indexOfFirst { it.id == id }
        if (index < 0) return
        val category = categories[index]
        if (packageName in category.packages) return
        categories[index] = category.copy(packages = category.packages + packageName)
        setDrawerCategories(categories)
    }

    fun unassignAppFromCategory(id: String, packageName: String) {
        val categories = getDrawerCategories().toMutableList()
        val index = categories.indexOfFirst { it.id == id }
        if (index < 0) return
        val category = categories[index]
        if (packageName !in category.packages) return
        categories[index] = category.copy(packages = category.packages - packageName)
        setDrawerCategories(categories)
    }

    /** Drops [packageName] from every category (used when an app is uninstalled). */
    fun removePackageFromAllCategories(packageName: String) {
        val categories = getDrawerCategories()
        if (categories.none { packageName in it.packages }) return
        setDrawerCategories(
            categories.map { if (packageName in it.packages) it.copy(packages = it.packages - packageName) else it }
        )
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
            when {
                item.isWidget -> listOf(
                    WIDGET_TAG, item.appWidgetId, item.packageName,
                    item.page, item.row, item.col, item.rowSpan, item.colSpan
                )
                item.isFolder -> listOf(
                    FOLDER_TAG, item.folderId, "",
                    item.page, item.row, item.col, item.rowSpan, item.colSpan
                )
                else -> listOf(
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
                FOLDER_TAG -> HomeItem(
                    packageName = "",
                    folderId = fields[1],
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
        private const val KEY_ICON_SHAPE = "icon_shape"
        private const val KEY_ICON_SHAPE_SCALE = "icon_shape_scale"
        private const val KEY_ICON_SHAPE_CORNER = "icon_shape_corner"
        private const val KEY_ICON_SHAPE_SCOPE = "icon_shape_scope"
        private const val KEY_ICON_PACK = "icon_pack_pkg"
        private const val KEY_ICON_PACK_SCOPE = "icon_pack_scope"
        private const val KEY_SWIPE_DOWN_ENABLED = "swipe_down_enabled"
        private const val KEY_SWIPE_DOWN_ACTION = "swipe_down_action"
        private const val KEY_SWIPE_DOWN_EMPTY_ONLY = "swipe_down_empty_only"
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_BLUR_ENABLED = "blur_enabled"
        private const val KEY_BLUR_TARGET = "blur_target"
        private const val KEY_BLUR_RADIUS = "blur_radius_dp"
        private const val KEY_FOLDERS_ENABLED = "folders_enabled"
        private const val KEY_FOLDER_PREVIEW = "folder_preview"
        private const val KEY_FOLDER_BACKGROUND = "folder_background"
        private const val KEY_FOLDER_OPEN_STYLE = "folder_open_style"
        private const val KEY_FOLDER_COLUMNS = "folder_grid_columns"
        private const val KEY_FOLDER_MAX_APPS = "folder_max_apps"
        private const val KEY_FOLDER_LABELS = "folder_show_labels"
        private const val KEY_FOLDER_UNFOLD = "folder_unfold_on_delete"
        private const val KEY_HOME_FOLDERS = "home_folders"
    private const val KEY_DRAWER_FOLDERS = "drawer_folders"
        private const val KEY_DRAWER_TABS_ENABLED = "drawer_tabs_enabled"
        private const val KEY_DRAWER_TAB_ALL = "drawer_tab_show_all"
        private const val KEY_DRAWER_TAB_HIDDEN = "drawer_tab_show_hidden"
        private const val KEY_DRAWER_TAB_POSITION = "drawer_tab_position"
        private const val KEY_DRAWER_TAB_SEARCH_SCOPE = "drawer_tab_search_scope"
        private const val KEY_DRAWER_CATEGORIES = "drawer_categories"
        private const val SEPARATOR = "|"
        private const val FIELD_SEPARATOR = ":"

        /** `tag:pkg:activity:page:row:col:rowSpan:colSpan` for apps and widgets alike. */
        private const val HOME_FIELD_COUNT = 8
        private const val APP_TAG = "a"
        private const val WIDGET_TAG = "w"
        private const val FOLDER_TAG = "f"

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

        const val DEFAULT_ICON_SHAPE_SCALE = 100
        const val DEFAULT_ICON_SHAPE_CORNER = 22
    }
    fun folderById(id: String): LauncherFolder? = getFolders().firstOrNull { it.id == id }

}
