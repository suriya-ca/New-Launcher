package com.sdev.newlauncher

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.sdev.newlauncher.data.IconPackRepository
import com.sdev.newlauncher.data.IconScope
import com.sdev.newlauncher.data.IconShape
import com.sdev.newlauncher.databinding.ActivitySettingsIconsBinding
import com.sdev.newlauncher.ui.IconRenderer
import com.sdev.newlauncher.ui.MaskedIconDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Icons & labels" page: icon size, whether app labels are drawn, the shape icons are masked
 * inside and which icon pack overrides them — each of the last two switchable per surface.
 */
class IconsSettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsIconsBinding
    private var packs: List<IconPackRepository.PackInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsIconsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.iconSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setIconSizeDp(value.toInt())
            binding.iconSizeValue.text = getString(R.string.settings_dp_value, value.toInt())
        }

        binding.showLabelsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setShowLabels(checked)
        }

        buildShapeChoices()

        binding.shapeScaleSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setIconShapeScalePercent(value.toInt())
            binding.shapeScaleValue.text = getString(R.string.settings_percent_value, value.toInt())
            renderPreview()
        }

        binding.shapeCornerSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setIconShapeCornerPercent(value.toInt())
            binding.shapeCornerValue.text = getString(R.string.settings_percent_value, value.toInt())
            renderPreview()
        }

        bindScopeSwitch(binding.shapeHomeSwitch, IconScope.HOME, prefs::isShapeAppliedTo, prefs::setShapeAppliedTo)
        bindScopeSwitch(binding.shapeDockSwitch, IconScope.DOCK, prefs::isShapeAppliedTo, prefs::setShapeAppliedTo)
        bindScopeSwitch(binding.shapeDrawerSwitch, IconScope.DRAWER, prefs::isShapeAppliedTo, prefs::setShapeAppliedTo)
        bindScopeSwitch(binding.shapeFolderSwitch, IconScope.FOLDER, prefs::isShapeAppliedTo, prefs::setShapeAppliedTo)

        bindScopeSwitch(binding.packHomeSwitch, IconScope.HOME, prefs::isPackAppliedTo, prefs::setPackAppliedTo)
        bindScopeSwitch(binding.packDockSwitch, IconScope.DOCK, prefs::isPackAppliedTo, prefs::setPackAppliedTo)
        bindScopeSwitch(binding.packDrawerSwitch, IconScope.DRAWER, prefs::isPackAppliedTo, prefs::setPackAppliedTo)
        bindScopeSwitch(binding.packFolderSwitch, IconScope.FOLDER, prefs::isPackAppliedTo, prefs::setPackAppliedTo)

        binding.iconPackRow.setOnClickListener { choosePack() }
        binding.iconReloadRow.setOnClickListener {
            IconPackRepository.clearCache()
            IconRenderer.clearCache()
            loadPacks()
        }

        binding.iconSizeSlider.value = prefs.getIconSizeDp().toFloat()
        binding.iconSizeValue.text = getString(R.string.settings_dp_value, prefs.getIconSizeDp())
        binding.showLabelsSwitch.isChecked = prefs.getShowLabels()

        binding.shapeScaleSlider.value = prefs.getIconShapeScalePercent().toFloat()
        binding.shapeScaleValue.text = getString(R.string.settings_percent_value, prefs.getIconShapeScalePercent())
        binding.shapeCornerSlider.value = prefs.getIconShapeCornerPercent().toFloat()
        binding.shapeCornerValue.text = getString(R.string.settings_percent_value, prefs.getIconShapeCornerPercent())

        renderPreview()
        bindPackSummary()
        loadPacks()
    }

    /** Builds one radio per [IconShape] and keeps the stored choice checked. */
    private fun buildShapeChoices() {
        val labels = mapOf(
            IconShape.NONE to R.string.settings_shape_none,
            IconShape.CIRCLE to R.string.settings_shape_circle,
            IconShape.SQUIRCLE to R.string.settings_shape_squircle,
            IconShape.ROUNDED to R.string.settings_shape_rounded,
            IconShape.TEARDROP to R.string.settings_shape_teardrop,
            IconShape.HEXAGON to R.string.settings_shape_hexagon
        )
        val stored = prefs.getIconShape()
        for (shape in IconShape.entries) {
            val button = MaterialRadioButton(this).apply {
                id = View.generateViewId()
                setText(labels.getValue(shape))
                textSize = 16f
                minHeight = dp(44)
                tag = shape
                isChecked = shape == stored
            }
            button.setOnCheckedChangeListener { view, checked ->
                if (!checked) return@setOnCheckedChangeListener
                val chosen = view.tag as? IconShape ?: return@setOnCheckedChangeListener
                prefs.setIconShape(chosen)
                IconRenderer.clearCache()
                renderPreview()
            }
            binding.shapeGroup.addView(button)
        }
    }

    private fun bindScopeSwitch(
        switch: MaterialSwitch,
        scope: IconScope,
        get: (IconScope) -> Boolean,
        set: (IconScope, Boolean) -> Unit
    ) {
        switch.isChecked = get(scope)
        switch.setOnCheckedChangeListener { _, checked ->
            set(scope, checked)
            IconRenderer.clearCache()
        }
    }

    /** Draws the current shape, at its current scale, over a sample icon. */
    private fun renderPreview() {
        val source = ContextCompat.getDrawable(this, R.drawable.ic_apps)
        val shape = prefs.getIconShape()
        binding.shapePreview.setImageDrawable(
            if (source == null || shape == IconShape.NONE) {
                source
            } else {
                MaskedIconDrawable(
                    source,
                    shape,
                    prefs.getIconShapeCornerPercent(),
                    prefs.getIconShapeScalePercent() / 100f
                )
            }
        )
    }

    private fun bindPackSummary() {
        val stored = prefs.getIconPack()
        val pack = packs.firstOrNull { it.packageName == stored }
        binding.iconPackSummary.text = pack?.label ?: getString(R.string.settings_icon_pack_system)
        binding.iconPackIcon.setImageDrawable(pack?.icon ?: ContextCompat.getDrawable(this, R.drawable.ic_apps))
        val applied = stored.isNotEmpty()
        binding.packHomeSwitch.isEnabled = applied
        binding.packDockSwitch.isEnabled = applied
        binding.packDrawerSwitch.isEnabled = applied
        binding.packFolderSwitch.isEnabled = applied
    }

    /** Reads the installed packs off the main thread: each one is a package to open and look at. */
    private fun loadPacks() {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { IconPackRepository.discover(this@IconsSettingsActivity) }
            packs = found
            val stored = prefs.getIconPack()
            if (stored.isNotEmpty() && found.none { it.packageName == stored }) {
                // The pack was uninstalled: fall back to the system icons.
                prefs.setIconPack("")
                IconRenderer.clearCache()
            }
            bindPackSummary()
        }
    }

    private fun choosePack() {
        if (packs.isEmpty()) {
            Toast.makeText(this, R.string.settings_icon_pack_none, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = ArrayList<String>(packs.size + 1)
        labels.add(getString(R.string.settings_icon_pack_system))
        labels.addAll(packs.map { it.label })

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_icon_pack)
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = if (which == 0) "" else packs[which - 1].packageName
                prefs.setIconPack(chosen)
                IconPackRepository.clearCache()
                IconRenderer.clearCache()
                bindPackSummary()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
