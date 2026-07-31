package com.musa.poetmusic.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.musa.poetmusic.data.MusicDatabase

/**
 * The platform audio effects chain — graphic equalizer, bass boost and
 * virtualizer — attached to the ExoPlayer's audio session.
 *
 * PlaybackService allocates the session id itself and hands it over in
 * onCreate, before anything is queued, which sidesteps the usual attach-order
 * problem: there is no window where playback has started but the effects have
 * no session to bind to. The effects live exactly as long as the service's
 * player and are released in [release].
 *
 * Every effect here is optional hardware — plenty of devices and most
 * emulators refuse to create one. A refusal is not an error: the corresponding
 * `*Available` flag goes false and the Settings card says so, rather than the
 * app failing to start playback.
 */
object AudioFx {

    private const val TAG = "AudioFx"

    /** Settings keys backing the persisted effect state. */
    private const val KEY_ENABLED = "eq_on"
    private const val KEY_PRESET = "eq_preset"
    private const val KEY_BANDS = "eq_bands"
    private const val KEY_BASS = "eq_bass"
    private const val KEY_VIRTUALIZER = "eq_virtualizer"

    /** Strength scale both BassBoost and Virtualizer take (0..1000). */
    const val STRENGTH_MAX = 1000

    /** One equalizer band as the device reports it. */
    data class Band(val index: Int, val centerHz: Int, val levelMillibels: Int)

    /** Everything the Settings card needs to render the chain in one read. */
    data class State(
        val available: Boolean = false,
        val enabled: Boolean = false,
        val preset: String = EqPresets.DEFAULT,
        val bands: List<Band> = emptyList(),
        val minLevel: Int = -1500,
        val maxLevel: Int = 1500,
        val bassAvailable: Boolean = false,
        val bassStrength: Int = 0,
        val virtualizerAvailable: Boolean = false,
        val virtualizerStrength: Int = 0
    )

    private val lock = Any()

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    @Volatile private var sessionId = 0

    /**
     * Create the effects for [sessionId] and restore the persisted settings.
     * Safe to call again — a previous chain is released first.
     */
    fun attach(sessionId: Int, db: MusicDatabase) = synchronized(lock) {
        releaseLocked()
        this.sessionId = sessionId
        // Priority 0: we are a normal foreground media app, not a system effect.
        equalizer = runCatching { Equalizer(0, sessionId) }
            .onFailure { Log.w(TAG, "Equalizer unavailable on this device: ${it.message}") }
            .getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }
            .onFailure { Log.w(TAG, "BassBoost unavailable: ${it.message}") }
            .getOrNull()
            ?.takeIf { runCatching { it.strengthSupported }.getOrDefault(false) }
        virtualizer = runCatching { Virtualizer(0, sessionId) }
            .onFailure { Log.w(TAG, "Virtualizer unavailable: ${it.message}") }
            .getOrNull()
            ?.takeIf { runCatching { it.strengthSupported }.getOrDefault(false) }
        restoreLocked(db)
    }

    fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        listOfNotNull(equalizer, bassBoost, virtualizer).forEach { runCatching { it.release() } }
        equalizer = null
        bassBoost = null
        virtualizer = null
        sessionId = 0
    }

    /** Re-apply the stored preset / custom levels / strengths to a fresh chain. */
    private fun restoreLocked(db: MusicDatabase) {
        val on = db.getSetting(KEY_ENABLED, "0") == "1"
        val preset = db.getSetting(KEY_PRESET, EqPresets.DEFAULT).takeIf(EqPresets::isKnown) ?: EqPresets.DEFAULT
        if (preset == EqPresets.CUSTOM) applyStoredLevelsLocked(db) else applyPresetLocked(preset)
        runCatching { equalizer?.enabled = on }
        val bass = db.getSetting(KEY_BASS, "0").toIntOrNull()?.coerceIn(0, STRENGTH_MAX) ?: 0
        val virt = db.getSetting(KEY_VIRTUALIZER, "0").toIntOrNull()?.coerceIn(0, STRENGTH_MAX) ?: 0
        applyStrengthsLocked(on, bass, virt)
    }

    /** Effects only run while the chain is switched on, so a disabled EQ is truly bypassed. */
    private fun applyStrengthsLocked(on: Boolean, bass: Int, virt: Int) {
        runCatching {
            bassBoost?.let { it.setStrength(bass.toShort()); it.enabled = on && bass > 0 }
        }
        runCatching {
            virtualizer?.let { it.setStrength(virt.toShort()); it.enabled = on && virt > 0 }
        }
    }

    private fun applyPresetLocked(preset: String) {
        val eq = equalizer ?: return
        runCatching {
            for (i in 0 until eq.numberOfBands.toInt()) {
                // getCenterFreq reports milliHz; the preset curves are in Hz.
                val hz = eq.getCenterFreq(i.toShort()) / 1000
                val level = EqPresets.gainMillibels(preset, hz)
                eq.setBandLevel(i.toShort(), clampLevel(eq, level).toShort())
            }
        }
    }

    private fun applyStoredLevelsLocked(db: MusicDatabase) {
        val eq = equalizer ?: return
        val stored = db.getSetting(KEY_BANDS, "").split(',').mapNotNull { it.trim().toIntOrNull() }
        runCatching {
            val count = eq.numberOfBands.toInt()
            // A band count that no longer matches (different device, or the
            // effect reporting differently) makes the stored curve meaningless.
            if (stored.size != count) return@runCatching applyPresetLocked(EqPresets.DEFAULT)
            for (i in 0 until count) eq.setBandLevel(i.toShort(), clampLevel(eq, stored[i]).toShort())
        }
    }

    private fun levelRange(eq: Equalizer): Pair<Int, Int> {
        val range = runCatching { eq.bandLevelRange }.getOrNull()
        return (range?.getOrNull(0)?.toInt() ?: -1500) to (range?.getOrNull(1)?.toInt() ?: 1500)
    }

    private fun clampLevel(eq: Equalizer, millibels: Int): Int {
        val (min, max) = levelRange(eq)
        return millibels.coerceIn(min, max)
    }

    /** A snapshot of the chain for rendering; never throws. */
    fun state(db: MusicDatabase): State = synchronized(lock) {
        val eq = equalizer
        val storedPreset = db.getSetting(KEY_PRESET, EqPresets.DEFAULT)
            .takeIf(EqPresets::isKnown) ?: EqPresets.DEFAULT
        val bass = db.getSetting(KEY_BASS, "0").toIntOrNull()?.coerceIn(0, STRENGTH_MAX) ?: 0
        val virt = db.getSetting(KEY_VIRTUALIZER, "0").toIntOrNull()?.coerceIn(0, STRENGTH_MAX) ?: 0
        if (eq == null) {
            return State(
                available = false,
                enabled = db.getSetting(KEY_ENABLED, "0") == "1",
                preset = storedPreset,
                bassAvailable = bassBoost != null, bassStrength = bass,
                virtualizerAvailable = virtualizer != null, virtualizerStrength = virt
            )
        }
        val (min, max) = levelRange(eq)
        val bands = runCatching {
            (0 until eq.numberOfBands.toInt()).map { i ->
                Band(
                    index = i,
                    centerHz = eq.getCenterFreq(i.toShort()) / 1000,
                    levelMillibels = eq.getBandLevel(i.toShort()).toInt()
                )
            }
        }.getOrDefault(emptyList())
        State(
            available = bands.isNotEmpty(),
            enabled = runCatching { eq.enabled }.getOrDefault(false),
            preset = storedPreset,
            bands = bands,
            minLevel = min,
            maxLevel = max,
            bassAvailable = bassBoost != null, bassStrength = bass,
            virtualizerAvailable = virtualizer != null, virtualizerStrength = virt
        )
    }

    fun setEnabled(db: MusicDatabase, on: Boolean) = synchronized(lock) {
        db.setSetting(KEY_ENABLED, if (on) "1" else "0")
        runCatching { equalizer?.enabled = on }
        val bass = db.getSetting(KEY_BASS, "0").toIntOrNull() ?: 0
        val virt = db.getSetting(KEY_VIRTUALIZER, "0").toIntOrNull() ?: 0
        applyStrengthsLocked(on, bass, virt)
    }

    fun applyPreset(db: MusicDatabase, name: String) = synchronized(lock) {
        val preset = name.takeIf(EqPresets::isKnown) ?: EqPresets.DEFAULT
        db.setSetting(KEY_PRESET, preset)
        if (preset == EqPresets.CUSTOM) applyStoredLevelsLocked(db) else applyPresetLocked(preset)
        persistLevelsLocked(db)
    }

    /**
     * Move one band. Dragging a slider is what defines a custom curve, so the
     * selected preset switches to Custom and the whole curve is persisted.
     */
    fun setBand(db: MusicDatabase, index: Int, millibels: Int) = synchronized(lock) {
        val eq = equalizer ?: return
        runCatching {
            if (index !in 0 until eq.numberOfBands.toInt()) return@runCatching
            eq.setBandLevel(index.toShort(), clampLevel(eq, millibels).toShort())
        }
        db.setSetting(KEY_PRESET, EqPresets.CUSTOM)
        persistLevelsLocked(db)
    }

    fun setBassStrength(db: MusicDatabase, strength: Int) = synchronized(lock) {
        val v = strength.coerceIn(0, STRENGTH_MAX)
        db.setSetting(KEY_BASS, v.toString())
        val on = db.getSetting(KEY_ENABLED, "0") == "1"
        runCatching { bassBoost?.let { it.setStrength(v.toShort()); it.enabled = on && v > 0 } }
    }

    fun setVirtualizerStrength(db: MusicDatabase, strength: Int) = synchronized(lock) {
        val v = strength.coerceIn(0, STRENGTH_MAX)
        db.setSetting(KEY_VIRTUALIZER, v.toString())
        val on = db.getSetting(KEY_ENABLED, "0") == "1"
        runCatching { virtualizer?.let { it.setStrength(v.toShort()); it.enabled = on && v > 0 } }
    }

    private fun persistLevelsLocked(db: MusicDatabase) {
        val eq = equalizer ?: return
        val csv = runCatching {
            (0 until eq.numberOfBands.toInt()).joinToString(",") { eq.getBandLevel(it.toShort()).toString() }
        }.getOrNull() ?: return
        db.setSetting(KEY_BANDS, csv)
    }
}
