package com.sdev.newlauncher

import android.os.Bundle
import com.sdev.newlauncher.data.SwipeDownAction
import com.sdev.newlauncher.databinding.ActivitySettingsGesturesBinding

/**
 * "Gestures" page: what a swipe down on the empty home screen opens, and how far it applies.
 */
class GesturesSettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsGesturesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsGesturesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.swipeDownSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setSwipeDownEnabled(checked)
            bindControls()
        }
        binding.swipeActionGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.setSwipeDownAction(
                if (checkedId == R.id.swipe_action_quick_settings) SwipeDownAction.QUICK_SETTINGS
                else SwipeDownAction.NOTIFICATIONS
            )
        }
        binding.swipeEmptySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setSwipeDownEmptySpaceOnly(checked)
        }
        binding.hapticsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setHapticsEnabled(checked)
        }

        binding.swipeDownSwitch.isChecked = prefs.isSwipeDownEnabled()
        binding.swipeActionGroup.check(
            if (prefs.getSwipeDownAction() == SwipeDownAction.QUICK_SETTINGS) R.id.swipe_action_quick_settings
            else R.id.swipe_action_notifications
        )
        binding.swipeEmptySwitch.isChecked = prefs.isSwipeDownEmptySpaceOnly()
        binding.hapticsSwitch.isChecked = prefs.isHapticsEnabled()
        bindControls()
    }

    /** Greys out the details of the gesture while the gesture itself is off. */
    private fun bindControls() {
        val enabled = binding.swipeDownSwitch.isChecked
        binding.swipeEmptySwitch.isEnabled = enabled
        binding.swipeActionGroup.isEnabled = enabled
        for (index in 0 until binding.swipeActionGroup.childCount) {
            binding.swipeActionGroup.getChildAt(index).isEnabled = enabled
        }
    }
}
