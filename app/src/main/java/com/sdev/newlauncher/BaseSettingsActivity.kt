package com.sdev.newlauncher

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.sdev.newlauncher.data.LauncherPrefs

/**
 * Shared plumbing for every settings screen: preference access, edge-to-edge layout and
 * the back-arrow toolbar.
 *
 * The launcher's settings are split into a hub ([SettingsActivity]) and one page per
 * topic; each page extends this class so the boilerplate lives in a single place.
 */
abstract class BaseSettingsActivity : AppCompatActivity() {

    protected val prefs: LauncherPrefs by lazy { LauncherPrefs(this) }

    /** Draws [root] behind the system bars by padding it with the bar/cutout insets. */
    protected fun applyEdgeToEdge(root: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }
    }

    /** Makes [toolbar]'s navigation icon close the screen. The title comes from the layout. */
    protected fun bindToolbar(toolbar: MaterialToolbar) {
        toolbar.setNavigationOnClickListener { finish() }
    }

    /** This app's `versionName`, or a placeholder if it cannot be read. */
    protected fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: DEFAULT_VERSION
    } catch (_: Exception) {
        DEFAULT_VERSION
    }

    private companion object {
        const val DEFAULT_VERSION = "1.0"
    }
}
