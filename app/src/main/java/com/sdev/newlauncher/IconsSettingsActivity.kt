package com.sdev.newlauncher

import android.os.Bundle
import com.sdev.newlauncher.databinding.ActivitySettingsIconsBinding

/**
 * "Icons & labels" page: icon size and whether app labels are drawn.
 */
class IconsSettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsIconsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsIconsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.iconSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setIconSizeDp(value.toInt())
            binding.iconSizeValue.text =
                getString(R.string.settings_dp_value, value.toInt())
        }

        binding.showLabelsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setShowLabels(checked)
        }

        binding.iconSizeSlider.value = prefs.getIconSizeDp().toFloat()
        binding.iconSizeValue.text = getString(R.string.settings_dp_value, prefs.getIconSizeDp())
        binding.showLabelsSwitch.isChecked = prefs.getShowLabels()
    }
}
