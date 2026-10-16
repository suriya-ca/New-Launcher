package com.sdev.newlauncher.data

/**
 * The mask an app icon is drawn inside. [NONE] leaves the icon exactly as the system (or the
 * active icon pack) drew it, so the default is what the launcher has always shown.
 */
enum class IconShape {
    /** No mask: the icon's own shape is used. */
    NONE,

    /** A full circle (ellipse when the cell is not square). */
    CIRCLE,

    /** A superellipse — the softer, "squircle" shape of modern adaptive icons. */
    SQUIRCLE,

    /** A rounded rectangle whose corner radius is set by the shape-corner preference. */
    ROUNDED,

    /** A circle with one pointed corner (top right), like a guitar pick. */
    TEARDROP,

    /** A six-sided polygon, point up. */
    HEXAGON;

    companion object {
        fun fromStored(value: Int): IconShape = entries.getOrElse(value) { NONE }

        fun toStored(shape: IconShape): Int = shape.ordinal
    }
}

/**
 * A place an app icon is drawn. Shapes and icon packs can each be switched on or off per
 * surface, so a themed icon can be used in the drawer while the home keeps the system one.
 */
enum class IconScope {
    HOME,
    DOCK,
    DRAWER,
    FOLDER
}
