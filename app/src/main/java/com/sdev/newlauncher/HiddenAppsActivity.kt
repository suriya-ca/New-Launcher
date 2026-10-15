package com.sdev.newlauncher

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sdev.newlauncher.data.AppInfo
import com.sdev.newlauncher.data.AppRepository
import com.sdev.newlauncher.data.LauncherPrefs
import com.sdev.newlauncher.databinding.ActivityHiddenAppsBinding
import com.sdev.newlauncher.ui.HiddenAppAdapter
import kotlinx.coroutines.launch

/**
 * Lets the user pick which installed apps are hidden from the app drawer.
 */
class HiddenAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHiddenAppsBinding
    private lateinit var prefs: LauncherPrefs
    private lateinit var adapter: HiddenAppAdapter

    private var allApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHiddenAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = LauncherPrefs(this)

        applyEdgeToEdge()

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = HiddenAppAdapter { app ->
            prefs.toggleHidden(app.packageName)
            adapter.hidden = prefs.getHiddenApps()
        }
        binding.hiddenList.layoutManager = LinearLayoutManager(this)
        binding.hiddenList.adapter = adapter

        binding.hiddenSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
        })

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        adapter.hidden = prefs.getHiddenApps()
    }

    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.hiddenRoot) { view, insets ->
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

    private fun loadApps() {
        lifecycleScope.launch {
            // Our own settings entry is never hideable.
            allApps = AppRepository.loadApps(this@HiddenAppsActivity)
                .filter { it.packageName != packageName }
                .sortedBy { it.label.lowercase() }
            applyFilter(binding.hiddenSearch.text?.toString().orEmpty())
        }
    }

    private fun applyFilter(query: String) {
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.label.contains(trimmed, ignoreCase = true) }
        }
        adapter.submitList(filtered)

        val empty = filtered.isEmpty()
        binding.hiddenList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.hiddenEmpty.visibility = if (empty) View.VISIBLE else View.GONE
    }
}
