package com.sdev.newlauncher

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.util.SizeF
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.sdev.newlauncher.data.AppInfo
import com.sdev.newlauncher.data.AppRepository
import com.sdev.newlauncher.data.BlurTarget
import com.sdev.newlauncher.data.DrawerCategory
import com.sdev.newlauncher.data.DrawerFolder
import com.sdev.newlauncher.data.DrawerLayoutMode
import com.sdev.newlauncher.data.DrawerTabPosition
import com.sdev.newlauncher.data.FolderBackground
import com.sdev.newlauncher.data.FolderOpenStyle
import com.sdev.newlauncher.data.FolderPreview
import com.sdev.newlauncher.data.HomeGeometry
import com.sdev.newlauncher.data.HomeItem
import com.sdev.newlauncher.data.IconPackRepository
import com.sdev.newlauncher.data.IconScope
import com.sdev.newlauncher.data.IconShape
import com.sdev.newlauncher.data.LauncherAppWidgetHost
import com.sdev.newlauncher.data.LauncherFolder
import com.sdev.newlauncher.data.LauncherPrefs
import com.sdev.newlauncher.data.SwipeDownAction
import com.sdev.newlauncher.data.PackageChangeReceiver
import com.sdev.newlauncher.data.WidgetProviderItem
import com.sdev.newlauncher.data.WidgetRepository
import com.sdev.newlauncher.data.WidgetResizeBounds
import com.sdev.newlauncher.databinding.ActivityMainBinding
import com.sdev.newlauncher.ui.AppAdapter
import com.sdev.newlauncher.ui.AppCell
import com.sdev.newlauncher.ui.DrawerPagerAdapter
import com.sdev.newlauncher.ui.DrawerRow
import com.sdev.newlauncher.ui.GridSpacingItemDecoration
import com.sdev.newlauncher.ui.IconRenderer
import com.sdev.newlauncher.ui.MaskedIconDrawable
import com.sdev.newlauncher.ui.OrderableAppAdapter
import com.sdev.newlauncher.ui.SectionedAppAdapter
import com.sdev.newlauncher.ui.StickyHeaderDecoration
import com.sdev.newlauncher.ui.WidgetCell
import com.sdev.newlauncher.ui.WidgetContext
import com.sdev.newlauncher.ui.WidgetRow
import com.sdev.newlauncher.ui.WidgetSheetAdapter
import com.sdev.newlauncher.ui.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Home screen + app drawer. Registered as a home app (see AndroidManifest).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: LauncherPrefs

    private lateinit var dockAdapter: AppAdapter
    private lateinit var gridDrawerAdapter: AppAdapter
    private lateinit var listDrawerAdapter: AppAdapter
    private lateinit var sectionedAdapter: SectionedAppAdapter
    private lateinit var orderAdapter: OrderableAppAdapter
    private lateinit var stickyHeaderDecoration: StickyHeaderDecoration

    /** The app being dragged out of the drawer, and the view it was lifted from. */
    private var pendingDrawerApp: AppInfo? = null
    private var drawerDragSource: View? = null

    /** The box the drag was lifted from, and whether the finger has left it yet. */
    private var drawerDragSourceBounds: Rect? = null
    private var drawerPulled = false

    /** True while the drawer has slid aside so a drag can reach the home screen behind it. */
    private var drawerPeeking = false

    /** Page appended for a drag that ran off the end; dropped again if nothing lands on it. */
    private var dragCreatedPage: Int? = null

    /** Custom-order drag state: where the app started, and the order to fall back to. */
    private var drawerReorderFrom = -1
    private var drawerReorderOrder: List<AppInfo>? = null

    /** Where the drawer's list was when the drag began, so the finger can be judged against it. */
    private var drawerGridBounds: Rect? = null

    private var pagerAdapter: DrawerPagerAdapter? = null
    private var pagerColumns = -1
    private var pagerColumnSpacing = -1
    private var pagerRowSpacing = -1

    private var spacingDecoration: RecyclerView.ItemDecoration? = null
    private var sectionHeaderPositions: List<Int> = emptyList()

    private var allApps: List<AppInfo> = emptyList()
    private var dockAppCount = 0
    private var drawerOpen = false
    private var editMode = false

    /** True while the home's page overview is up: its cards stand in for the page chips. */
    private var overviewShowing = false

    private lateinit var appWidgetHost: LauncherAppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager

    private var pendingWidgetId = -1
    private var pendingProvider: AppWidgetProviderInfo? = null
    private var pendingWidgetColSpan = 1
    private var pendingWidgetRowSpan = 1

    /** Where the widget being added was dropped, when the user placed it by hand. */
    private var pendingWidgetSlot: HomeGeometry.Slot? = null

    /** Widget drag in flight: what was lifted, from where, and whether it has cleared that box. */
    private var widgetDragItem: HomeItem? = null
    private var widgetDragSource: View? = null
    private var widgetDragSourceBounds: Rect? = null
    private var widgetDragPulled = false

    /** The widget picker sheet: on screen, stepped aside for a drag, and what it is filtered to. */
    private var widgetSheetOpen = false
    private var widgetSheetPeeking = false
    private var widgetSheetFilter: String? = null
    private var widgetProviders: List<WidgetProviderItem> = emptyList()
    private lateinit var widgetSheetAdapter: WidgetSheetAdapter

    /** Packages that provide at least one widget, so the app menu knows whether to offer them. */
    private var widgetPackages: Set<String> = emptySet()

    /**
     * Context the widget host views are inflated with. Never the activity itself: its AppCompat
     * inflater would rewrite the widget's platform view classes and RemoteViews would reject them.
     */
    private val widgetContext by lazy { WidgetContext(this) }

    /** Last size reported to each widget, so re-reporting an identical size can't loop layouts. */
    private val widgetSizeCache = HashMap<Int, Long>()

    /** Resize limits per widget, so a resize gesture never has to ask the provider mid-drag. */
    private val widgetBoundsCache = HashMap<Int, WidgetResizeBounds>()

    /** Set once the stored widget spans have been brought up to date with the current rule. */
    private var homeSpansRefitted = false

    /** Cached appearance values so every home cell can be bound without touching prefs. */
    private var labels = true
    private var iconPx = 0

    /**
     * The drawer's selected category tab: `null` is "All", [HIDDEN_TAB] is the hidden-apps tab,
     * anything else is the id of a [DrawerCategory].
     */
    private var selectedTabId: String? = null

    /** The icon settings the renderer cache was filled from, so it is only dropped when they move. */
    private var iconSignature = ""

    /** Whether the platform supports blurring what is behind a window on this device. */
    private var windowBlurSupported = false

    /** True while the drawer tabs are being rebuilt, so their own callbacks are ignored. */
    private var updatingTabs = false

    private val packageReceiver = PackageChangeReceiver { reloadApps() }

    // Widget binding: the system shows its own consent / configure screens, then calls back here.
    private val bindWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            checkAndConfigureWidget()
        } else {
            cancelPendingWidget()
        }
    }

    private val configureWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            finishAddingWidget()
        } else {
            cancelPendingWidget()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = LauncherPrefs(this)
        appWidgetHost = LauncherAppWidgetHost(this)
        appWidgetManager = AppWidgetManager.getInstance(this)
        windowBlurSupported =
            isWindowBlurSupportedByDevice()

        setupSystemBars()
        setupWorkspace()
        setupRecyclerViews()
        setupInteractions()

        reloadApps()
    }

    private fun isWindowBlurSupportedByDevice(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            val wm = getSystemService(WindowManager::class.java) ?: return false
            val method = wm.javaClass.getMethod("isWindowBlurSupported")
            method.invoke(wm) as? Boolean ?: true
        } catch (_: Throwable) {
            true
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        appWidgetHost.startListening()
    }

    override fun onStop() {
        super.onStop()
        appWidgetHost.stopListening()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: IllegalArgumentException) {
            // not registered
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Pressing the system Home button while running returns here.
        exitEditMode()
        closeDrawer()
    }

    override fun onResume() {
        super.onResume()
        // Pick up any changes made in the settings app.
        if (::prefs.isInitialized) {
            applyAppearanceSettings()
            refreshHomeAndDock()
            renderDrawer()
        }
        // A sheet closed just before the system's bind/configure screen took over is finished here:
        // its animation stopped with the window, and a visible sheet would eat touches on the home.
        if (!widgetSheetOpen) hideWidgetSheet()
        // And a sheet left stepped aside by a drag that never ended comes back: kept aside, it would
        // look closed while still counting as open, leaving no way to open it again.
        if (widgetSheetOpen && widgetDragItem == null) setWidgetSheetPeek(false)
    }

    // ---------------------------------------------------------------- setup

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.homeContainer.updatePadding(top = bars.top, bottom = bars.bottom)
            binding.drawerContainer.updatePadding(top = dp(16) + bars.top, bottom = dp(8) + bars.bottom)
            insets
        }
    }

    /** Wires the paged home surface to the app data and the edit-mode callbacks. */
    private fun setupWorkspace() {
        binding.workspace.itemViewFactory = { item ->
            when {
                item.isWidget -> createWidgetCell(item)
                item.isFolder -> LayoutInflater.from(this).inflate(R.layout.item_folder, binding.workspace, false)
                else -> LayoutInflater.from(this).inflate(R.layout.item_app, binding.workspace, false)
            }
        }
        binding.workspace.itemBinder = { view, item ->
            when {
                item.isWidget -> bindWidgetCell(view, item)
                item.isFolder -> bindFolderCell(view, item)
                else -> {
                    val app = appFor(item)
                    if (app == null) {
                        view.visibility = View.INVISIBLE
                    } else {
                        view.visibility = View.VISIBLE
                        AppCell.bind(
                            view,
                            app,
                            labels,
                            iconPx,
                            0,
                            { tapped -> if (editMode) showAppMenu(tapped, view) else launchApp(tapped) },
                            { _, anchor -> onHomeItemLongClick(item, anchor) },
                            scope = IconScope.HOME
                        )
                    }
                }
            }
        }
        binding.workspace.itemRecycler = { view, item ->
            view.clearAnimation()
            // Only forget the hosted widget once the placement itself is gone.
            if (item.isWidget && prefs.getHomeItems().none { it.id == item.id }) {
                releaseWidget(item)
            }
        }
        binding.workspace.cellGapPx = dpExact(HomeGeometry.CELL_GAP_DP)
        binding.workspace.onEmptyLongPress = { enterEditMode() }
        binding.workspace.onPageChanged = { updateHomePageDots(it) }
        binding.workspace.onItemDrop = ::onHomeItemDropped
        binding.workspace.onAddPageRequested = ::onDragRequestedNewPage
        binding.workspace.onPagesReordered = ::onHomePagesReordered
        binding.workspace.onPageDeleteRequested = ::confirmDeleteHomePage
        binding.workspace.onAddPageTapped = ::addHomePageAt
        binding.workspace.onOverviewChanged = ::onOverviewChanged
        binding.workspace.maxPageCount = MAX_HOME_PAGES
        binding.workspace.onDragLocation = ::noteDragPosition
        binding.workspace.resizeBounds = ::widgetResizeBounds
        binding.workspace.onItemResize = ::onHomeItemResized
        binding.workspace.onItemRemoveRequested = ::confirmRemoveHomeItem
        binding.workspace.onItemLaidOut = { view, item ->
            // Reported after the traversal: telling a provider its size can bounce an update back
            // that requests layout, and doing that from inside a layout pass is not allowed.
            val host = (view as? WidgetCell)?.hostView
            if (item.isWidget && host != null) view.post { updateWidgetSize(host, item) }
        }
        binding.workspace.onDragStateChanged = ::onDragState

        // The dock is a drop target: dragging an app onto it pins the app there.
        binding.dockContainer.setOnDragListener { _, event -> onDockDrag(event) }
        // The drawer tracks its own drag: how far the finger moved, and when to step aside.
        binding.drawerContainer.setOnDragListener { view, event -> onDrawerDrag(view, event) }
        // Its list is a drop target of its own, so an app can be dropped back to reorder it.
        binding.drawerGrid.setOnDragListener { view, event -> onDrawerGridDrag(view, event) }

        // The span rule needs the measured cell size, so the one-time widget refit waits for the
        // first layout and then runs from a post, never from inside the layout pass itself.
        binding.workspace.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!homeSpansRefitted) binding.workspace.post {
                if (refitWidgetSpans()) refreshHomeAndDock()
            }
        }
    }

    private fun setupRecyclerViews() {
        dockAdapter = AppAdapter(
            itemLayoutRes = R.layout.item_app_dock,
            onAppClick = ::launchApp,
            onAppLongClick = ::showAppMenu
        ).apply { iconScope = IconScope.DOCK }
        gridDrawerAdapter = AppAdapter(
            onAppClick = ::launchApp,
            onAppLongClick = ::showAppMenu,
            onAppDragStart = ::startDrawerDrag
        ).apply { iconScope = IconScope.DRAWER }
        listDrawerAdapter = AppAdapter(
            itemLayoutRes = R.layout.item_app_list,
            onAppClick = ::launchApp,
            onAppLongClick = ::showAppMenu,
            onAppDragStart = ::startDrawerDrag
        ).apply { iconScope = IconScope.DRAWER }
        sectionedAdapter = SectionedAppAdapter(
            onAppClick = ::launchApp,
            onAppLongClick = ::showAppMenu,
            onAppDragStart = ::startDrawerDrag
        ).apply { iconScope = IconScope.DRAWER }
        orderAdapter = OrderableAppAdapter(
            onAppClick = ::launchApp,
            onAppLongClick = ::showAppMenu,
            onAppDragStart = ::startDrawerDrag
        ).apply { iconScope = IconScope.DRAWER }
        stickyHeaderDecoration = StickyHeaderDecoration(sectionedAdapter)

        binding.dockGrid.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.dockGrid.adapter = dockAdapter

        binding.drawerGrid.layoutManager = GridLayoutManager(this, prefs.getDrawerColumns())
        binding.drawerGrid.adapter = gridDrawerAdapter

        binding.drawerIndexBar.onLetterSelected = ::scrollToSection

        binding.drawerPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updatePageIndicator(position)
        })

        // Dock cells shrink to fit however many icons the user allows.
        binding.dockAppsArea.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            updateDockCellWidth(right - left)
        }

        // The widget sheet: a grid of cards with app headings spanning the full width.
        widgetSheetAdapter = WidgetSheetAdapter(
            rows = emptyList(),
            onSelected = ::addWidgetAtFreeCell,
            onLifted = ::startWidgetDrag
        )
        val sheetLayout = GridLayoutManager(this, WIDGET_SHEET_COLUMNS)
        sheetLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (widgetSheetAdapter.isHeader(position)) WIDGET_SHEET_COLUMNS else 1
        }
        binding.widgetList.layoutManager = sheetLayout
        binding.widgetList.adapter = widgetSheetAdapter

        applyAppearanceSettings()
    }

    /** Applies the user's appearance settings to the already-created views/adapters. */
    private fun applyAppearanceSettings() {
        labels = prefs.getShowLabels()
        iconPx = dp(prefs.getIconSizeDp())

        binding.workspace.columns = prefs.getHomeColumns()
        binding.workspace.rows = prefs.getHomeRows()

        // The resize floor is a span, so it moves with the cell size.
        widgetBoundsCache.clear()

        // The edit bar takes the dock's place while the home screen is being edited.
        binding.dockContainer.visibility =
            if (prefs.getShowDock() && !editMode) View.VISIBLE else View.GONE
        binding.homeEditBar.visibility = if (editMode) View.VISIBLE else View.GONE
        binding.appsButton.visibility = if (prefs.getShowAppsButton()) View.VISIBLE else View.GONE
        updateDockCellWidth(binding.dockAppsArea.width)

        // Icon shape and pack decide how every icon is drawn: a change to either has to empty the
        // renderer's cache, and the pack's parsed filter is rebuilt through the repository.
        val signature = prefs.getIconShape().name + "|" + prefs.getIconShapeScalePercent() + "|" +
            prefs.getIconShapeCornerPercent() + "|" + prefs.getIconPack() + "|" +
            prefs.isShapeAppliedTo(IconScope.HOME) + "|" + prefs.isShapeAppliedTo(IconScope.DOCK) +
            "|" + prefs.isShapeAppliedTo(IconScope.DRAWER) + "|" + prefs.isShapeAppliedTo(IconScope.FOLDER)
        if (signature != iconSignature) {
            iconSignature = signature
            IconRenderer.clearCache()
            IconPackRepository.clearCache()
            rebindIcons()
        }

        prefs.trimDockToCapacity()

        updateBlur()
    }

    /**
     * The drawer and dock adapters are list adapters: handing them the same apps again is a no-op
     * for DiffUtil, so a change that only affects how an icon is drawn — its shape, or the icon
     * pack it comes from — would never reach the cells already on screen. Emptying the renderer's
     * cache is not enough on its own, so every visible row is rebound here, and the paged drawer's
     * adapter is dropped so its nested adapters are rebuilt around fresh icons too.
     */
    private fun rebindIcons() {
        pagerAdapter = null
        if (::dockAdapter.isInitialized) dockAdapter.notifyDataSetChanged()
        if (::gridDrawerAdapter.isInitialized) gridDrawerAdapter.notifyDataSetChanged()
        if (::listDrawerAdapter.isInitialized) listDrawerAdapter.notifyDataSetChanged()
        if (::sectionedAdapter.isInitialized) sectionedAdapter.notifyDataSetChanged()
        if (::orderAdapter.isInitialized) orderAdapter.notifyDataSetChanged()
        if (::dockAdapter.isInitialized) dockAdapter.notifyDataSetChanged()
        if (::binding.isInitialized) binding.workspace.submit(prefs.getHomeItems().filter { !it.isApp || appFor(it) != null })
    }
    /**
     * Turns the real wallpaper blur on behind whichever surface is on screen, and off when none of
     * them is.
     *
     * The blur is the window's own `blurBehindRadius`, not a bitmap copy of the wallpaper: from
     * Android 14 an app can no longer read the wallpaper image at all (WallpaperManager throws),
     * and a window blur is what the platform provides instead — it blurs whatever is genuinely
     * behind the window, which for a translucent home app is the wallpaper. The drawer keeps its
     * own background at the user's opacity, so the slider still decides how much of the blurred
     * wallpaper shows through it.
     */
    private fun updateBlur() {
        val blurOn = prefs.isBlurEnabled()
        val drawerBlur = blurOn && drawerOpen && prefs.isBlurTargetEnabled(BlurTarget.DRAWER)
        val sheetBlur = blurOn && widgetSheetOpen && prefs.isBlurTargetEnabled(BlurTarget.SHEETS)
        val overviewBlur = blurOn && binding.workspace.isOverviewOpen() &&
            prefs.isBlurTargetEnabled(BlurTarget.OVERVIEW)

        binding.drawerContainer.background?.mutate()?.alpha = drawerBackgroundAlpha()

        setWindowBlur(
            window,
            if (drawerBlur || sheetBlur || overviewBlur) prefs.getBlurRadiusDp() else 0
        )
    }

    /**
     * Applies (or clears) the platform's blur-behind on [target]. Does nothing when the device or
     * the user has window blurs switched off, in which case the surfaces keep their plain scrim.
     */
    private fun setWindowBlur(target: Window, radiusDp: Int) {
        if (!windowBlurSupported) return
        val lp = target.attributes
        val wantBlur = radiusDp > 0
        val flags = if (wantBlur) {
            lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        }
        val radius = if (wantBlur) dp(radiusDp) else 0
        if (lp.flags == flags && lp.blurBehindRadius == radius) return
        lp.flags = flags
        lp.blurBehindRadius = radius
        target.attributes = lp
        // Window-level background blur is what actually repaints the wallpaper.
        target.setBackgroundBlurRadius(radius)
    }

    private fun drawerBackgroundAlpha(): Int = (prefs.getDrawerOpacity() * 255).toInt().coerceIn(0, 255)

    private fun drawerScrimColor(): Int = Color.argb(drawerBackgroundAlpha(), 0x0D, 0x10, 0x16)

    /**
     * Handles a downward swipe. Over the drawer or the widget sheet it dismisses them, as it always
     * has; on empty home space it opens the panel the gesture setting names. Over an icon, in edit
     * mode or in the page overview it does nothing, so those gestures keep their own meaning.
     */
    private fun onSwipeDown(startX: Float, startY: Float) {
        when {
            widgetSheetOpen -> closeWidgetSheet()
            drawerOpen -> if (canDismissWithSwipe()) closeDrawer()
            editMode || binding.workspace.isOverviewOpen() -> Unit
            !prefs.isSwipeDownEnabled() -> Unit
            prefs.isSwipeDownEmptySpaceOnly() && workspaceHitsItem(startX, startY) -> Unit
            else -> {
                openSystemPanel(prefs.getSwipeDownAction())
                if (prefs.isHapticsEnabled()) vibrate()
            }
        }
    }

    /** True when a touch at the given root-window point lands on a home item. */
    private fun workspaceHitsItem(rootX: Float, rootY: Float): Boolean {
        val root = IntArray(2)
        val workspace = IntArray(2)
        binding.root.getLocationOnScreen(root)
        binding.workspace.getLocationOnScreen(workspace)
        return binding.workspace.hitsItem(
            rootX + root[0] - workspace[0],
            rootY + root[1] - workspace[1]
        )
    }

    /**
     * Asks the system's status bar to open a panel. There is no public API for this — the shade
     * belongs to SystemUI — so the hidden `StatusBarManager` methods are tried by reflection. A
     * build that refuses them leaves the swipe doing nothing rather than crashing.
     */
    private fun openSystemPanel(action: SwipeDownAction): Boolean {
        val service = getSystemService(Context.STATUS_BAR_SERVICE) ?: return false
        val candidates: List<Pair<String, Array<Class<*>>>> =
            if (action == SwipeDownAction.QUICK_SETTINGS) {
                listOf(
                    "expandSettingsPanel" to arrayOf(),
                    "expandSettingsPanel" to arrayOf(String::class.java)
                )
            } else {
                listOf("expandNotificationsPanel" to arrayOf())
            }
        for ((name, types) in candidates) {
            try {
                val method = service.javaClass.getMethod(name, *types)
                method.invoke(service, *Array(types.size) { null })
                return true
            } catch (_: Throwable) {
                // Try the next overload, or give up: the gesture is optional.
            }
        }
        return false
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? Vibrator ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(GESTURE_HAPTIC_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /**
     * Spreads the dock icons evenly across the whole dock: every cell is an equal share of
     * the room between the dock's edges and the apps button, so the gaps between icons —
     * and between the outer icons and the edges — all come out the same.
     */
    private fun updateDockCellWidth(availableWidth: Int) {
        if (availableWidth <= 0) return
        val count = dockAppCount.coerceAtLeast(1)
        val cell = availableWidth / count
        dockAdapter.cellWidthPx = cell
        dockAdapter.iconSizePx = min(dp(DOCK_MAX_ICON_DP), (cell - dp(8)).coerceAtLeast(dp(20)))
    }

    private fun setupInteractions() {
        binding.appsButton.setOnClickListener { openDrawer() }
        binding.root.onSwipeUp = { if (!widgetSheetOpen) openDrawer() }

        binding.editWallpaper.setOnClickListener { openWallpaper() }
        binding.editWidgets.setOnClickListener { openWidgetSheet(null) }
        binding.editSettings.setOnClickListener {
            startActivity(Intent(this, HomeSettingsActivity::class.java))
        }
        binding.editDone.setOnClickListener { exitEditMode() }

        // The widget sheet is part of this window, so a widget can be dragged out of it. Nothing
        // here accepts a drag, though: a drop target left lying over the home would swallow every
        // widget released behind it, so the grid is the only place a widget can land.
        binding.widgetSheetScrim.setOnClickListener { closeWidgetSheet() }
        binding.widgetSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderWidgetSheet()
        })

        binding.drawerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderDrawer()
        })

        binding.drawerTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = onDrawerTabSelected(tab)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        // A swipe down on the empty home screen opens the chosen panel; elsewhere it dismisses
        // whatever is open, which is what the gesture has always done.
        binding.root.onSwipeDown = { x, y -> onSwipeDown(x, y) }

        // Back never finishes the activity: it dismisses what is open, innermost first.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    widgetSheetOpen -> closeWidgetSheet()
                    drawerOpen -> closeDrawer()
                    binding.workspace.isOverviewOpen() -> binding.workspace.closeOverview()
                    editMode -> exitEditMode()
                }
            }
        })
    }

    // ---------------------------------------------------------------- home

    private fun reloadApps() {
        lifecycleScope.launch {
            val apps = AppRepository.loadApps(this@MainActivity)
            // Which packages offer widgets: asked on a background thread, and used to decide
            // whether the app menu gets a Widgets entry.
            val providers = withContext(Dispatchers.IO) {
                try {
                    appWidgetManager.installedProviders
                } catch (_: Exception) {
                    emptyList()
                }
            }
            widgetPackages = providers.map { it.provider.packageName }.toSet()
            allApps = apps
            prefs.removeMissing(apps.map { it.packageName }.toSet())
            refreshHomeAndDock()
            renderDrawer()
        }
    }

    private fun refreshHomeAndDock() {
        refitWidgetSpans()
        // A folder is a placement too: it survives even though no installed app answers to it.
        prefs.pruneFolders()
        val items = prefs.getHomeItems().filter { !it.isApp || appFor(it) != null }
        val highestPage = items.maxOfOrNull { it.page } ?: 0
        val pages = maxOf(prefs.getHomePageCount(), highestPage + 1)
        // The stored count is the user's own: a page they added stays until they delete it.
        if (pages > prefs.getHomePageCount()) prefs.setHomePageCount(pages)

        binding.workspace.pageCount = pages
        binding.workspace.submit(items)
        binding.homeHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        buildPageStrip(pages)

        val byPackage = allApps.associateBy { it.packageName }
        val dock = prefs.getDockApps().mapNotNull { byPackage[it] }
        dockAppCount = dock.size
        dockAdapter.submitList(dock)
        updateDockCellWidth(binding.dockAppsArea.width)
    }

    /** Resolves a home placement to a launchable app, tolerating legacy package-only entries. */
    private fun appFor(item: HomeItem): AppInfo? = allApps.firstOrNull {
        it.packageName == item.packageName &&
            (item.activityName.isEmpty() || it.activityName == item.activityName)
    }

    /**
     * The page indicator. Outside edit mode it is the plain row of dots; in edit mode every page
     * becomes a numbered chip that can be tapped, dragged to a new position and deleted.
     */
    private fun buildPageStrip(count: Int) {
        val group: LinearLayout = binding.homePageIndicator
        group.removeAllViews()
        // The overview shows the pages themselves, with their own ✕ on each; a row of chips for
        // the same pages underneath would only be saying it twice.
        if (overviewShowing || (count <= 1 && !editMode)) {
            group.visibility = View.GONE
            return
        }
        group.visibility = View.VISIBLE
        for (index in 0 until count) {
            group.addView(if (editMode) pageChip(index, count) else pageDot())
        }
        updateHomePageDots(binding.workspace.currentPage())
    }

    /** The page overview opened or closed: it stands in for the chip row while it is showing. */
    private fun onOverviewChanged(open: Boolean) {
        overviewShowing = open
        buildPageStrip(prefs.getHomePageCount())
        updateBlur()
    }

    private fun pageDot(): ImageView {
        val dot = ImageView(this)
        val params = LinearLayout.LayoutParams(dp(7), dp(7))
        params.marginStart = dp(4)
        params.marginEnd = dp(4)
        dot.layoutParams = params
        dot.setImageResource(R.drawable.dot_unselected)
        return dot
    }

    /** A numbered page chip: tap to go there, long press and drag to reorder, ✕ to delete it. */
    private fun pageChip(index: Int, count: Int): View {
        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.background = ContextCompat.getDrawable(this, R.drawable.bg_page_chip)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val gap = dp(PAGE_CHIP_GAP_DP) / 2
        params.marginStart = gap
        params.marginEnd = gap
        chip.layoutParams = params
        chip.setPadding(dp(12), dp(6), dp(8), dp(6))

        val number = TextView(this)
        number.text = (index + 1).toString()
        number.setTextColor(ContextCompat.getColor(this, R.color.page_chip_text))
        chip.addView(number)

        val close = ImageView(this)
        val closeParams = LinearLayout.LayoutParams(dp(14), dp(14))
        closeParams.marginStart = dp(8)
        close.layoutParams = closeParams
        close.setImageResource(R.drawable.ic_chip_close)
        close.contentDescription = getString(R.string.home_page_delete)
        close.alpha = if (count > 1) 1f else 0.3f
        close.isEnabled = count > 1
        close.setOnClickListener { confirmDeleteHomePage(index) }
        chip.addView(close)

        chip.setOnClickListener { binding.workspace.goToPage(index) }

        var dragging = false
        var startRawX = 0f
        var previewTo = index
        chip.setOnLongClickListener {
            dragging = true
            previewTo = index
            true
        }
        chip.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val group: LinearLayout = binding.homePageIndicator
                    val cells = group.childCount.coerceAtLeast(1)
                    val step = (group.width / cells).coerceAtLeast(1)
                    val target = (index + (event.rawX - startRawX).toInt() / step)
                        .coerceIn(0, cells - 1)
                    if (target != previewTo && view.parent === group) {
                        group.removeViewAt(group.indexOfChild(view))
                        group.addView(view, target)
                        renumberPageChips()
                        previewTo = target
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@setOnTouchListener false
                    dragging = false
                    if (event.actionMasked == MotionEvent.ACTION_UP && previewTo != index) {
                        onHomePagesReordered(index, previewTo)
                    } else {
                        buildPageStrip(prefs.getHomePageCount())
                    }
                    true
                }
                else -> false
            }
        }
        return chip
    }

    /** Reprints the numbers on the page chips after a reorder preview moved one of them. */
    private fun renumberPageChips() {
        val group: LinearLayout = binding.homePageIndicator
        for (position in 0 until group.childCount) {
            val number = (group.getChildAt(position) as? LinearLayout)?.getChildAt(0) as? TextView
            number?.text = (position + 1).toString()
        }
    }

    private fun updateHomePageDots(current: Int) {
        val group: LinearLayout = binding.homePageIndicator
        val chipDrawable = ContextCompat.getDrawable(this, R.drawable.bg_page_chip)
        val selectedChipDrawable = ContextCompat.getDrawable(this, R.drawable.bg_page_chip_selected)
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (editMode) {
                child.background = if (index == current) selectedChipDrawable else chipDrawable
            } else {
                (child as? ImageView)?.setImageResource(
                    if (index == current) R.drawable.dot_selected else R.drawable.dot_unselected
                )
            }
        }
    }

    /** Deletes a page from the strip, asking first when it still holds apps or widgets. */
    private fun confirmDeleteHomePage(index: Int) {
        if (prefs.getHomePageCount() <= 1) return
        val onPage = prefs.getHomeItems().count { it.page == index }
        if (onPage == 0) {
            deleteHomePage(index)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.home_page_delete_title, index + 1))
            .setMessage(resources.getQuantityString(R.plurals.home_page_delete_message, onPage, onPage))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.home_page_delete_confirm) { _, _ -> deleteHomePage(index) }
            .show()
    }

    private fun deleteHomePage(index: Int) {
        prefs.deleteHomePage(index)
        refreshHomeAndDock()
        binding.workspace.post {
            binding.workspace.goToPage(index.coerceAtMost(prefs.getHomePageCount() - 1))
        }
    }

    /** A page chip was dragged to [to]: the pages move with it, and so do the items on them. */
    private fun onHomePagesReordered(from: Int, to: Int) {
        prefs.moveHomePage(from, to)
        refreshHomeAndDock()
        // With the overview up, the strip itself slides over to the page that moved; the scroll
        // only has to follow along when the overview is not what is on screen.
        if (!binding.workspace.isOverviewOpen()) {
            binding.workspace.post { binding.workspace.goToPage(to) }
        }
    }

    private fun onHomeItemLongClick(item: HomeItem, anchor: View) {
        if (editMode) {
            binding.workspace.startItemDrag(item, anchor)
        } else if (item.isFolder) {
            showFolderMenu(item, anchor)
        } else {
            appFor(item)?.let { showAppMenu(it, anchor) }
        }
    }

    /** Applies a completed drag: exact placement, swap, folder creation, or the nearest free cell. */
    private fun onHomeItemDropped(id: String, page: Int, row: Int, col: Int) {
        // A widget lifted out of the picker is not on the home screen yet: it is being placed. The
        // grid reaches up under the sheet, so a long press released without pulling the card clear
        // would otherwise drop the widget behind the sheet it was never lifted out of.
        if (widgetDragItem?.id == id) {
            if (widgetDragPulled) placePendingWidget(page, row, col)
            return
        }

        val items = prefs.getHomeItems().toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) {
            // Not a home placement yet: an app was dragged out of the drawer.
            dropDrawerAppOnHome(id, page, row, col)
            return
        }

        val moving = items[index]
        val target = moving.copy(page = page, row = row, col = col)
        val columns = binding.workspace.columns
        val rows = binding.workspace.rows

        // Dropping an app onto another app makes a folder; onto a folder it joins it.
        val app = if (moving.isApp) appFor(moving) else null
        if (app != null && prefs.areFoldersEnabled()) {
            val occupant = items.firstOrNull { it.id != id && it.overlaps(target) }
            if (occupant != null && occupant.isApp && occupant.packageName != moving.packageName) {
                if (createFolder(occupant, app, items)) return
            } else if (occupant != null && occupant.isFolder) {
                if (addToFolderAt(target, app, items)) return
            }
        }

        if (HomeGeometry.fits(items, target, columns, rows)) {
            items[index] = target
        } else {
            val occupant = items.firstOrNull { it.id != id && it.overlaps(target) }
            if (occupant != null && occupant.colSpan == moving.colSpan && occupant.rowSpan == moving.rowSpan) {
                val occupantIndex = items.indexOfFirst { it.id == occupant.id }
                items[index] = target
                items[occupantIndex] =
                    occupant.copy(page = moving.page, row = moving.row, col = moving.col)
            } else {
                val slot = HomeGeometry.nearestFreeSlot(
                    items,
                    columns,
                    rows,
                    page,
                    row,
                    col,
                    moving.colSpan,
                    moving.rowSpan,
                    excludeId = id
                ) ?: return
                items[index] = moving.copy(page = slot.page, row = slot.row, col = slot.col)
            }
        }

        prefs.setHomeItems(items)
        if (page + 1 > prefs.getHomePageCount()) prefs.setHomePageCount(page + 1)
        refreshHomeAndDock()
    }

    /** The delete badge on the selected item was tapped: confirm before the item goes. */
    private fun confirmRemoveHomeItem(item: HomeItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.home_remove_widget_title)
            .setMessage(R.string.home_remove_widget_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.home_remove) { _, _ -> removeHomeItem(item) }
            .show()
    }

    private fun removeHomeItem(item: HomeItem) {
        if (item.isFolder) {
            deleteFolder(item.folderId)
            return
        }
        prefs.removeHomeItem(item.id)
        refreshHomeAndDock()
    }

    // ---------------------------------------------------------------- folders

    /** Resolves a folder child's `package/activity` key to a live app, or null once it is gone. */
    private fun appByKey(key: String): AppInfo? {
        val packageName = key.substringBefore('/')
        val activityName = key.substringAfter('/', "")
        return allApps.firstOrNull {
            it.packageName == packageName && (activityName.isEmpty() || it.activityName == activityName)
        }
    }

    /** Draws a folder's cell: its backdrop, a preview of the apps inside and its name. */
    private fun bindFolderCell(view: View, item: HomeItem) {
        val folder = prefs.folderById(item.folderId)
        val background = view.findViewById<View>(R.id.folder_background)
        val grid = view.findViewById<LinearLayout>(R.id.folder_grid)
        val label = view.findViewById<TextView>(R.id.folder_label)

        // Rebound views keep the lift dimming the drag left on them.
        view.alpha = 1f
        background.background = folderBackdrop()
        label.text = folder?.name.orEmpty()
        label.visibility = if (prefs.showFolderLabels()) View.VISIBLE else View.GONE

        val preview = prefs.getFolderPreview()
        val perRow = if (preview == FolderPreview.GRID_3X3) 3 else 2
        val cellWidth = (binding.workspace.width / binding.workspace.columns.coerceAtLeast(1)).coerceAtLeast(dp(48))
        val miniPx = (cellWidth * if (perRow == 3) 0.24f else 0.36f).toInt().coerceAtLeast(dp(12))
        val marginPx = dp(2)

        val apps = (folder?.children ?: emptyList())
            .mapNotNull { appByKey(it) }
            .take(perRow * perRow)

        grid.removeAllViews()
        grid.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        for (chunk in apps.chunked(perRow)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (app in chunk) {
                val icon = ImageView(this).apply {
                    setImageDrawable(IconRenderer.icon(this@MainActivity, app, IconScope.FOLDER))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val params = LinearLayout.LayoutParams(miniPx, miniPx)
                params.setMargins(marginPx, marginPx, marginPx, marginPx)
                row.addView(icon, params)
            }
            grid.addView(row)
        }

        view.setOnClickListener { if (editMode) showFolderMenu(item, view) else openFolder(item) }
        view.setOnLongClickListener {
            onHomeItemLongClick(item, view)
            true
        }
    }

    /**
     * The backdrop behind a folder's preview: the folder's own choice, or — on "match icon shape" —
     * the icon shape itself, so a folder always looks like the icons it sits among.
     */
    private fun folderBackdrop(): Drawable {
        val color = ColorDrawable(FOLDER_BACKDROP_COLOR)
        val shapeKind = when (prefs.getFolderBackground()) {
            FolderBackground.CIRCLE -> IconShape.CIRCLE
            FolderBackground.SQUARE -> IconShape.NONE
            FolderBackground.ROUNDED -> IconShape.ROUNDED
            FolderBackground.AUTO -> when (prefs.getIconShape()) {
                IconShape.NONE -> IconShape.ROUNDED
                else -> prefs.getIconShape()
            }
        }
        if (shapeKind == IconShape.NONE) {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(FOLDER_BACKDROP_COLOR)
            }
        }
        return MaskedIconDrawable(color, shapeKind, prefs.getIconShapeCornerPercent(), 1f)
    }

    private fun openFolder(item: HomeItem) {
        val folder = prefs.folderById(item.folderId) ?: return
        showFolderSheet(folder)
    }

    /** Shows a folder's contents; the presentation follows the folder open-style setting. */
    private fun showFolderSheet(folder: LauncherFolder) {
        val sheet = layoutInflater.inflate(R.layout.sheet_folder, null)
        val dialog = Dialog(this)
        val sheetStyle = prefs.getFolderOpenStyle() == FolderOpenStyle.SHEET
        // The dialog is a window of its own, so it carries the blur: it blurs the home screen
        // behind it, which the activity's own window cannot do for it.
        val blurred = prefs.isBlurEnabled() && prefs.isBlurTargetEnabled(BlurTarget.SHEETS)

        if (blurred) {
            // A full-screen transparent window: the blur and the scrim fill it, the sheet sits
            // inside it. Tapping the scrim is what dismisses, since the sheet no longer owns the
            // whole window and a touch outside it would otherwise be swallowed.
            val container = FrameLayout(this)
            val scrim = View(this).apply {
                setBackgroundColor(SHEET_SCRIM_COLOR)
                setOnClickListener { dialog.dismiss() }
            }
            container.addView(
                scrim,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            val sheetParams = FrameLayout.LayoutParams(
                if (sheetStyle) FrameLayout.LayoutParams.MATCH_PARENT
                else (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            sheetParams.gravity = if (sheetStyle) Gravity.BOTTOM else Gravity.CENTER
            container.addView(sheet, sheetParams)
            dialog.setContentView(container)
        } else {
            dialog.setContentView(sheet)
        }

        val title = sheet.findViewById<TextView>(R.id.folder_sheet_title)
        title.text = folder.name.ifEmpty { getString(R.string.folder_default_name) }
        sheet.findViewById<View>(R.id.folder_sheet_rename).setOnClickListener {
            dialog.dismiss()
            promptRenameFolder(folder.id)
        }
        sheet.findViewById<View>(R.id.folder_sheet_delete).setOnClickListener {
            dialog.dismiss()
            confirmDeleteFolder(folder.id)
        }
        fillFolderSheet(sheet, folder, dialog)

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (blurred) {
                // The window fills the screen so its blur covers all of it; the scrim dims.
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                setWindowBlur(this, prefs.getBlurRadiusDp())
            } else {
                setDimAmount(0.55f)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                if (sheetStyle) {
                    setGravity(Gravity.BOTTOM)
                    setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT
                    )
                } else {
                    setGravity(Gravity.CENTER)
                    setLayout(
                        (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                        WindowManager.LayoutParams.WRAP_CONTENT
                    )
                }
            }
        }
    }

    /** Fills a folder sheet with the apps inside it, as rows of ordinary app cells. */
    private fun fillFolderSheet(sheet: View, folder: LauncherFolder, dialog: Dialog) {
        val area = sheet.findViewById<LinearLayout>(R.id.folder_sheet_apps)
        val empty = sheet.findViewById<TextView>(R.id.folder_sheet_empty)
        val scroll = sheet.findViewById<View>(R.id.folder_sheet_scroll)
        area.removeAllViews()

        val apps = folder.children.mapNotNull { appByKey(it) }
        empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        scroll.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        if (apps.isEmpty()) return

        val columns = prefs.getFolderGridColumns()
        val showLabels = prefs.getShowLabels()
        val iconPx = dp(prefs.getIconSizeDp())
        for (chunk in apps.chunked(columns)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (app in chunk) {
                val cell = layoutInflater.inflate(R.layout.item_app, row, false)
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                AppCell.bind(
                    cell,
                    app,
                    showLabels,
                    iconPx,
                    0,
                    { tapped ->
                        dialog.dismiss()
                        launchApp(tapped)
                    },
                    { _, anchor -> showFolderChildMenu(folder, app, anchor, dialog) },
                    scope = IconScope.FOLDER
                )
            }
            // Keep the cells of a part-filled last row as wide as the ones above them.
            repeat(columns - chunk.size) {
                row.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            area.addView(row)
        }
    }

    private fun showFolderChildMenu(folder: LauncherFolder, app: AppInfo, anchor: View, dialog: Dialog) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_FOLDER_REMOVE, 0, R.string.folder_remove_app)
        popup.menu.add(0, MENU_INFO, 1, R.string.action_app_info)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_FOLDER_REMOVE -> {
                    prefs.removeAppFromFolder(folder.id, app.key)
                    dialog.dismiss()
                    refreshHomeAndDock()
                }
                MENU_INFO -> AppRepository.openAppInfo(this, app)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun showFolderMenu(item: HomeItem, anchor: View) {
        val folder = prefs.folderById(item.folderId) ?: return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_FOLDER_OPEN, 0, R.string.folder_open)
        popup.menu.add(0, MENU_FOLDER_RENAME, 1, R.string.action_rename)
        popup.menu.add(0, MENU_FOLDER_DELETE, 2, R.string.folder_delete)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_FOLDER_OPEN -> openFolder(item)
                MENU_FOLDER_RENAME -> promptRenameFolder(folder.id)
                MENU_FOLDER_DELETE -> confirmDeleteFolder(folder.id)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun promptRenameFolder(folderId: String) {
        val folder = prefs.folderById(folderId) ?: return
        val input = EditText(this).apply {
            setText(folder.name)
            hint = getString(R.string.folder_name_hint)
            setSelection(text.length)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val pad = dp(20)
            params.setMargins(pad, dp(8), pad, 0)
            layoutParams = params
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_rename)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.folder_default_name) }
                prefs.renameFolder(folderId, name)
                refreshHomeAndDock()
            }
            .show()
    }

    private fun confirmDeleteFolder(folderId: String) {
        val folder = prefs.folderById(folderId) ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.folder_delete_confirm, folder.name))
            .setMessage(R.string.folder_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.folder_delete) { _, _ -> deleteFolder(folderId) }
            .show()
    }

    /** Removes a folder's cell; its apps return to the home screen unless the setting says otherwise. */
    private fun deleteFolder(folderId: String) {
        val folder = prefs.folderById(folderId) ?: return
        val items = prefs.getHomeItems()
        val placement = items.firstOrNull { it.isFolder && it.folderId == folderId }
        val kept = items.filterNot { it.isFolder && it.folderId == folderId }.toMutableList()
        prefs.removeFolderRecord(folderId)

        if (placement != null && prefs.isUnfoldOnDelete()) {
            val columns = binding.workspace.columns
            val rows = binding.workspace.rows
            for (key in folder.children) {
                val app = appByKey(key) ?: continue
                val placed = HomeItem(app.packageName, app.activityName)
                val slot = HomeGeometry.freeSlotOnPage(kept, columns, rows, placement.page)
                    ?: HomeGeometry.firstFreeSlot(
                        kept,
                        columns,
                        rows,
                        pages = maxOf(prefs.getHomePageCount(), placement.page + 1)
                    )
                    ?: break
                kept.add(placed.copy(page = slot.page, row = slot.row, col = slot.col))
            }
        }

        prefs.setHomeItems(kept)
        refreshHomeAndDock()
    }

    /**
     * Puts a folder where [occupant] sat and files both apps inside it. The folder takes over the
     * occupant's cell, and neither app keeps a placement of its own.
     */
    private fun createFolder(occupant: HomeItem?, incoming: AppInfo, items: List<HomeItem>): Boolean {
        if (!prefs.areFoldersEnabled()) return false
        val first = occupant?.takeIf { it.isApp } ?: return false
        val folderId = prefs.newFolderId()
        val name = getString(R.string.folder_default_name)
        prefs.upsertFolder(LauncherFolder(folderId, name, listOf(first.appKey, incoming.key)))

        val cell = HomeItem(
            packageName = "",
            folderId = folderId,
            page = first.page,
            row = first.row,
            col = first.col,
            rowSpan = first.rowSpan,
            colSpan = first.colSpan
        )
        val kept = items.filterNot { it.id == first.id || (it.isApp && it.appKey == incoming.key) } + cell
        prefs.setHomeItems(kept)
        refreshHomeAndDock()
        return true
    }

    /**
     * Files an app into the folder it was dropped on. Returns false — leaving the caller to fall
     * back to ordinary placement — when there is no folder there or it is already full.
     */
    private fun addToFolderAt(target: HomeItem, incoming: AppInfo, items: List<HomeItem>): Boolean {
        val placement = items.firstOrNull { it.isFolder && it.overlaps(target) } ?: return false
        val folder = prefs.folderById(placement.folderId) ?: return false
        if (incoming.key !in folder.children && !prefs.addAppToFolder(folder.id, incoming.key)) {
            // The folder is full: the caller places the app on the grid instead.
            return false
        }
        // The app is in the folder now, so its own placement goes.
        prefs.setHomeItems(items.filterNot { it.isApp && it.appKey == incoming.key })
        refreshHomeAndDock()
        return true
    }

    private fun enterEditMode() {
        if (editMode || drawerOpen) return
        editMode = true
        binding.workspace.editing = true
        applyAppearanceSettings()
        // Re-bind so widget cells switch their touch-blocking overlay on.
        refreshHomeAndDock()
    }

    private fun exitEditMode() {
        if (!editMode) return
        editMode = false
        binding.workspace.editing = false

        // The page count belongs to the user now: a page they added stays until they delete it.
        applyAppearanceSettings()
        refreshHomeAndDock()
    }

    /**
     * A ＋ card in the overview was tapped: an empty page goes in at [index], pushing what is
     * already on that page and everything after it one page along. The card to the right of the
     * pages asks for the end; the card to their left asks for the front.
     */
    private fun addHomePageAt(index: Int) {
        val count = prefs.getHomePageCount()
        if (count >= MAX_HOME_PAGES) return
        prefs.insertHomePage(index.coerceIn(0, count))
        refreshHomeAndDock()
        binding.workspace.post {
            binding.workspace.goToPage(index.coerceAtMost(prefs.getHomePageCount() - 1))
        }
    }

    private fun openWallpaper() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), null))
        } catch (_: Exception) {
            // No wallpaper picker available.
        }
    }

    // ---------------------------------------------------------------- widget sheet

    /**
     * Opens the widget picker. It lives inside this window rather than in a dialog, which is what
     * lets a widget be dragged straight out of it onto the home screen. [packageFilter] narrows it
     * to one app's widgets, which is what the app menu asks for.
     *
     * Edit mode is deliberately left as it was: the picker is part of arranging the home, so
     * coming back from it — or from a widget's configure screen — should land in the same mode.
     */
    private fun openWidgetSheet(packageFilter: String?) {
        if (widgetSheetOpen) return
        if (drawerOpen) closeDrawer()
        widgetSheetFilter = packageFilter

        val cellWidthDp = homeCellWidthDp()
        val cellHeightDp = homeCellHeightDp()
        lifecycleScope.launch {
            val providers = withContext(Dispatchers.IO) {
                WidgetRepository.loadProviders(
                    this@MainActivity,
                    cellWidthDp,
                    cellHeightDp,
                    HomeGeometry.CELL_GAP_DP
                )
            }
            widgetProviders = providers
            widgetPackages = providers.map { it.packageName }.toSet()
            showWidgetSheet()
        }
    }

    private fun showWidgetSheet() {
        val panel = binding.widgetSheetPanel
        val metrics = resources.displayMetrics
        // A close still in flight ends by hiding the sheet, and cancelling that animation runs the
        // end action too: take it off screen first so the rise that follows is not undone by it.
        hideWidgetSheet()
        // Tall enough to browse, never so tall that it swallows the home screen behind it.
        binding.widgetList.layoutParams = binding.widgetList.layoutParams.apply {
            height = min(dp(WIDGET_LIST_MAX_DP), (metrics.heightPixels * 0.42f).toInt())
        }
        binding.widgetsTitle.text = widgetSheetFilter?.let { packageName ->
            val appName = widgetProviders.firstOrNull { it.packageName == packageName }?.appName
                ?: allApps.firstOrNull { it.packageName == packageName }?.label
            getString(R.string.widgets_for_app, appName ?: packageName)
        } ?: getString(R.string.widgets_title)

        binding.widgetSearch.text?.clear()
        renderWidgetSheet()

        widgetSheetOpen = true
        widgetSheetPeeking = false
        binding.widgetSheet.visibility = View.VISIBLE
        binding.widgetSheetScrim.alpha = 0f
        updateBlur()
        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate()
                .translationY(0f)
                .setDuration(SHEET_ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            binding.widgetSheetScrim.animate().alpha(1f).setDuration(SHEET_ANIM_MS).start()
        }
    }

    /** Rebuilds the sheet's rows for the current filter and search text, grouped by app. */
    private fun renderWidgetSheet() {
        if (!::widgetSheetAdapter.isInitialized) return
        val query = binding.widgetSearch.text?.toString().orEmpty().trim()
        val matching = widgetProviders.filter { provider ->
            (widgetSheetFilter == null || provider.packageName == widgetSheetFilter) &&
                (query.isEmpty() ||
                    provider.label.contains(query, ignoreCase = true) ||
                    provider.appName.contains(query, ignoreCase = true))
        }

        val rows = ArrayList<WidgetRow>(matching.size + 4)
        matching.groupBy { it.appName }.forEach { (appName, group) ->
            rows.add(WidgetRow.Header(appName))
            group.forEach { rows.add(WidgetRow.Widget(it)) }
        }
        widgetSheetAdapter.submit(rows)

        binding.widgetsEmpty.setText(
            if (query.isEmpty()) R.string.widgets_empty else R.string.widgets_no_match
        )
        binding.widgetsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.widgetList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Slides the sheet away and forgets what it was showing. */
    private fun closeWidgetSheet() {
        if (!widgetSheetOpen) {
            // Nothing is open, so make sure nothing is left on screen either.
            hideWidgetSheet()
            return
        }
        widgetSheetOpen = false
        widgetSheetPeeking = false
        widgetSheetFilter = null
        hideKeyboardFrom(binding.widgetSearch)

        val panel = binding.widgetSheetPanel
        panel.animate().cancel()
        binding.widgetSheetScrim.animate().cancel()
        panel.animate()
            .translationY(panel.height.toFloat())
            .setDuration(SHEET_ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                hideWidgetSheet()
                panel.translationY = 0f
            }
            .start()
        binding.widgetSheetScrim.animate().alpha(0f).setDuration(SHEET_ANIM_MS).start()
    }

    /**
     * Takes the sheet off screen at once, translations and all. Sheet animations stop while the
     * window is hidden, so a close interrupted by the system's bind/configure screen is finished
     * here instead when the activity comes back: a sheet left on screen would keep swallowing every
     * touch meant for the home.
     */
    private fun hideWidgetSheet() {
        binding.widgetSheetPanel.animate().cancel()
        binding.widgetSheetScrim.animate().cancel()
        binding.widgetSheetPanel.translationY = 0f
        binding.widgetSheetScrim.alpha = 1f
        binding.widgetSheet.visibility = View.GONE
        updateBlur()
    }

    /**
     * Slides the sheet out of the way of a widget being carried to the home screen, and back again.
     * Nothing here accepts a drag, so this is only for the eye's sake: the point is to get the sheet
     * out of the way so the user can see the grid they are aiming at.
     */
    private fun setWidgetSheetPeek(peek: Boolean) {
        if (!widgetSheetOpen || widgetSheetPeeking == peek) return
        widgetSheetPeeking = peek
        val panel = binding.widgetSheetPanel
        panel.animate().cancel()
        binding.widgetSheetScrim.animate().cancel()
        panel.animate()
            .translationY(if (peek) panel.height.toFloat() else 0f)
            .setDuration(PEEK_ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        // The dim goes with the panel, so the home screen reads as the thing in use.
        binding.widgetSheetScrim.animate()
            .alpha(if (peek) 0f else 1f)
            .setDuration(PEEK_ANIM_MS)
            .start()
    }

    /** Home grid cell size in dp, which is how a provider's declared sizes are measured. */
    private fun homeCellWidthDp(): Int =
        (binding.workspace.cellWidthPx() / resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun homeCellHeightDp(): Int =
        (binding.workspace.cellHeightPx() / resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /** Creates the home view for a widget placement, hosting the widget or a placeholder. */
    private fun createWidgetCell(item: HomeItem): View {
        val cell = WidgetCell(this)
        val info = appWidgetManager.getAppWidgetInfo(item.appWidgetId)
        if (info == null) {
            cell.setPlaceholder(
                LayoutInflater.from(this).inflate(R.layout.item_widget_missing, cell, false)
            )
        } else {
            cell.attachHost(appWidgetHost.createView(widgetContext, item.appWidgetId, info))
        }
        return cell
    }

    private fun bindWidgetCell(view: View, item: HomeItem) {
        val cell = view as? WidgetCell ?: return
        cell.setEditing(editMode)
        cell.overlay.setOnLongClickListener {
            binding.workspace.startItemDrag(item, cell)
            true
        }
        cell.overlay.setOnClickListener {
            binding.workspace.toggleSelection(item.id)
        }
    }

    /**
     * How far the widget behind [item] may be resized, cached because gestures ask repeatedly.
     * A provider's floors are capped at the span the widget already has: a widget the refit pass
     * shrank below the size its provider asks for would otherwise be stuck, with every candidate
     * either unchanged or overlapping a neighbour.
     */
    private fun widgetResizeBounds(item: HomeItem): WidgetResizeBounds? {
        if (!item.isWidget) return null
        val provider = providerResizeBounds(item.appWidgetId)
        return provider.copy(
            minColSpan = provider.minColSpan.coerceAtMost(item.colSpan),
            minRowSpan = provider.minRowSpan.coerceAtMost(item.rowSpan)
        )
    }

    /**
     * What the provider behind [appWidgetId] allows, before the current span is taken into account.
     *
     * Both axes are always offered. A provider's `resizeMode` only says which directions its author
     * laid out a layout for; several common widgets are frozen on one axis because of it — the UPI
     * widget declares "vertical only" and the Airtel one declares nothing at all, so neither could
     * be made wider or narrower on the grid. The minimum span is still respected, so nothing can be
     * squashed flat, and the provider is told the size it ended up with either way.
     */
    private fun providerResizeBounds(appWidgetId: Int): WidgetResizeBounds {
        widgetBoundsCache[appWidgetId]?.let { return it }
        // A provider that has gone leaves a placeholder behind, with no size of its own to respect.
        // Limits read for an id that is allocated but still unbound are not remembered, or they
        // would pin a placeholder's own onto the widget that turns up a moment later.
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
        if (info == null) {
            return WidgetResizeBounds(
                minColSpan = 1,
                minRowSpan = 1,
                horizontal = true,
                vertical = true
            )
        }

        val minimum = WidgetRepository.minimumResizeSpans(
            info,
            homeCellWidthDp(),
            homeCellHeightDp(),
            HomeGeometry.CELL_GAP_DP
        )

        val bounds = WidgetResizeBounds(
            minColSpan = minimum.first,
            minRowSpan = minimum.second,
            horizontal = true,
            vertical = true
        )
        widgetBoundsCache[appWidgetId] = bounds
        return bounds
    }

    /** Applies a finished resize: the same placement, a new span, reported to the provider. */
    private fun onHomeItemResized(resized: HomeItem) {
        prefs.setHomeItems(prefs.getHomeItems().map { if (it.id == resized.id) resized else it })
        refreshHomeAndDock()
    }

    /**
     * One-time pass over the stored widgets so their spans follow the current rule: a widget a
     * shade wider than one cell now takes one cell instead of two. The result is remembered, so a
     * span the user picked with the resize handles is never overwritten again. Returns true when
     * something changed.
     */
    private fun refitWidgetSpans(): Boolean {
        if (homeSpansRefitted) return false
        if (binding.workspace.width <= 0) return false

        homeSpansRefitted = true
        prefs.setHomeSpansRefit()

        val items = prefs.getHomeItems()
        if (items.none { it.isWidget }) return false

        val cellWidthDp = homeCellWidthDp()
        val cellHeightDp = homeCellHeightDp()
        val columns = binding.workspace.columns
        val rows = binding.workspace.rows
        var changed = false

        val refitted = items.map { item ->
            if (!item.isWidget) return@map item
            val info = appWidgetManager.getAppWidgetInfo(item.appWidgetId) ?: return@map item
            val spans = WidgetRepository.placementSpans(
                info,
                cellWidthDp,
                cellHeightDp,
                HomeGeometry.CELL_GAP_DP
            )
            val colSpan = spans.first.coerceIn(1, (columns - item.col).coerceAtLeast(1))
            val rowSpan = spans.second.coerceIn(1, (rows - item.row).coerceAtLeast(1))
            if (item.colSpan == colSpan && item.rowSpan == rowSpan) item
            else {
                changed = true
                item.copy(colSpan = colSpan, rowSpan = rowSpan)
            }
        }

        if (!changed) return false
        prefs.setHomeItems(refitted)
        return true
    }

    /** Reports the room the widget actually got, in dp, so it can lay its content out. */
    private fun updateWidgetSize(hostView: AppWidgetHostView, item: HomeItem) {
        val widthPx = hostView.width
        val heightPx = hostView.height
        if (widthPx <= 0 || heightPx <= 0) return

        // Reporting the same size again makes the widget re-layout, which would loop.
        val key = (widthPx.toLong() shl 32) or heightPx.toLong()
        if (widgetSizeCache[item.appWidgetId] == key) return
        widgetSizeCache[item.appWidgetId] = key

        val density = resources.displayMetrics.density
        val size = SizeF(widthPx / density, heightPx / density)
        // Preserve the existing options (configuration extras, etc.) and set the sizes.
        val options = Bundle(appWidgetManager.getAppWidgetOptions(item.appWidgetId))
        hostView.updateAppWidgetSize(options, listOf(size))
    }

    /**
     * Adds [provider] at the first cell that can hold it, for a tap in the picker. The user picks
     * the spot instead by long-pressing the card and dragging it onto the home screen.
     */
    private fun addWidgetAtFreeCell(provider: WidgetProviderItem) {
        closeWidgetSheet()
        pendingWidgetSlot = null
        startPendingWidget(provider)
        bindPendingWidget()
    }

    /** Allocates an id for [provider] and records how much of the home grid it needs. */
    private fun startPendingWidget(provider: WidgetProviderItem) {
        pendingWidgetId = appWidgetHost.allocateAppWidgetId()
        pendingProvider = provider.providerInfo
        pendingWidgetColSpan = provider.minSpanX.coerceIn(1, binding.workspace.columns)
        pendingWidgetRowSpan = provider.minSpanY.coerceIn(1, binding.workspace.rows)
    }

    /** Asks the system to let this launcher host the pending widget, then follows on. */
    private fun bindPendingWidget() {
        val widgetId = pendingWidgetId
        val provider = pendingProvider
        if (widgetId < 0 || provider == null) {
            cancelPendingWidget()
            return
        }

        val bound = appWidgetManager.bindAppWidgetIdIfAllowed(widgetId, provider.provider)
        if (bound) {
            checkAndConfigureWidget()
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            }
            bindWidgetLauncher.launch(intent)
        }
    }

    private fun checkAndConfigureWidget() {
        val info = pendingProvider
        if (info?.configure != null) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = info.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
            }
            configureWidgetLauncher.launch(intent)
        } else {
            finishAddingWidget()
        }
    }

    /**
     * Places the freshly bound widget. A widget the user dropped by hand goes where it landed,
     * falling back to the nearest free cell and then to the first free one when that is taken.
     */
    private fun finishAddingWidget() {
        val widgetId = pendingWidgetId
        val info = pendingProvider
        if (widgetId < 0 || info == null) {
            cancelPendingWidget()
            return
        }

        val items = prefs.getHomeItems()
        val columns = binding.workspace.columns
        val rows = binding.workspace.rows
        val colSpan = pendingWidgetColSpan
        val rowSpan = pendingWidgetRowSpan

        val wanted = pendingWidgetSlot
        val slot = if (wanted == null) {
            HomeGeometry.firstFreeSlot(
                items,
                columns,
                rows,
                colSpan = colSpan,
                rowSpan = rowSpan,
                pages = prefs.getHomePageCount()
            )
        } else {
            val candidate = HomeItem(
                packageName = info.provider.packageName,
                appWidgetId = widgetId,
                page = wanted.page,
                row = wanted.row,
                col = wanted.col,
                rowSpan = rowSpan,
                colSpan = colSpan
            )
            when {
                HomeGeometry.fits(items, candidate, columns, rows) -> wanted
                else -> HomeGeometry.nearestFreeSlot(
                    items,
                    columns,
                    rows,
                    wanted.page,
                    wanted.row,
                    wanted.col,
                    colSpan,
                    rowSpan
                ) ?: HomeGeometry.firstFreeSlot(
                    items,
                    columns,
                    rows,
                    colSpan = colSpan,
                    rowSpan = rowSpan,
                    pages = prefs.getHomePageCount()
                )
            }
        }
        if (slot == null) {
            cancelPendingWidget()
            return
        }

        prefs.setHomeItems(
            items + HomeItem(
                packageName = info.provider.packageName,
                appWidgetId = widgetId,
                page = slot.page,
                row = slot.row,
                col = slot.col,
                rowSpan = rowSpan,
                colSpan = colSpan
            )
        )
        if (slot.page + 1 > prefs.getHomePageCount()) prefs.setHomePageCount(slot.page + 1)

        clearPendingWidget()
        refreshHomeAndDock()
    }

    private fun cancelPendingWidget() {
        val widgetId = pendingWidgetId
        clearPendingWidget()
        if (widgetId < 0) return
        try {
            appWidgetHost.deleteAppWidgetId(widgetId)
        } catch (_: Exception) {
            // Already released.
        }
    }

    private fun clearPendingWidget() {
        pendingWidgetId = -1
        pendingProvider = null
        pendingWidgetColSpan = 1
        pendingWidgetRowSpan = 1
        pendingWidgetSlot = null
    }

    private fun releaseWidget(item: HomeItem) {
        if (!item.isWidget) return
        widgetSizeCache.remove(item.appWidgetId)
        widgetBoundsCache.remove(item.appWidgetId)
        try {
            appWidgetHost.deleteAppWidgetId(item.appWidgetId)
        } catch (_: Exception) {
            // Already released.
        }
    }

    // ---------------------------------------------------------------- drag & drop

    /**
     * Tracks the drag in flight: everything a drag borrows — the drawer's place on screen, a page
     * added for it — is given back when it ends.
     */
    private fun onDragState(dragging: Boolean) {
        if (dragging) {
            dragCreatedPage = null
            return
        }
        highlightDock(false)
        finishDrawerDrag()
        finishWidgetDrag()
        dropUnusedDragPage()
    }

    /**
     * Lifts [provider] out of the widget sheet. The widget id is allocated up front so the drag can
     * carry it, and handed back if the drag ends without the widget being placed.
     */
    private fun startWidgetDrag(provider: WidgetProviderItem, source: View) {
        startPendingWidget(provider)

        val item = HomeItem(
            packageName = provider.packageName,
            appWidgetId = pendingWidgetId,
            rowSpan = pendingWidgetRowSpan,
            colSpan = pendingWidgetColSpan
        )
        widgetDragItem = item
        widgetDragSource = source
        widgetDragSourceBounds = screenBounds(source)
        widgetDragPulled = false

        // A drop target is only told about a drag the view hierarchy can see, and the home has to be
        // one of them for the widget to be able to land there. Coming from the drawer's app menu,
        // the home is still hidden behind the drawer as it slides away.
        binding.homeContainer.visibility = View.VISIBLE

        source.startDragAndDrop(
            ClipData.newPlainText(WIDGET_DRAG_LABEL, provider.label),
            View.DragShadowBuilder(source),
            Workspace.DragState(item),
            0
        )
        source.alpha = 0.3f
        onDragState(true)
    }

    /**
     * Judges a widget drag from where the finger is: the sheet steps aside as soon as the card has
     * been pulled clear of its own box, and stays aside until the drag ends. The home reports every
     * drag position it sees, and its grid reaches up under the sheet — so the report arrives while
     * the finger is still on the card, which is exactly when it has to be judged.
     */
    private fun noteWidgetDragPosition(screenX: Int, screenY: Int) {
        if (widgetDragItem == null || widgetDragPulled) return
        if (!pulledClearOf(widgetDragSourceBounds, screenX, screenY)) return
        widgetDragPulled = true
        setWidgetSheetPeek(true)
    }

    /** Gives back what a widget drag borrowed: the card's looks, or the widget itself. */
    private fun finishWidgetDrag() {
        val source = widgetDragSource ?: return
        widgetDragSource = null
        widgetDragItem = null
        widgetDragSourceBounds = null
        widgetDragPulled = false
        source.alpha = 1f
        if (widgetSheetOpen) setWidgetSheetPeek(false)
        // Nowhere to put it: the allocated id goes back, and the picker is ready for another try.
        if (pendingWidgetSlot == null && pendingProvider != null) cancelPendingWidget()
    }

    /**
     * Remembers where the widget was released, then asks the system to bind it. The provider's own
     * configure screen, when it has one, runs before [finishAddingWidget] places it.
     */
    private fun placePendingWidget(page: Int, row: Int, col: Int) {
        pendingWidgetSlot = HomeGeometry.Slot(page, row, col)
        closeWidgetSheet()
        bindPendingWidget()
    }

    /** Lifts [app] out of the drawer so it can be dropped on the home screen. */
    private fun startDrawerDrag(app: AppInfo, source: View) {
        val custom = prefs.getDrawerLayout() == DrawerLayoutMode.CUSTOM
        pendingDrawerApp = app
        drawerDragSource = source
        drawerPulled = false
        drawerReorderFrom = if (custom) orderAdapter.indexOf(app) else -1
        drawerReorderOrder = if (custom) orderAdapter.order() else null
        drawerGridBounds = if (custom) screenBounds(binding.drawerGrid) else null
        drawerDragSourceBounds = screenBounds(source)

        // Drop targets are only told about a drag the view hierarchy can see, and the home has
        // to be one of them for the app to be able to land there.
        binding.homeContainer.visibility = View.VISIBLE

        source.startDragAndDrop(
            ClipData.newPlainText(DRAWER_DRAG_LABEL, app.packageName),
            View.DragShadowBuilder(source),
            Workspace.DragState(HomeItem(app.packageName, app.activityName)),
            0
        )
        source.alpha = 0.3f
        onDragState(true)
    }

    /** Bounds of [view] on screen: drag bookkeeping is all in one coordinate space. */
    private fun screenBounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height
        )
    }

    /**
     * Judges a drawer drag from where the finger is: how far it has travelled, and whether the
     * drawer should step aside. Every drag position the app sees is fed through here, whichever
     * view happened to receive it.
     */
    private fun noteDragPosition(screenX: Int, screenY: Int) {
        if (pendingDrawerApp == null) {
            // The same report feeds a widget drag, whose own listener stops seeing the finger the
            // moment the sheet has stepped aside.
            noteWidgetDragPosition(screenX, screenY)
            return
        }
        // A long press only becomes a drag once the app has been pulled clear of its own icon.
        // Until then the finger is still "on" the app, so the home is left alone.
        if (!drawerPulled) {
            if (!pulledClearOf(drawerDragSourceBounds, screenX, screenY)) return
            drawerPulled = true
        }
        // The custom layout keeps its list in place until the finger leaves it, so an app dragged
        // back over the list reorders it instead of leaving for the home screen.
        val overList = drawerGridBounds?.contains(screenX, screenY) == true
        setDrawerPeek(!overList)
    }

    /**
     * True once the finger has moved clear of the box a drag was lifted from. Small wobbles during
     * a long press stay inside that box, so they are not mistaken for a drag.
     */
    private fun pulledClearOf(bounds: Rect?, screenX: Int, screenY: Int): Boolean {
        if (bounds == null) return true
        val margin = dpExact(DRAG_PULL_MARGIN_DP)
        return screenX < bounds.left - margin || screenX > bounds.right + margin ||
            screenY < bounds.top - margin || screenY > bounds.bottom + margin
    }

    /** [noteDragPosition] for a drag event delivered to [view]. */
    private fun noteDragPosition(view: View, event: DragEvent) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        noteDragPosition(event.x.toInt() + location[0], event.y.toInt() + location[1])
    }

    /**
     * Slides the drawer down out of the way (or back again) while a drag is in flight. It is only
     * moved, never hidden: staying laid out means a finger that comes back over the list still
     * finds it.
     */
    private fun setDrawerPeek(peek: Boolean) {
        if (!drawerOpen || drawerPeeking == peek) return
        drawerPeeking = peek
        val drawer = binding.drawerContainer
        drawer.animate().cancel()
        binding.homeContainer.visibility = View.VISIBLE
        drawer.animate()
            .translationY(if (peek) drawer.height.toFloat() else 0f)
            .setDuration(PEEK_ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // Back on top: the home screen goes behind the drawer again.
                if (!peek) binding.homeContainer.visibility = View.INVISIBLE
            }
            .start()
    }

    /**
     * Gives back what a drawer drag borrowed: the list order if the app was not dropped back into
     * the drawer, the drawer's place on screen, and — for a press that never moved — the menu.
     *
     * The borrow is given back whenever a drawer drag was in flight, not only while the app it
     * carried is still known: dropping on the home or into the dock clears [pendingDrawerApp]
     * before the drag ends, and the tile that was dimmed for the whole drag would otherwise stay
     * dim until something happened to rebind it.
     */
    private fun finishDrawerDrag() {
        val app = pendingDrawerApp
        val source = drawerDragSource
        if (app == null && source == null) return
        val pulled = drawerPulled
        pendingDrawerApp = null
        drawerDragSource = null
        drawerPulled = false
        drawerDragSourceBounds = null
        drawerGridBounds = null
        restoreDrawerOrder()
        val wasPeeking = drawerPeeking
        setDrawerPeek(false)
        if (!wasPeeking && drawerOpen) binding.homeContainer.visibility = View.INVISIBLE
        source?.alpha = 1f
        // A press that was never pulled off its icon asked for the app's menu, not a drag.
        if (!pulled && app != null && source != null && source.isAttachedToWindow) {
            showAppMenu(app, source)
        }
    }

    /** Drag events over the drawer as a whole: the list steps aside once the drag leaves it. */
    private fun onDrawerDrag(view: View, event: DragEvent): Boolean {
        if (event.localState !is Workspace.DragState) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return pendingDrawerApp != null
            DragEvent.ACTION_DRAG_LOCATION -> noteDragPosition(view, event)
        }
        return true
    }

    /** Drag events over the drawer's list: in the custom layout, cells reorder under the finger. */
    private fun onDrawerGridDrag(view: View, event: DragEvent): Boolean {
        if (event.localState !is Workspace.DragState) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED ->
                return pendingDrawerApp != null && prefs.getDrawerLayout() == DrawerLayoutMode.CUSTOM
            DragEvent.ACTION_DRAG_LOCATION -> {
                noteDragPosition(view, event)
                reorderDrawerUnder(event.x, event.y)
            }
            DragEvent.ACTION_DROP -> if (drawerReorderFrom >= 0) persistDrawerOrder()
        }
        return true
    }

    /** Moves the dragged app to whatever drawer cell the finger is over. */
    private fun reorderDrawerUnder(x: Float, y: Float) {
        if (drawerReorderFrom < 0) return
        val child = binding.drawerGrid.findChildViewUnder(x, y) ?: return
        val to = binding.drawerGrid.getChildAdapterPosition(child)
        if (to == RecyclerView.NO_POSITION || to == drawerReorderFrom) return
        if (orderAdapter.moveItem(drawerReorderFrom, to)) drawerReorderFrom = to
    }

    /** Saves the custom order as it stands after a drop back into the list. */
    private fun persistDrawerOrder() {
        drawerReorderOrder = null
        drawerReorderFrom = -1
        prefs.setCustomOrder(orderAdapter.order().map { it.packageName })
    }

    /** Puts the custom order back the way it was, for a drag that did not land in the list. */
    private fun restoreDrawerOrder() {
        val original = drawerReorderOrder ?: return
        drawerReorderOrder = null
        drawerReorderFrom = -1
        if (prefs.getDrawerLayout() == DrawerLayoutMode.CUSTOM) orderAdapter.submit(original)
    }

    /** The dock is a drop target: an app dropped on it is pinned there. */
    private fun onDockDrag(event: DragEvent): Boolean {
        val state = event.localState as? Workspace.DragState
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> state != null && !state.item.isWidget
            DragEvent.ACTION_DRAG_ENTERED -> {
                highlightDock(true)
                true
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                highlightDock(false)
                true
            }
            DragEvent.ACTION_DROP -> {
                highlightDock(false)
                if (state != null) pinDraggedAppToDock(state.item)
                true
            }
            else -> false
        }
    }

    /** Grows the dock a little while an app hovers over it. */
    private fun highlightDock(active: Boolean) {
        val scale = if (active) 1.06f else 1f
        binding.dockAppsArea.scaleX = scale
        binding.dockAppsArea.scaleY = scale
    }

    /**
     * Pins the dragged app to the dock. An app lifted out of the drawer is simply added; one
     * lifted off the home screen moves there, which is what dragging it off its cell means.
     */
    private fun pinDraggedAppToDock(item: HomeItem) {
        if (item.isWidget) return
        val fromHome = prefs.getHomeItems().any { it.id == item.id }
        prefs.pinDockApp(item.packageName)
        if (fromHome) prefs.removeHomeItem(item.id)
        // The app left the drawer rather than being dropped back into the list.
        restoreDrawerOrder()
        pendingDrawerApp = null
        if (drawerOpen) closeDrawer()
        refreshHomeAndDock()
    }

    /** A drag ran past the last page: add one for it, and take it away again if it stays empty. */
    private fun onDragRequestedNewPage() {
        // A widget still held inside its own card is not reaching for another page: the grid sits
        // under the picker, so it reports the finger before the drag has really left the card.
        if (widgetDragItem != null && !widgetDragPulled) return
        if (dragCreatedPage != null) return
        val count = prefs.getHomePageCount()
        if (count >= MAX_HOME_PAGES) return
        dragCreatedPage = count
        prefs.setHomePageCount(count + 1)
        refreshHomeAndDock()
    }

    /** Drops a page that existed only so a drag could reach past the last one. */
    private fun dropUnusedDragPage() {
        val created = dragCreatedPage ?: return
        dragCreatedPage = null
        // Anything landing on the page keeps it; an untouched page was only ever there for the drag.
        if (prefs.getHomeItems().any { it.page == created }) return
        if (prefs.getHomePageCount() != created + 1) return
        prefs.setHomePageCount(created)
        refreshHomeAndDock()
    }

    /** Places an app dropped out of the drawer, exactly where it landed when it fits. */
    private fun dropDrawerAppOnHome(id: String, page: Int, row: Int, col: Int) {
        val app = pendingDrawerApp ?: return
        if (id != HomeItem(app.packageName, app.activityName).id) return

        val items = prefs.getHomeItems()
        val index = items.indexOfFirst { it.isApp && it.packageName == app.packageName }
        val target = HomeItem(app.packageName, app.activityName, page = page, row = row, col = col)

        // A drawer app dropped on a folder joins it; dropped on another app it makes a folder.
        if (prefs.areFoldersEnabled()) {
            val occupant = items.firstOrNull {
                it.overlaps(target) && !(it.isApp && it.packageName == app.packageName)
            }
            val handled = when {
                occupant == null -> false
                occupant.isFolder -> addToFolderAt(target, app, items)
                occupant.isApp -> createFolder(occupant, app, items)
                else -> false
            }
            if (handled) {
                restoreDrawerOrder()
                pendingDrawerApp = null
                closeDrawer()
                return
            }
        }

        val moving = if (index >= 0) {
            // Already on the home screen: the drop moves it rather than adding a second copy.
            items[index].copy(page = page, row = row, col = col)
        } else {
            target
        }

        val columns = binding.workspace.columns
        val rows = binding.workspace.rows
        val slot = if (HomeGeometry.fits(items, moving, columns, rows)) {
            HomeGeometry.Slot(page, row, col)
        } else {
            HomeGeometry.nearestFreeSlot(
                items,
                columns,
                rows,
                page,
                row,
                col,
                moving.colSpan,
                moving.rowSpan,
                excludeId = moving.id
            ) ?: return
        }

        val placed = moving.copy(page = slot.page, row = slot.row, col = slot.col)
        prefs.setHomeItems(
            if (index >= 0) items.toMutableList().also { it[index] = placed } else items + placed
        )
        if (slot.page + 1 > prefs.getHomePageCount()) prefs.setHomePageCount(slot.page + 1)

        // The app has landed, so the drawer has played its part and can close. Any reordering the
        // drag made on its way across the list is undone: the app left the drawer.
        restoreDrawerOrder()
        pendingDrawerApp = null
        closeDrawer()
        refreshHomeAndDock()
    }

    // ---------------------------------------------------------------- drawer rendering

    /** Rebuilds the drawer for the current layout mode, search query, tab and settings. */
    private fun renderDrawer() {
        if (!::orderAdapter.isInitialized) return

        val query = binding.drawerSearch.text?.toString().orEmpty().trim()
        val searching = query.isNotEmpty()
        val mode = prefs.getDrawerLayout()

        val hidden = prefs.getHiddenApps()
        val visible = allApps.filter { it.packageName !in hidden }
        val categories = prefs.getDrawerCategories()
        renderDrawerTabs(categories)

        // A search that is not scoped to the tab covers every app, so the tab can be ignored.
        val showEverything = searching && !prefs.isDrawerTabSearchScoped()
        val pool = if (showEverything) visible else appsForSelectedTab(visible, hidden, categories)
        val apps = pool
            .sortedBy { it.label.lowercase() }
            .let { sorted ->
                if (searching) sorted.filter { it.label.contains(query, ignoreCase = true) } else sorted
            }

        detachDrawerExtras()

        if (apps.isEmpty()) {
            binding.drawerGrid.visibility = View.GONE
            binding.drawerPager.visibility = View.GONE
            binding.drawerEmpty.visibility = View.VISIBLE
            return
        }
        binding.drawerEmpty.visibility = View.GONE

        val labels = prefs.getShowLabels()
        val iconPx = dp(prefs.getIconSizeDp())
        val columns = prefs.getDrawerColumns()
        val spacing = dp(prefs.getDrawerRowSpacing())

        // Search always falls back to the plain grid so results read as one flat list.
        if (searching) {
            showGrid(apps, columns, labels, iconPx, spacing)
            return
        }

        when (mode) {
            DrawerLayoutMode.GRID -> showGrid(apps, columns, labels, iconPx, spacing)
            DrawerLayoutMode.LIST -> showList(apps, labels, iconPx, spacing)
            DrawerLayoutMode.PAGES -> showPages(apps, columns, labels, iconPx, spacing)
            DrawerLayoutMode.AZ -> showSections(apps, columns, labels, iconPx, spacing)
            DrawerLayoutMode.CUSTOM -> showCustom(apps, columns, labels, iconPx, spacing)
        }
    }

    /** The apps the drawer's current tab stands for. */
    private fun appsForSelectedTab(
        visible: List<AppInfo>,
        hidden: Set<String>,
        categories: List<DrawerCategory>
    ): List<AppInfo> = when (val tab = selectedTabId) {
        null -> visible
        HIDDEN_TAB -> allApps.filter { it.packageName in hidden }
        else -> {
            val category = categories.firstOrNull { it.id == tab }
            if (category == null) {
                // The tab was deleted under us: fall back to All.
                selectedTabId = null
                visible
            } else {
                visible.filter { it.packageName in category.packages }
            }
        }
    }

    /**
     * Draws the category tabs, moves them to where the settings put them and keeps the selection
     * pointing at a tab that still exists. Tabs only appear once there is something to switch
     * between, so a drawer with no categories looks exactly as it always did.
     */
    private fun renderDrawerTabs(categories: List<DrawerCategory>) {
        val tabs = binding.drawerTabs
        val wanted = prefs.areDrawerTabsEnabled() && (categories.isNotEmpty() || prefs.showHiddenDrawerTab())
        if (!wanted) {
            tabs.removeAllTabs()
            tabs.visibility = View.GONE
            return
        }
        tabs.visibility = View.VISIBLE
        tabs.setBackgroundColor(drawerScrimColor())
        tabs.tabGravity = TabLayout.GRAVITY_START
        (tabs.parent as? LinearLayout)?.let { column ->
            val index = if (prefs.getDrawerTabPosition() == DrawerTabPosition.ABOVE_SEARCH) 0 else 1
            if (column.indexOfChild(tabs) != index) {
                column.removeView(tabs)
                column.addView(tabs, index.coerceAtMost(column.childCount))
            }
        }

        tabs.tabMode = TabLayout.MODE_FIXED

        updatingTabs = true
        tabs.removeAllTabs()
        if (prefs.showAllDrawerTab()) addDrawerTab(tabs, null, getString(R.string.drawer_tab_all))
        for (category in categories) addDrawerTab(tabs, category.id, category.name)
        if (prefs.showHiddenDrawerTab()) {
            addDrawerTab(tabs, HIDDEN_TAB, getString(R.string.drawer_tab_hidden))
        }

        val index = (0 until tabs.tabCount).firstOrNull { tabs.getTabAt(it)?.tag == selectedTabId } ?: 0
        val tab = tabs.getTabAt(index)
        if (tab != null) tabs.selectTab(tab, false)
        selectedTabId = tab?.tag as? String
        updatingTabs = false
        styleDrawerTabs()
    }

    private fun addDrawerTab(tabs: TabLayout, id: String?, label: String) {
        val view = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        tabs.addTab(tabs.newTab().setTag(id).setCustomView(view))
    }

    /** Repaints the tabs for which one is selected. */
    private fun styleDrawerTabs() {
        val tabs = binding.drawerTabs
        val selectedPosition = tabs.selectedTabPosition
        for (index in 0 until tabs.tabCount) {
            val view = tabs.getTabAt(index)?.customView as? TextView ?: continue
            val selected = index == selectedPosition
            applyTabStyle(view, selected)
        }
    }

    /** Filled pills, the selected one tinted; the one look the tabs have. */
    private fun applyTabStyle(view: TextView, selected: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(if (selected) TAB_SELECTED_COLOR else Color.TRANSPARENT)
        }
        view.setTextColor(if (selected) Color.WHITE else TAB_TEXT_COLOR)
    }

    private fun onDrawerTabSelected(tab: TabLayout.Tab?) {
        if (updatingTabs) return
        selectedTabId = tab?.tag as? String
        styleDrawerTabs()
        renderDrawer()
    }

    /** Removes per-mode decorations, drag support and overlay visibility. */
    private fun detachDrawerExtras() {
        spacingDecoration?.let { binding.drawerGrid.removeItemDecoration(it) }
        spacingDecoration = null
        binding.drawerGrid.removeItemDecoration(stickyHeaderDecoration)
        binding.drawerIndexBar.visibility = View.GONE
        binding.drawerPager.visibility = View.GONE
        binding.drawerPageIndicator.visibility = View.GONE
        sectionHeaderPositions = emptyList()
    }

    private fun showGrid(
        apps: List<AppInfo>,
        columns: Int,
        labels: Boolean,
        iconPx: Int,
        spacing: Int
    ) {
        binding.drawerGrid.visibility = View.VISIBLE
        binding.drawerGrid.layoutManager = GridLayoutManager(this, columns)
        gridDrawerAdapter.showLabels = labels
        gridDrawerAdapter.iconSizePx = iconPx
        gridDrawerAdapter.cellWidthPx = 0
        binding.drawerGrid.adapter = gridDrawerAdapter
        addSpacingDecoration(spacing)
        gridDrawerAdapter.submitList(apps)
    }

    private fun showList(
        apps: List<AppInfo>,
        labels: Boolean,
        iconPx: Int,
        spacing: Int
    ) {
        binding.drawerGrid.visibility = View.VISIBLE
        binding.drawerGrid.layoutManager = LinearLayoutManager(this)
        listDrawerAdapter.showLabels = labels
        listDrawerAdapter.iconSizePx = iconPx
        binding.drawerGrid.adapter = listDrawerAdapter
        addSpacingDecoration(spacing)
        listDrawerAdapter.submitList(apps)
    }

    private fun showSections(
        apps: List<AppInfo>,
        columns: Int,
        labels: Boolean,
        iconPx: Int,
        spacing: Int
    ) {
        binding.drawerGrid.visibility = View.VISIBLE
        val layoutManager = GridLayoutManager(this, columns)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (sectionedAdapter.isHeader(position)) columns else 1
        }
        binding.drawerGrid.layoutManager = layoutManager
        sectionedAdapter.showLabels = labels
        sectionedAdapter.iconSizePx = iconPx
        binding.drawerGrid.adapter = sectionedAdapter
        binding.drawerGrid.addItemDecoration(stickyHeaderDecoration)
        addSpacingDecoration(spacing)

        sectionedAdapter.submitList(buildSectionRows(apps))

        binding.drawerIndexBar.setLetters(sectionLetters())
        binding.drawerIndexBar.visibility = View.VISIBLE
    }

    private fun showCustom(
        apps: List<AppInfo>,
        columns: Int,
        labels: Boolean,
        iconPx: Int,
        spacing: Int
    ) {
        binding.drawerGrid.visibility = View.VISIBLE
        binding.drawerGrid.layoutManager = GridLayoutManager(this, columns)
        orderAdapter.showLabels = labels
        orderAdapter.iconSizePx = iconPx
        binding.drawerGrid.adapter = orderAdapter
        addSpacingDecoration(spacing)
        orderAdapter.submit(prefs.applyCustomOrder(apps))
    }

    private fun showPages(
        apps: List<AppInfo>,
        columns: Int,
        labels: Boolean,
        iconPx: Int,
        spacing: Int
    ) {
        binding.drawerGrid.visibility = View.GONE
        binding.drawerPager.visibility = View.VISIBLE

        val columnSpacing = dpExact(DRAWER_COLUMN_GAP_DP)
        if (pagerAdapter == null || pagerColumns != columns ||
            pagerColumnSpacing != columnSpacing || pagerRowSpacing != spacing
        ) {
            pagerAdapter = DrawerPagerAdapter(
                columns,
                columnSpacing,
                spacing,
                ::launchApp,
                ::showAppMenu,
                ::startDrawerDrag
            )
            pagerColumns = columns
            pagerColumnSpacing = columnSpacing
            pagerRowSpacing = spacing
            binding.drawerPager.adapter = pagerAdapter
        }
        val adapter = pagerAdapter ?: return
        adapter.showLabels = labels
        adapter.iconSizePx = iconPx

        val perPage = (drawerRows(iconPx, labels, spacing) * columns).coerceAtLeast(1)
        val pages = apps.chunked(perPage)
        adapter.submitPages(pages)

        buildPageIndicator(pages.size)
        binding.drawerPageIndicator.visibility = if (pages.size > 1) View.VISIBLE else View.GONE
    }

    private fun addSpacingDecoration(rowSpacing: Int) {
        spacingDecoration =
            GridSpacingItemDecoration(dpExact(DRAWER_COLUMN_GAP_DP), rowSpacing).also {
                binding.drawerGrid.addItemDecoration(it)
            }
    }

    /** How many app rows fit on one drawer page, used only by the paged layout. */
    private fun drawerRows(iconPx: Int, labels: Boolean, spacing: Int): Int {
        val height = binding.drawerPager.height.takeIf { it > 0 }
            ?: binding.drawerGrid.height.takeIf { it > 0 }
            ?: (binding.root.height - dp(DRAWER_CHROME_DP))
        val rowHeight = iconPx + dp(16) + spacing + if (labels) dp(28) else 0
        return (height / rowHeight.coerceAtLeast(1)).coerceIn(1, MAX_DRAWER_ROWS)
    }

    private fun buildSectionRows(apps: List<AppInfo>): List<DrawerRow> {
        val rows = ArrayList<DrawerRow>(apps.size + SECTION_COUNT)
        val headerPositions = ArrayList<Int>(SECTION_COUNT)
        var currentLetter: String? = null
        for (app in apps) {
            val letter = sectionLetter(app.label)
            if (letter != currentLetter) {
                currentLetter = letter
                headerPositions.add(rows.size)
                rows.add(DrawerRow.Header(letter))
            }
            rows.add(DrawerRow.App(app))
        }
        sectionHeaderPositions = headerPositions
        return rows
    }

    private fun sectionLetters(): List<String> =
        sectionHeaderPositions.mapNotNull { (sectionedAdapter.rowAt(it) as? DrawerRow.Header)?.letter }

    private fun sectionLetter(label: String): String {
        val first = label.trim().firstOrNull()?.uppercaseChar() ?: return OTHER_SECTION
        return if (first.isLetter()) first.toString() else OTHER_SECTION
    }

    private fun scrollToSection(index: Int) {
        val position = sectionHeaderPositions.getOrNull(index) ?: return
        (binding.drawerGrid.layoutManager as? GridLayoutManager)
            ?.scrollToPositionWithOffset(position, 0)
    }

    private fun buildPageIndicator(count: Int) {
        val group: LinearLayout = binding.drawerPageIndicator
        group.removeAllViews()
        val size = dp(7)
        val margin = dp(4)
        for (index in 0 until count) {
            val dot = ImageView(this)
            val params = LinearLayout.LayoutParams(size, size)
            params.marginStart = margin
            params.marginEnd = margin
            dot.layoutParams = params
            dot.setImageResource(R.drawable.dot_unselected)
            group.addView(dot)
        }
        updatePageIndicator(0)
    }

    private fun updatePageIndicator(current: Int) {
        val group: LinearLayout = binding.drawerPageIndicator
        for (index in 0 until group.childCount) {
            val dot = group.getChildAt(index) as? ImageView ?: continue
            dot.setImageResource(
                if (index == current) R.drawable.dot_selected else R.drawable.dot_unselected
            )
        }
    }

    // ---------------------------------------------------------------- actions

    private fun launchApp(app: AppInfo) {
        if (drawerOpen) closeDrawer()
        AppRepository.launch(this, app)
    }

    private fun createDrawerFolder(app: AppInfo) {
        val input = EditText(this).apply {
            hint = getString(R.string.folder_name_hint)
            setText(getString(R.string.folder_default_name))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_create)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.folder_default_name) }
                val folder = DrawerFolder(prefs.newDrawerFolderId(), name, listOf(app.key))
                prefs.upsertDrawerFolder(folder)
                refreshHomeAndDock()
            }
            .show()
    }

    private fun showAppMenu(app: AppInfo, anchor: View) {
        val onHome = prefs.isAppOnHome(app.packageName)
        val docked = prefs.isDocked(app.packageName)

        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_HOME, 0, if (onHome) R.string.action_remove_home else R.string.action_add_home)
        popup.menu.add(0, MENU_DOCK, 1, if (docked) R.string.action_remove_dock else R.string.action_add_dock)
        // Only apps that actually provide widgets get the entry that opens their picker.
        if (app.packageName in widgetPackages) {
            popup.menu.add(0, MENU_WIDGETS, 2, R.string.action_widgets)
        }
        popup.menu.add(0, MENU_INFO, 3, R.string.action_app_info)
        if (app.packageName != packageName) {
            popup.menu.add(0, MENU_HIDE, 4, R.string.action_hide_app)
        }
        if (!app.isSystemApp && app.packageName != packageName) {
            popup.menu.add(0, MENU_UNINSTALL, 5, R.string.action_uninstall)
        }

        // Folders can be made straight out of any app: tapping an app for longer-than-a-tap gives
        // it its own folder here, without having to drag anything around.
        if (prefs.areFoldersEnabled() && drawerOpen) {
            popup.menu.add(0, MENU_FOLDER_CREATE, 6, R.string.folder_create)
            val existingDrawerFolders = prefs.getDrawerFolders().filter { it.canAdd(app.key) }
            if (existingDrawerFolders.isNotEmpty()) {
                popup.menu.add(0, MENU_FOLDER_ADD, 7, R.string.folder_add_to_folder)
            }
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HOME -> {
                    if (onHome) {
                        prefs.removeAppFromHome(app.packageName)
                    } else {
                        prefs.addAppToHome(app.packageName, app.activityName)
                    }
                    refreshHomeAndDock()
                }
                MENU_DOCK -> {
                    if (docked) prefs.unpinDockApp(app.packageName) else prefs.pinDockApp(app.packageName)
                    refreshHomeAndDock()
                    updateDockCellWidth(binding.dockAppsArea.width)
                }
                MENU_WIDGETS -> openWidgetSheet(app.packageName)
                MENU_HIDE -> hideApp(app)
                MENU_INFO -> AppRepository.openAppInfo(this, app)
                MENU_FOLDER_ADD -> {
                    val folders = prefs.getDrawerFolders().filter { it.canAdd(app.key) }
                    if (folders.isEmpty()) return@setOnMenuItemClickListener true
                    val names = folders.map { it.name }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.folder_add_to_folder)
                        .setItems(names) { _, which ->
                            prefs.addAppToDrawerFolder(folders[which].id, app.key)
                            refreshHomeAndDock()
                        }
                        .show()
                    true
                }
                MENU_FOLDER_CREATE -> {
                    createDrawerFolder(app)
                    true
                }

                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    /**
     * Hides [app] from the launcher entirely: it is removed from the home screen and the dock
     * and then excluded from the drawer. Unhiding is done from Settings → Hidden apps.
     */
    private fun hideApp(app: AppInfo) {
        prefs.removeAppFromHome(app.packageName)
        prefs.unpinDockApp(app.packageName)
        prefs.hideApp(app.packageName)
        refreshHomeAndDock()
        renderDrawer()
    }

    // ---------------------------------------------------------------- drawer

    private fun openDrawer() {
        if (drawerOpen) return
        // Edit mode is kept: the drawer is a way to fetch apps and widgets for the home, so
        // leaving it should not take the home out of the mode it was being arranged in.
        drawerOpen = true
        drawerPeeking = false

        // Hide the home screen so the wallpaper shows behind the drawer instead.
        binding.homeContainer.visibility = View.INVISIBLE
        updateBlur()

        val drawer = binding.drawerContainer
        drawer.visibility = View.VISIBLE
        // Keep focus off the search field so the IME does not pop up and swallow
        // the first back press; the user taps the field to start typing.
        binding.drawerSearch.clearFocus()
        drawer.requestFocus()
        drawer.post {
            // Now that the drawer has been measured, paged layouts can size their pages.
            renderDrawer()
            drawer.translationY = drawer.height.toFloat()
            drawer.animate()
                .translationY(0f)
                .setDuration(DRAWER_ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawerPeeking = false
        hideKeyboard()

        val drawer = binding.drawerContainer
        drawer.animate().cancel()
        drawer.animate()
            .translationY(drawer.height.toFloat())
            .setDuration(DRAWER_ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                drawer.visibility = View.GONE
                drawer.translationY = 0f
                binding.drawerSearch.text?.clear()
                binding.homeContainer.visibility = View.VISIBLE
                updateBlur()
            }
            .start()
    }

    /** A swipe down dismisses the drawer unless there are more apps to scroll back to. */
    private fun canDismissWithSwipe(): Boolean =
        binding.drawerGrid.visibility != View.VISIBLE || !binding.drawerGrid.canScrollVertically(-1)

    private fun hideKeyboard() {
        hideKeyboardFrom(binding.drawerSearch)
    }

    private fun hideKeyboardFrom(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Like [dp], but rounds to the nearest pixel. Used where the size has to line up with an
     * XML dimension, which the framework rounds the same way.
     */
    private fun dpExact(value: Int): Int =
        Math.round(value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DRAWER_ANIM_MS = 220L

        /** How long the drawer takes to step aside for a drag, and to come back. */
        const val PEEK_ANIM_MS = 180L

        /**
         * How far a finger must clear the box it lifted before that counts as a drag rather than a
         * long press with a shaky hand.
         */
        const val DRAG_PULL_MARGIN_DP = 8

        /** Payload label for a drag that started in the drawer. */
        const val DRAWER_DRAG_LABEL = "drawer-app"

        /** Payload label for a widget dragged out of the picker. */
        const val WIDGET_DRAG_LABEL = "widget"

        /** How long the widget sheet takes to slide in, out, and aside. */
        const val SHEET_ANIM_MS = 220L

        /** Cards per row in the widget sheet. */
        const val WIDGET_SHEET_COLUMNS = 2

        /** Tallest the sheet's list ever gets; it shrinks on short screens. */
        const val WIDGET_LIST_MAX_DP = 380

        /** A page count no one is going to browse past by hand. */
        const val MAX_HOME_PAGES = 12

        /** Space between two page chips in the edit-mode page strip. */
        const val PAGE_CHIP_GAP_DP = 8

        const val DOCK_MAX_ICON_DP = 48

        /** Matches the drawer container's 16dp side padding so every column gap is equal. */
        const val DRAWER_COLUMN_GAP_DP = 16

        const val DRAWER_CHROME_DP = 150
        const val MAX_DRAWER_ROWS = 8
        const val SECTION_COUNT = 27

        const val OTHER_SECTION = "#"

        const val MENU_HOME = 1
        const val MENU_DOCK = 2
        const val MENU_WIDGETS = 3
        const val MENU_INFO = 4
        const val MENU_UNINSTALL = 5
        const val MENU_HIDE = 6
        const val MENU_FOLDER_OPEN = 7
        const val MENU_FOLDER_RENAME = 8
        const val MENU_FOLDER_DELETE = 9
        const val MENU_FOLDER_REMOVE = 10
        const val MENU_FOLDER_CREATE = 11
        const val MENU_FOLDER_ADD = 12

        /** The translucency of a folder's own backdrop, over the wallpaper. */
        const val FOLDER_BACKDROP_COLOR = 0x70FFFFFF

        /** The tab id standing for the hidden-apps tab; `null` is the "All" tab. */
        const val HIDDEN_TAB = "hidden"

        /** Tab colours: the selected pill and the text of an unselected tab. */
        val TAB_SELECTED_COLOR = 0x33FFFFFF
        val TAB_TEXT_COLOR = Color.argb(0xB3, 0xFF, 0xFF, 0xFF)

        /** The dim laid over the blurred wallpaper behind a sheet. */
        val SHEET_SCRIM_COLOR = Color.argb(0x99, 0x00, 0x00, 0x00)

        /** How long the swipe-down tap of feedback runs. */
        const val GESTURE_HAPTIC_MS = 20L
    }


}