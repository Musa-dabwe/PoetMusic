package com.musa.poetmusic.data

/**
 * The look-and-feel triplet, read as one unit.
 *
 * The server shell, the settings screen, the activity's status bar and the
 * home-screen widget all need the same three values with the same defaults.
 * Reading them through here keeps the default strings in one place, so a
 * typo can't leave one surface on a different accent than the rest.
 *
 * Deliberately pure data: the `theme` name is resolved to a canvas colour by
 * `Shell.CANVAS_TINTS` at the call sites, so `data/` stays independent of
 * `server/`.
 */
data class AppSettings(
    val accent: String = DEFAULT_ACCENT,
    val theme: String = DEFAULT_THEME,
    val dark: Boolean = false
) {
    companion object {
        const val DEFAULT_ACCENT = "#b9a5ec"
        const val DEFAULT_THEME = "Lavender"

        fun from(db: MusicDatabase) = AppSettings(
            accent = db.getSetting("accent", DEFAULT_ACCENT),
            theme = db.getSetting("theme", DEFAULT_THEME),
            dark = db.getSetting("dark", "0") == "1"
        )
    }
}
