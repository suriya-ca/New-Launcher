package com.sdev.newlauncher

import android.os.Bundle
import com.sdev.newlauncher.data.FolderBackground
import com.sdev.newlauncher.data.FolderOpenStyle
import com.sdev.newlauncher.data.FolderPreview
import com.sdev.newlauncher.databinding.ActivitySettingsFoldersBinding

/**
 * "Folders" page: whether folders exist at all, how a folder's cell previews its apps and what
 * happens to those apps when the folder is deleted.
 */
class FoldersSettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsFoldersBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsFoldersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.settingsRoot)
        bindToolbar(binding.toolbar)

        binding.foldersEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setFoldersEnabled(checked)
        }

        binding.folderPreviewGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.setFolderPreview(
                if (checkedId == R.id.folder_preview_3x3) FolderPreview.GRID_3X3
                else FolderPreview.GRID_2X2
            )
        }

        binding.folderBackgroundGroup.setOnCheckedChangeListener { _, checkedId ->
            val background = when (checkedId) {
                R.id.folder_bg_circle -> FolderBackground.CIRCLE
                R.id.folder_bg_rounded -> FolderBackground.ROUNDED
                R.id.folder_bg_square -> FolderBackground.SQUARE
                else -> FolderBackground.AUTO
            }
            prefs.setFolderBackground(background)
        }

        binding.folderOpenGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.setFolderOpenStyle(
                if (checkedId == R.id.folder_open_popup) FolderOpenStyle.POPUP else FolderOpenStyle.SHEET
            )
        }

        binding.folderColumnsSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setFolderGridColumns(value.toInt())
            binding.folderColumnsValue.text = getString(R.string.settings_columns_value, value.toInt())
        }

        binding.folderMaxSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.setFolderMaxApps(value.toInt())
            binding.folderMaxValue.text = getString(R.string.settings_folder_max_value, value.toInt())
        }

        binding.folderLabelsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setShowFolderLabels(checked)
        }

        binding.folderUnfoldSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setUnfoldOnDelete(checked)
        }

        refreshFromPrefs()
    }

    /** Pushes the current stored values into the controls. */
    private fun refreshFromPrefs() {
        binding.foldersEnabledSwitch.isChecked = prefs.areFoldersEnabled()

        binding.folderPreviewGroup.check(
            if (prefs.getFolderPreview() == FolderPreview.GRID_3X3) R.id.folder_preview_3x3
            else R.id.folder_preview_2x2
        )

        binding.folderBackgroundGroup.check(
            when (prefs.getFolderBackground()) {
                FolderBackground.CIRCLE -> R.id.folder_bg_circle
                FolderBackground.ROUNDED -> R.id.folder_bg_rounded
                FolderBackground.SQUARE -> R.id.folder_bg_square
                FolderBackground.AUTO -> R.id.folder_bg_auto
            }
        )

        binding.folderOpenGroup.check(
            if (prefs.getFolderOpenStyle() == FolderOpenStyle.POPUP) R.id.folder_open_popup
            else R.id.folder_open_sheet
        )

        binding.folderColumnsSlider.value = prefs.getFolderGridColumns().toFloat()
        binding.folderColumnsValue.text =
            getString(R.string.settings_columns_value, prefs.getFolderGridColumns())

        binding.folderMaxSlider.value = prefs.getFolderMaxApps().toFloat()
        binding.folderMaxValue.text = getString(R.string.settings_folder_max_value, prefs.getFolderMaxApps())

        binding.folderLabelsSwitch.isChecked = prefs.showFolderLabels()
        binding.folderUnfoldSwitch.isChecked = prefs.isUnfoldOnDelete()
    }
}
