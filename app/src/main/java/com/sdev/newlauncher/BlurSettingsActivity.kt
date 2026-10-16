package com.sdev.newlauncher

import android.os.Bundle
import com.google.android.material.materialswitch.MaterialSwitch
import com.sdev.newlauncher.data.BlurTarget
import com.sdev.newlauncher.databinding.ActivitySettingsBlurBinding

/**
 * "Blur & effects" page: whether the wallpaper is blurred, behind which surfaces, and how hard.
 *
 * The blur itself is the window's — the platform blurs what is genuinely behind the launcher's
 * window — so there is nothing here to source or size a copy of the wallpaper.
 */
class BlurSettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsBlurBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBlurBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.blurEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setBlurEnabled(checked)
            bindControls()
        }
        bindTarget(binding.blurDrawerSwitch, BlurTarget.DRAWER)
        bindTarget(binding.blurOverviewSwitch, BlurTarget.OVERVIEW)
        bindTarget(binding.blurSheetsSwitch, BlurTarget.SHEETS)

        binding.blurRadiusSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setBlurRadiusDp(value.toInt())
            binding.blurRadiusValue.text = getString(R.string.settings_blur_on_value, value.toInt())
        }

        binding.blurEnabledSwitch.isChecked = prefs.isBlurEnabled()
        binding.blurDrawerSwitch.isChecked = prefs.isBlurTargetEnabled(BlurTarget.DRAWER)
        binding.blurOverviewSwitch.isChecked = prefs.isBlurTargetEnabled(BlurTarget.OVERVIEW)
        binding.blurSheetsSwitch.isChecked = prefs.isBlurTargetEnabled(BlurTarget.SHEETS)

        binding.blurRadiusSlider.value = prefs.getBlurRadiusDp().toFloat()
        binding.blurRadiusValue.text = getString(R.string.settings_blur_on_value, prefs.getBlurRadiusDp())

        bindControls()
    }

    private fun bindTarget(switch: MaterialSwitch, target: BlurTarget) {
        switch.setOnCheckedChangeListener { _, checked -> prefs.setBlurTargetEnabled(target, checked) }
    }

    /** Greys out the strength controls while the blur is off. */
    private fun bindControls() {
        val enabled = binding.blurEnabledSwitch.isChecked
        binding.blurDrawerSwitch.isEnabled = enabled
        binding.blurOverviewSwitch.isEnabled = enabled
        binding.blurSheetsSwitch.isEnabled = enabled
        binding.blurStrengthCard.alpha = if (enabled) 1f else DISABLED_ALPHA
        binding.blurRadiusSlider.isEnabled = enabled
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f
    }
}
