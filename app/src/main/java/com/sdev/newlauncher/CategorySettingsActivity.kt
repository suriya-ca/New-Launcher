package com.sdev.newlauncher

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.sdev.newlauncher.data.AppInfo
import com.sdev.newlauncher.data.AppRepository
import com.sdev.newlauncher.data.DrawerCategory
import com.sdev.newlauncher.databinding.ActivitySettingsCategoriesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Creates and edits the drawer's category tabs: a name plus the apps filed under it. The list of
 * categories is short and changes rarely, so its rows are built by hand rather than by an adapter.
 */
class CategorySettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsCategoriesBinding
    private var apps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.categoryNewRow.setOnClickListener { editCategory(null) }

        renderCategories()
        lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) { AppRepository.loadApps(this@CategorySettingsActivity) }
                .sortedBy { it.label.lowercase() }
        }
    }

    override fun onResume() {
        super.onResume()
        renderCategories()
    }

    /** Rebuilds the list of categories. */
    private fun renderCategories() {
        val list = binding.categoryList
        list.removeAllViews()
        val categories = prefs.getDrawerCategories()
        binding.categoryEmpty.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE

        for (category in categories) {
            val row = layoutInflater.inflate(R.layout.item_category, list, false)
            row.findViewById<TextView>(R.id.category_name).text = category.name
            row.findViewById<TextView>(R.id.category_count).text =
                getString(R.string.category_apps_value, category.packages.size)
            row.setOnClickListener { editCategory(category) }
            row.findViewById<View>(R.id.category_delete).setOnClickListener {
                confirmDelete(category)
            }
            list.addView(row)
        }
    }

    /** Opens the name-and-apps dialog for [category], or for a new one when it is null. */
    private fun editCategory(category: DrawerCategory?) {
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = dp(20)

        val nameField = EditText(this).apply {
            setText(category?.name.orEmpty())
            hint = getString(R.string.category_name_hint)
            setSelection(text.length)
        }
        dialogView.addView(
            nameField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(pad, dp(4), pad, dp(8)) }
        )

        val hint = TextView(this).apply {
            setText(R.string.category_apps_hint)
            textSize = 13f
            setPadding(pad, 0, pad, 0)
        }
        dialogView.addView(hint)

        // The tick-list can be long, so it gets its own scroller with a bounded height, and every
        // checkbox is remembered as it is built: reading them back out of the view tree would be
        // guesswork once the rows have been recycled through a layout pass.
        val appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(appList) }
        dialogView.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(APP_LIST_MAX_DP))
        )

        val boxes = ArrayList<Pair<String, MaterialCheckBox>>(apps.size)
        for (app in apps) {
            val row = layoutInflater.inflate(R.layout.item_hidden_app, appList, false)
            row.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.app_label).text = app.label
            val check = row.findViewById<MaterialCheckBox>(R.id.app_check)
            check.isChecked = category?.contains(app.packageName) == true
            row.setOnClickListener { check.isChecked = !check.isChecked }
            appList.addView(row)
            boxes.add(app.packageName to check)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (category == null) R.string.category_new else R.string.category_rename)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()

        // An empty name is not worth blocking on: it falls back to the page's own title.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameField.text.toString().trim()
                .ifEmpty { getString(R.string.category_title) }
            val packages = boxes.filter { it.second.isChecked }.map { it.first }
            saveCategory(category, name, packages)
            dialog.dismiss()
        }
    }

    private fun saveCategory(category: DrawerCategory?, name: String, packages: List<String>) {
        val id = category?.id ?: prefs.newDrawerCategoryId()
        prefs.upsertDrawerCategory(DrawerCategory(id, name, packages))
        renderCategories()
    }

    private fun confirmDelete(category: DrawerCategory) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.category_delete_confirm, category.name))
            .setMessage(R.string.category_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                prefs.deleteDrawerCategory(category.id)
                renderCategories()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Tallest the app tick-list grows inside the edit dialog. */
        const val APP_LIST_MAX_DP = 360
    }
}
