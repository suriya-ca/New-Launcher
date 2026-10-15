package com.sdev.newlauncher.data

/**
 * How the app drawer arranges its apps.
 */
enum class DrawerLayoutMode {
    /** Vertically scrolling grid (default). */
    GRID,

    /** Horizontally paged grids. */
    PAGES,

    /** Single-column list of icon + label rows. */
    LIST,

    /** Grid grouped into sections by the first letter of each label. */
    AZ,

    /** Grid in a user-defined order that can be reordered by dragging. */
    CUSTOM;

    companion object {
        fun fromStored(value: Int): DrawerLayoutMode = entries.getOrElse(value) { GRID }

        fun toStored(mode: DrawerLayoutMode): Int = mode.ordinal
    }
}
