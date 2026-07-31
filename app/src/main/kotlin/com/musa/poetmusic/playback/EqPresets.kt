package com.musa.poetmusic.playback

import kotlin.math.log10

/**
 * The equalizer's preset curves, defined independently of any device.
 *
 * Android hands back however many bands the platform effect happens to expose
 * (5 on most phones, 10 on some), at centre frequencies we don't get to pick.
 * So a preset is stored as a gain curve sampled at five fixed anchor
 * frequencies and read back at whatever centre frequency a band reports,
 * interpolated in log-frequency space — the axis the anchors are spaced on and
 * the one hearing works in. That makes a preset mean the same thing on a
 * 5-band and a 10-band device.
 *
 * Pure Kotlin on purpose: this is the part worth testing off-device.
 */
object EqPresets {

    /** The frequencies (Hz) every preset curve is defined at. */
    val ANCHOR_HZ = intArrayOf(60, 230, 910, 3600, 14000)

    /** Selected when the user drags a band; no curve of its own. */
    const val CUSTOM = "Custom"

    const val DEFAULT = "Flat"

    /** Preset gains in dB at [ANCHOR_HZ], in display order. */
    val CURVES: Map<String, IntArray> = linkedMapOf(
        "Flat" to intArrayOf(0, 0, 0, 0, 0),
        "Rock" to intArrayOf(6, 3, -2, 3, 6),
        "Pop" to intArrayOf(-2, 3, 5, 2, -1),
        "Jazz" to intArrayOf(4, 2, -2, 2, 5),
        "Classical" to intArrayOf(5, 3, -1, 3, 4),
        "Dance" to intArrayOf(7, 4, -2, 2, 5)
    )

    /** Preset names for the selector, with Custom pinned last. */
    val NAMES: List<String> = CURVES.keys.toList() + CUSTOM

    fun isKnown(name: String): Boolean = name == CUSTOM || name in CURVES

    /**
     * The gain [preset] asks for at [centerHz], in millibels (the unit
     * android.media.audiofx.Equalizer works in). Frequencies outside the anchor
     * range clamp to the nearest end of the curve rather than extrapolating.
     * Custom (and any unknown name) is flat — its levels come from storage, not
     * from a curve.
     */
    fun gainMillibels(preset: String, centerHz: Int): Int {
        val curve = CURVES[preset] ?: return 0
        if (centerHz <= ANCHOR_HZ.first()) return curve.first() * 100
        if (centerHz >= ANCHOR_HZ.last()) return curve.last() * 100
        val x = log10(centerHz.toDouble())
        for (i in 0 until ANCHOR_HZ.size - 1) {
            val lo = ANCHOR_HZ[i]
            val hi = ANCHOR_HZ[i + 1]
            if (centerHz in lo..hi) {
                val loX = log10(lo.toDouble())
                val hiX = log10(hi.toDouble())
                val t = (x - loX) / (hiX - loX)
                val dB = curve[i] + (curve[i + 1] - curve[i]) * t
                return Math.round(dB * 100).toInt()
            }
        }
        return 0
    }

    /** Short axis label for a band's centre frequency ("60", "1.4k"). */
    fun label(centerHz: Int): String = when {
        centerHz >= 10_000 -> "${centerHz / 1000}k"
        centerHz >= 1_000 -> {
            val k = centerHz / 1000.0
            if (k % 1.0 < 0.05) "${k.toInt()}k" else String.format(java.util.Locale.US, "%.1fk", k)
        }
        else -> centerHz.toString()
    }
}
