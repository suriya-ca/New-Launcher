package com.sdev.newlauncher

import android.content.Intent
import android.os.Bundle
import com.sdev.newlauncher.data.DrawerLayoutMode
import com.sdev.newlauncher.data.SwipeDownAction
import com.sdev.newlauncher.databinding.ActivitySettingsBinding

/**
 * The launcher's settings hub. Also exposed as a launchable app in the drawer
 * (see its LAUNCHER intent-filter in AndroidManifest.xml).
 *
 * Every row opens a dedicated page; the summaries are refreshed in [onResume] so they
 * always reflect the latest stored values.
 */
class SettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.rowHome.setOnClickListener { open(HomeSettingsActivity::class.java) }
        binding.rowDrawer.setOnClickListener { open(DrawerSettingsActivity::class.java) }
        binding.rowIcons.setOnClickListener { open(IconsSettingsActivity::class.java) }
        binding.rowFolders.setOnClickListener { open(FoldersSettingsActivity::class.java) }
        binding.rowGestures.setOnClickListener { open(GesturesSettingsActivity::class.java) }
        binding.rowBlur.setOnClickListener { open(BlurSettingsActivity::class.java) }
        binding.rowDock.setOnClickListener { open(DockSettingsActivity::class.java) }
        binding.rowHidden.setOnClickListener { open(HiddenAppsActivity::class.java) }
        binding.rowSystem.setOnClickListener { open(SystemSettingsActivity::class.java) }
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    /** Pushes the current stored values into the row summaries. */
    private fun refreshSummaries() {
        binding.homeSummary.text =
            getString(R.string.settings_grid_value, prefs.getHomeColumns(), prefs.getHomeRows())

        binding.drawerSummary.text = getString(
            R.string.settings_drawer_hub_summary,
            getString(labelForMode(prefs.getDrawerLayout())),
            prefs.getDrawerColumns()
        )

        binding.iconsSummary.text =
            getString(R.string.settings_dp_value, prefs.getIconSizeDp())

        binding.foldersSummary.text = if (!prefs.areFoldersEnabled()) {
            getString(R.string.settings_disabled_value)
        } else {
            getString(R.string.settings_folders_count_value, prefs.getFolders().size + prefs.getDrawerFolderCount())
        }

        binding.gesturesSummary.text = if (!prefs.isSwipeDownEnabled()) {
            getString(R.string.settings_disabled_value)
        } else {
            getString(
                R.string.gestures_hub_summary,
                getString(
                    if (prefs.getSwipeDownAction() == SwipeDownAction.QUICK_SETTINGS) {
                        R.string.settings_swipe_quick_settings
                    } else {
                        R.string.settings_swipe_notifications
                    }
                )
            )
        }

        binding.blurSummary.text = getString(
            R.string.settings_blur_hub_summary,
            getString(
                if (prefs.isBlurEnabled()) R.string.settings_enabled_value
                else R.string.settings_disabled_value
            )
        )

        binding.dockSummary.text =
            getString(R.string.settings_icons_value, prefs.getDockCapacity())

        binding.hiddenSummary.text =
            getString(R.string.settings_hidden_apps_summary, prefs.getHiddenApps().size)

        binding.systemSummary.text =
            getString(R.string.settings_version_value, appVersionName())
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }

    private fun labelForMode(mode: DrawerLayoutMode): Int = when (mode) {
        DrawerLayoutMode.GRID -> R.string.settings_layout_grid
        DrawerLayoutMode.PAGES -> R.string.settings_layout_pages
        DrawerLayoutMode.LIST -> R.string.settings_layout_list
        DrawerLayoutMode.AZ -> R.string.settings_layout_az
        DrawerLayoutMode.CUSTOM -> R.string.settings_layout_custom
    }
}
