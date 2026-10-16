package com.sdev.newlauncher.data

/**
 * One user-defined tab in the app drawer: a name and the packages filed under it. An app can be
 * in any number of categories; the drawer's "All" tab always shows everything.
 */
data class DrawerCategory(
    val id: String,
    val name: String,
    val packages: List<String>
) {
    fun contains(packageName: String): Boolean = packages.contains(packageName)
}

/** Which panel a swipe down on the empty home screen opens. */
enum class SwipeDownAction {
    /** Pull the notification shade down. */
    NOTIFICATIONS,

    /** Pull the quick-settings (control) panel down. */
    QUICK_SETTINGS;

    companion object {
        fun fromStored(value: Int): SwipeDownAction = entries.getOrElse(value) { NOTIFICATIONS }

        fun toStored(action: SwipeDownAction): Int = action.ordinal
    }
}

/**
 * Where the drawer's category tabs sit relative to the search field.
 *
 * The tabs themselves are always laid out fixed (each one an equal share of the width) and drawn
 * as filled pills, the selected one tinted; that is the launcher's one look for them, so there is
 * nothing to choose.
 */
enum class DrawerTabPosition {
    ABOVE_SEARCH,
    BELOW_SEARCH;

    companion object {
        fun fromStored(value: Int): DrawerTabPosition = entries.getOrElse(value) { BELOW_SEARCH }

        fun toStored(position: DrawerTabPosition): Int = position.ordinal
    }
}
