package com.sdev.newlauncher

import android.os.Bundle
import com.sdev.newlauncher.data.DrawerLayoutMode
import com.sdev.newlauncher.databinding.ActivitySettingsDrawerBinding

/**
 * "App drawer" page: layout mode, grid columns, row spacing and background opacity.
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

        refreshFromPrefs()
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
}
