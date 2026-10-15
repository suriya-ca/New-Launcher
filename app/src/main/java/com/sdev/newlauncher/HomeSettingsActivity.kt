package com.sdev.newlauncher

import android.os.Bundle
import com.sdev.newlauncher.databinding.ActivitySettingsHomeBinding

/**
 * "Home screen" page: how many apps the home grid shows per row and per column.
 */
class HomeSettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.homeColumnsSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setHomeColumns(value.toInt())
            binding.homeColumnsValue.text =
                getString(R.string.settings_columns_value, value.toInt())
        }

        binding.homeRowsSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setHomeRows(value.toInt())
            binding.homeRowsValue.text = getString(R.string.settings_rows_value, value.toInt())
        }

        binding.homeColumnsSlider.value = prefs.getHomeColumns().toFloat()
        binding.homeColumnsValue.text =
            getString(R.string.settings_columns_value, prefs.getHomeColumns())

        binding.homeRowsSlider.value = prefs.getHomeRows().toFloat()
        binding.homeRowsValue.text = getString(R.string.settings_rows_value, prefs.getHomeRows())
    }
}
