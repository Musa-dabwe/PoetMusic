package com.musa.poetmusic.playback

/** One equalizer band as the platform reports it. */
data class EqBand(val index: Int, val centerHz: Int, val levelMillibels: Int)

/** Everything the Settings equalizer card needs to render the chain in one read. */
data class EqState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val preset: String = EqPresets.DEFAULT,
    val bands: List<EqBand> = emptyList(),
    val minLevel: Int = -1500,
    val maxLevel: Int = 1500,
    val bassAvailable: Boolean = false,
    val bassStrength: Int = 0,
    val virtualizerAvailable: Boolean = false,
    val virtualizerStrength: Int = 0
)

/**
 * The audio effects chain, as the Settings card sees it
 * (docs/desktop-app-plan.md §2.1).
 *
 * Android drives `android.media.audiofx` on the ExoPlayer session; the desktop
 * drives GStreamer's `equalizer-10bands`. Every effect is optional on both:
 * a platform that refuses to create one reports it unavailable and the card
 * says so, rather than playback failing.
 */
interface EqPort {

    /** A snapshot of the chain for rendering; never throws. */
    fun state(): EqState

    fun setEnabled(on: Boolean)
    fun applyPreset(name: String)

    /**
     * Move one band. Dragging a slider is what defines a custom curve, so the
     * selected preset switches to Custom and the whole curve is persisted.
     */
    fun setBand(index: Int, millibels: Int)

    fun setBassStrength(strength: Int)
    fun setVirtualizerStrength(strength: Int)

    companion object {
        /** Strength scale both bass boost and virtualizer take. */
        const val STRENGTH_MAX = 1000

        /** Settings keys backing the persisted effect state, shared by both builds. */
        const val KEY_ENABLED = "eq_on"
        const val KEY_PRESET = "eq_preset"
        const val KEY_BANDS = "eq_bands"
        const val KEY_BASS = "eq_bass"
        const val KEY_VIRTUALIZER = "eq_virtualizer"
    }
}
