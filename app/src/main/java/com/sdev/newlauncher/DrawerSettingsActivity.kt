package com.sdev.newlauncher

import android.content.Intent
import android.os.Bundle
import com.sdev.newlauncher.data.DrawerLayoutMode
import com.sdev.newlauncher.data.DrawerTabPosition
import com.sdev.newlauncher.databinding.ActivitySettingsDrawerBinding

/**
 * "App drawer" page: layout mode, grid columns, row spacing, background opacity and the
 * category tabs across the top of the drawer.
 */
class DrawerSettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsDrawerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsDrawerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.drawerLayoutGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeForRadio(checkedId)
            prefs.setDrawerLayout(mode)
            updateDrawerLayoutSummary(mode)
        }

        binding.drawerColumnsSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setDrawerColumns(value.toInt())
            binding.drawerColumnsValue.text =
                getString(R.string.settings_columns_value, value.toInt())
        }

        binding.drawerRowSpacingSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setDrawerRowSpacing(value.toInt())
            binding.drawerRowSpacingValue.text =
                getString(R.string.settings_dp_value, value.toInt())
        }

        binding.drawerOpacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setDrawerOpacity(value)
            binding.drawerOpacityValue.text =
                getString(R.string.settings_percent_value, (value * 100).toInt())
        }

        binding.drawerTabsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setDrawerTabsEnabled(checked)
            bindTabControls()
        }
        binding.drawerTabAllSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setShowAllDrawerTab(checked)
        }
        binding.drawerTabHiddenSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setShowHiddenDrawerTab(checked)
        }
        binding.drawerTabSearchSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setDrawerTabSearchScoped(checked)
        }
        binding.drawerTabPositionGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.setDrawerTabPosition(
                if (checkedId == R.id.tab_position_above) DrawerTabPosition.ABOVE_SEARCH
                else DrawerTabPosition.BELOW_SEARCH
            )
        }
        binding.manageCategoriesRow.setOnClickListener {
            startActivity(Intent(this, CategorySettingsActivity::class.java))
        }

        refreshFromPrefs()
    }

    override fun onResume() {
        super.onResume()
        bindTabSummary()
    }

    /** Pushes the current stored values into the controls. */
    private fun refreshFromPrefs() {
        val mode = prefs.getDrawerLayout()
        binding.drawerLayoutGroup.check(radioForMode(mode))
        updateDrawerLayoutSummary(mode)

        binding.drawerColumnsSlider.value = prefs.getDrawerColumns().toFloat()
        binding.drawerColumnsValue.text =
            getString(R.string.settings_columns_value, prefs.getDrawerColumns())

        binding.drawerRowSpacingSlider.value = prefs.getDrawerRowSpacing().toFloat()
        binding.drawerRowSpacingValue.text =
            getString(R.string.settings_dp_value, prefs.getDrawerRowSpacing())

        val opacity = prefs.getDrawerOpacity()
        binding.drawerOpacitySlider.value = opacity
        binding.drawerOpacityValue.text =
            getString(R.string.settings_percent_value, (opacity * 100).toInt())

        binding.drawerTabsSwitch.isChecked = prefs.areDrawerTabsEnabled()
        binding.drawerTabAllSwitch.isChecked = prefs.showAllDrawerTab()
        binding.drawerTabHiddenSwitch.isChecked = prefs.showHiddenDrawerTab()
        binding.drawerTabSearchSwitch.isChecked = prefs.isDrawerTabSearchScoped()
        binding.drawerTabPositionGroup.check(
            if (prefs.getDrawerTabPosition() == DrawerTabPosition.ABOVE_SEARCH) R.id.tab_position_above
            else R.id.tab_position_below
        )
        bindTabControls()
        bindTabSummary()
    }

    /** Enables the tab controls only while the tabs themselves are on. */
    private fun bindTabControls() {
        val enabled = binding.drawerTabsSwitch.isChecked
        binding.drawerTabAllSwitch.isEnabled = enabled
        binding.drawerTabHiddenSwitch.isEnabled = enabled
        binding.drawerTabSearchSwitch.isEnabled = enabled
        binding.drawerTabPositionGroup.isEnabled = enabled
        for (index in 0 until binding.drawerTabPositionGroup.childCount) {
            binding.drawerTabPositionGroup.getChildAt(index).isEnabled = enabled
        }
        binding.manageCategoriesRow.isEnabled = enabled
        binding.manageCategoriesRow.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun bindTabSummary() {
        val count = prefs.getDrawerCategories().size
        binding.manageCategoriesSummary.text = if (count == 0) {
            getString(R.string.settings_manage_categories_summary)
        } else {
            getString(R.string.settings_categories_count_value, count)
        }
    }

    private fun updateDrawerLayoutSummary(mode: DrawerLayoutMode) {
        binding.drawerLayoutSummary.text = getString(summaryForMode(mode))
    }

    private fun radioForMode(mode: DrawerLayoutMode): Int = when (mode) {
        DrawerLayoutMode.GRID -> R.id.mode_grid
        DrawerLayoutMode.PAGES -> R.id.mode_pages
        DrawerLayoutMode.LIST -> R.id.mode_list
        DrawerLayoutMode.AZ -> R.id.mode_az
        DrawerLayoutMode.CUSTOM -> R.id.mode_custom
    }

    private fun modeForRadio(checkedId: Int): DrawerLayoutMode = when (checkedId) {
        R.id.mode_pages -> DrawerLayoutMode.PAGES
        R.id.mode_list -> DrawerLayoutMode.LIST
        R.id.mode_az -> DrawerLayoutMode.AZ
        R.id.mode_custom -> DrawerLayoutMode.CUSTOM
        else -> DrawerLayoutMode.GRID
    }

    private fun summaryForMode(mode: DrawerLayoutMode): Int = when (mode) {
        DrawerLayoutMode.GRID -> R.string.settings_layout_grid_summary
        DrawerLayoutMode.PAGES -> R.string.settings_layout_pages_summary
        DrawerLayoutMode.LIST -> R.string.settings_layout_list_summary
        DrawerLayoutMode.AZ -> R.string.settings_layout_az_summary
        DrawerLayoutMode.CUSTOM -> R.string.settings_custom_order_summary
    }

    private companion object {
        /** How far a row fades when the setting it belongs to is switched off. */
        const val DISABLED_ALPHA = 0.4f
    }
}
