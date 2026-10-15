package com.sdev.newlauncher

import android.os.Bundle
import com.sdev.newlauncher.databinding.ActivitySettingsDockBinding

/**
 * "Dock" page: visibility, the app-drawer button and the dock capacity.
 */
class DockSettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsDockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsDockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.showDockSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setShowDock(checked)
        }

        binding.showAppsButtonSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setShowAppsButton(checked)
        }

        binding.dockCapacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setDockCapacity(value.toInt())
            binding.dockCapacityValue.text =
                getString(R.string.settings_icons_value, value.toInt())
        }

        binding.showDockSwitch.isChecked = prefs.getShowDock()
        binding.showAppsButtonSwitch.isChecked = prefs.getShowAppsButton()
        binding.dockCapacitySlider.value = prefs.getDockCapacity().toFloat()
        binding.dockCapacityValue.text =
            getString(R.string.settings_icons_value, prefs.getDockCapacity())
    }
}
