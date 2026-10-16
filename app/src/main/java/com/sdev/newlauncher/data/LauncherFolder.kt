package com.sdev.newlauncher.data

/**
 * A named container of home apps.
 *
 * A folder is an ordinary home placement (a [HomeItem] with [HomeItem.folderId] set) plus one
 * of these, which holds the apps inside it as their [AppInfo.key] style `package/activity`
 * strings. Keeping the contents in a side table means a folder moves, is deleted with its page
 * and survives reordering exactly like any other item.
 */
data class LauncherFolder(
    val id: String,
    val name: String,
    val children: List<String>
) {
    /** True once the folder holds no apps and is only waiting to be removed. */
    val isEmpty: Boolean get() = children.isEmpty()
}

/** How a folder's cell draws the apps inside it. */
enum class FolderPreview {
    /** A 2×2 grid of the first four apps (default). */
    GRID_2X2,

    /** A 3×3 grid of the first nine apps. */
    GRID_3X3;

    companion object {
        fun fromStored(value: Int): FolderPreview = entries.getOrElse(value) { GRID_2X2 }

        fun toStored(preview: FolderPreview): Int = preview.ordinal
    }
}

/** The backdrop drawn behind a folder's cell. */
enum class FolderBackground {
    /** Follows the icon shape of the launchable apps. */
    AUTO,

    CIRCLE,
    ROUNDED,
    SQUARE;

    companion object {
        fun fromStored(value: Int): FolderBackground = entries.getOrElse(value) { AUTO }

        fun toStored(background: FolderBackground): Int = background.ordinal
    }
}

data class DrawerFolder(
    val id: String,
    val name: String,
    val children: List<String>
) {
    /** True once the folder holds no apps. */
    val isEmpty: Boolean get() = children.isEmpty()

    /** Whether another app can still be added (with room to spare). */
    fun canAdd(key: String): Boolean = key !in children && children.size < 36
}
enum class FolderOpenStyle {
    /** A sheet that slides up from the bottom of the screen (default). */
    SHEET,

    /** A compact dialog in the middle of the screen. */
    POPUP;

    companion object {
        fun fromStored(value: Int): FolderOpenStyle = entries.getOrElse(value) { SHEET }

        fun toStored(style: FolderOpenStyle): Int = style.ordinal
    }
}
