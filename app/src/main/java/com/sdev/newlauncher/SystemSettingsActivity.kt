package com.sdev.newlauncher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sdev.newlauncher.databinding.ActivitySettingsSystemBinding

/**
 * "System" page: default-launcher shortcut, version info and the reset action.
 */
class SystemSettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsSystemBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsSystemBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.defaultLauncherRow.setOnClickListener {
            openDefaultLauncherSettings()
        }

        binding.versionValue.text = getString(R.string.settings_version_value, appVersionName())

        binding.resetRow.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_reset)
                .setMessage(R.string.settings_reset_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_reset) { _, _ ->
                    prefs.resetAll()
                }
                .show()
        }
    }

    private fun openDefaultLauncherSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                // Nothing else we can do.
            }
        }
    }
}
