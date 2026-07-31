package com.musa.poetmusic.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preset curves have to mean the same thing whatever band layout a device
 * reports, which is the whole reason they are stored as a curve rather than as
 * a fixed list of per-band levels.
 */
class EqPresetsTest {

    @Test
    fun `a curve reads back exactly at its own anchor frequencies`() {
        val rock = EqPresets.CURVES.getValue("Rock")
        EqPresets.ANCHOR_HZ.forEachIndexed { i, hz ->
            assertEquals(
                "anchor $hz Hz",
                rock[i] * 100,
                EqPresets.gainMillibels("Rock", hz)
            )
        }
    }

    @Test
    fun `flat is flat at every frequency`() {
        listOf(20, 60, 300, 1000, 5000, 14000, 20000).forEach { hz ->
            assertEquals(0, EqPresets.gainMillibels("Flat", hz))
        }
    }

    @Test
    fun `between anchors the gain is interpolated, not snapped`() {
        // Rock dips from +3 dB at 230 Hz to -2 dB at 910 Hz, so a band in
        // between must land strictly between the two.
        val mid = EqPresets.gainMillibels("Rock", 500)
        assertTrue("expected between -200 and 300 mB, got $mid", mid in -200..300)
        assertTrue("must not snap to an anchor", mid != 300 && mid != -200)
    }

    @Test
    fun `interpolation is monotonic along a falling segment`() {
        var previous = Int.MAX_VALUE
        for (hz in intArrayOf(230, 350, 500, 700, 910)) {
            val g = EqPresets.gainMillibels("Rock", hz)
            assertTrue("gain should fall across 230..910 Hz, saw $g after $previous", g <= previous)
            previous = g
        }
    }

    @Test
    fun `frequencies outside the anchor range clamp instead of extrapolating`() {
        val dance = EqPresets.CURVES.getValue("Dance")
        assertEquals(dance.first() * 100, EqPresets.gainMillibels("Dance", 20))
        assertEquals(dance.first() * 100, EqPresets.gainMillibels("Dance", 1))
        assertEquals(dance.last() * 100, EqPresets.gainMillibels("Dance", 20_000))
        assertEquals(dance.last() * 100, EqPresets.gainMillibels("Dance", 48_000))
    }

    @Test
    fun `custom and unknown presets are flat - their levels come from storage`() {
        assertEquals(0, EqPresets.gainMillibels(EqPresets.CUSTOM, 60))
        assertEquals(0, EqPresets.gainMillibels("Nonsense", 3600))
    }

    @Test
    fun `only the shipped presets and Custom are accepted`() {
        assertTrue(EqPresets.isKnown("Flat"))
        assertTrue(EqPresets.isKnown(EqPresets.CUSTOM))
        assertFalse(EqPresets.isKnown("Nonsense"))
        assertFalse(EqPresets.isKnown(""))
    }

    @Test
    fun `every curve has one gain per anchor and the default is a real preset`() {
        EqPresets.CURVES.forEach { (name, curve) ->
            assertEquals("$name must cover every anchor", EqPresets.ANCHOR_HZ.size, curve.size)
        }
        assertTrue(EqPresets.DEFAULT in EqPresets.CURVES)
        assertEquals(EqPresets.CUSTOM, EqPresets.NAMES.last())
    }

    @Test
    fun `band labels shorten kilohertz`() {
        assertEquals("60", EqPresets.label(60))
        assertEquals("910", EqPresets.label(910))
        assertEquals("1k", EqPresets.label(1000))
        assertEquals("3.6k", EqPresets.label(3600))
        assertEquals("14k", EqPresets.label(14000))
    }
}
