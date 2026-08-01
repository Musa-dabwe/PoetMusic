package com.musa.poetmusic.desktop

import com.musa.poetmusic.data.LibraryStore
import com.musa.poetmusic.playback.EqBand
import com.musa.poetmusic.playback.EqPort
import com.musa.poetmusic.playback.EqPresets
import com.musa.poetmusic.playback.EqState
import org.freedesktop.gstreamer.Element

/**
 * The Settings equalizer card, driven by GStreamer's `equalizer-10bands`
 * (docs/desktop-app-plan.md §3.5).
 *
 * The Android build asks the device how many bands it has and at which centre
 * frequencies; here the element's ten bands are fixed, and their centres are
 * the ISO octave series below. Presets survive the difference because
 * [EqPresets] stores a curve at five anchor frequencies and interpolates it in
 * log-frequency space, so "Rock" means the same shape on a 5-band phone and on
 * these ten bands.
 *
 * Bass boost and virtualizer have no honest GStreamer counterpart, so they are
 * reported unavailable and the card's existing "not offered here" path hides
 * them — better than a fake slider that moves nothing.
 */
class GstEq(private val store: LibraryStore, private val element: Element?) : EqPort {

    /** `equalizer-10bands`' fixed band centres, in Hz. */
    private val centres = intArrayOf(29, 59, 119, 237, 474, 947, 1889, 3770, 7523, 15011)

    /** The element takes dB directly, clamped to this range. */
    private val minDb = -24.0
    private val maxDb = 12.0

    private val minLevel = (minDb * 100).toInt()
    private val maxLevel = (maxDb * 100).toInt()

    init {
        restore()
    }

    private fun enabledSetting(): Boolean = store.getSetting(EqPort.KEY_ENABLED, "0") == "1"

    private fun storedPreset(): String =
        store.getSetting(EqPort.KEY_PRESET, EqPresets.DEFAULT).takeIf(EqPresets::isKnown) ?: EqPresets.DEFAULT

    /** Re-apply the persisted curve to a freshly built pipeline. */
    private fun restore() {
        if (element == null) return
        val preset = storedPreset()
        if (preset == EqPresets.CUSTOM) applyStoredLevels() else applyPresetCurve(preset)
        pushToElement()
    }

    /** Millibel levels currently in force, one per band. */
    private fun levels(): IntArray {
        val stored = store.getSetting(EqPort.KEY_BANDS, "").split(',').mapNotNull { it.trim().toIntOrNull() }
        if (stored.size == centres.size) return stored.toIntArray()
        return IntArray(centres.size) { EqPresets.gainMillibels(storedPreset(), centres[it]) }
    }

    private fun applyPresetCurve(preset: String) {
        val curve = IntArray(centres.size) { EqPresets.gainMillibels(preset, centres[it]) }
        store.setSetting(EqPort.KEY_BANDS, curve.joinToString(","))
    }

    private fun applyStoredLevels() {
        // Nothing to do: levels() already falls back to the preset curve when
        // the stored list does not match the band count.
    }

    /**
     * Push the persisted curve into the element. A disabled chain is flattened
     * rather than bypassed, since playbin's audio-filter cannot be swapped out
     * mid-stream — the audible result is the same.
     */
    private fun pushToElement() {
        val el = element ?: return
        val on = enabledSetting()
        val ls = levels()
        runCatching {
            centres.indices.forEach { i ->
                val db = if (on) (ls.getOrElse(i) { 0 } / 100.0).coerceIn(minDb, maxDb) else 0.0
                el.set("band$i", db)
            }
        }
    }

    override fun state(): EqState {
        val ls = levels()
        return EqState(
            available = element != null,
            enabled = enabledSetting(),
            preset = storedPreset(),
            bands = if (element == null) emptyList()
            else centres.mapIndexed { i, hz ->
                EqBand(i, hz, ls.getOrElse(i) { 0 }.coerceIn(minLevel, maxLevel))
            },
            minLevel = minLevel,
            maxLevel = maxLevel,
            // No GStreamer counterpart — see the class comment.
            bassAvailable = false,
            virtualizerAvailable = false
        )
    }

    override fun setEnabled(on: Boolean) {
        store.setSetting(EqPort.KEY_ENABLED, if (on) "1" else "0")
        pushToElement()
    }

    override fun applyPreset(name: String) {
        val preset = name.takeIf(EqPresets::isKnown) ?: EqPresets.DEFAULT
        store.setSetting(EqPort.KEY_PRESET, preset)
        if (preset != EqPresets.CUSTOM) applyPresetCurve(preset)
        pushToElement()
    }

    override fun setBand(index: Int, millibels: Int) {
        if (index !in centres.indices) return
        val ls = levels()
        ls[index] = millibels.coerceIn(minLevel, maxLevel)
        // Dragging a slider is what defines a custom curve.
        store.setSetting(EqPort.KEY_PRESET, EqPresets.CUSTOM)
        store.setSetting(EqPort.KEY_BANDS, ls.joinToString(","))
        pushToElement()
    }

    override fun setBassStrength(strength: Int) = Unit
    override fun setVirtualizerStrength(strength: Int) = Unit
}
