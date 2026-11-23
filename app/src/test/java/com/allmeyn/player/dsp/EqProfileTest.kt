package com.allmeyn.player.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EqProfileTest {
    // AutoEq results/oratory1990/over-ear/Sennheiser HD 650/Sennheiser HD 650 ParametricEQ.txt
    private val hd650 = """
        Preamp: -6.1 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
        Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42
        Filter 3: ON PK Fc 118 Hz Gain -3.1 dB Q 0.50
        Filter 4: ON PK Fc 37 Hz Gain 0.7 dB Q 3.96
        Filter 5: ON PK Fc 3169 Hz Gain -1.7 dB Q 3.89
        Filter 6: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70
        Filter 7: ON PK Fc 1227 Hz Gain -1.2 dB Q 2.53
        Filter 8: ON PK Fc 2055 Hz Gain 1.2 dB Q 3.23
        Filter 9: ON PK Fc 587 Hz Gain 0.4 dB Q 1.19
        Filter 10: ON PK Fc 5332 Hz Gain -1.1 dB Q 5.75
    """.trimIndent()

    @Test fun readsAutoEqParametricEq() {
        val r = EqParser.parse(hd650, "Sennheiser HD 650")
        val p = r.profile
        assertEquals("Sennheiser HD 650", p.name)
        assertEquals(-6.1f, p.preampDb, 1e-6f)
        assertEquals(10, p.bands.size)
        assertEquals(EqBand(EqFilterType.LOW_SHELF, 105f, 6.4f, 0.70f), p.bands[0])
        assertEquals(EqFilterType.HIGH_SHELF, p.bands[5].type)
        // AutoEq's own curve for these filters at 44.1 kHz (its biquad formulas), plus the preamp.
        val freqs = floatArrayOf(20f, 100f, 1000f, 3169f, 8800f, 15000f)
        val want = doubleArrayOf(6.07, 0.493, -0.541, -1.347, 4.364, -1.464)
        val got = p.responseDb(freqs, 44100.0)
        for (i in freqs.indices) assertEquals("at ${freqs[i]} Hz", want[i] - 6.1, got[i].toDouble(), 0.002)
        assertTrue(r.notes.none { it.startsWith("Peaks") })  // the preamp covers the boosts
    }

    @Test fun exportRoundTrips() {
        val p = EqParser.parse(hd650, "HD 650").profile
        val again = EqParser.parse(p.toApoText()).profile
        assertEquals(p, again)
    }

    @Test fun otherSyntaxes() {
        val r = EqParser.parse(
            """
            # My cans
            Preamp: -3 dB
            Preamp: -1,5 dB
            Channel: L
            Filter: ON PEQ Fc 1000 Hz Gain 3 dB BW Oct 1.0
            Filter 2: OFF PK Fc 2000 Hz Gain 3 dB Q 1
            Filter 3: ON HP Fc 20 Hz
            Filter 4: ON NO Fc 50 Hz
            """.trimIndent(),
        )
        val p = r.profile
        assertEquals("My cans", p.name)
        assertEquals(-4.5f, p.preampDb, 1e-6f)
        assertEquals(2, p.bands.size)
        assertEquals(1.414f, p.bands[0].q, 0.001f)  // 1 octave
        assertEquals(EqBand(EqFilterType.HIGH_PASS, 20f, 0f, 0.71f), p.bands[1])
        assertTrue(r.notes.any { "OFF" in it } && r.notes.any { "unsupported" in it } && r.notes.any { "both channels" in it })
    }

    @Test fun refusesGraphicEqAndJunkClearly() {
        try { EqParser.parse("GraphicEQ: 20 -0.2; 21 -0.2"); fail() } catch (e: IllegalArgumentException) { assertTrue("convolution" in e.message!!) }
        try { EqParser.parse("hello"); fail() } catch (e: IllegalArgumentException) { assertTrue("No EqualizerAPO" in e.message!!) }
    }

    @Test fun warnsWhenBoostsCanClip() {
        val r = EqParser.parse("Filter 1: ON PK Fc 100 Hz Gain 6 dB Q 1")
        assertTrue(r.notes.any { it.startsWith("Peaks at +6.0 dB") })
    }

    @Test fun libraryKeepsSeveralProfiles() {
        val a = EqParser.parse(hd650, "HD 650").profile
        val b = EqProfile("Flat-ish", -1f, listOf(EqBand(EqFilterType.PEAK, 3000f, -2f, 2f)))
        assertEquals(listOf(a, b), EqLibrary.decode(EqLibrary.encode(listOf(a, b))))
    }

    @Test fun nativeLayoutCarriesTheEq() {
        val p = DspParams(eqOn = true, eq = EqParser.parse(hd650).profile)
        val v = p.toNativeValues()
        assertEquals(16 + 3 + 4 * 10, v.size)
        assertEquals(1f, v[16]); assertEquals(-6.1f, v[17]); assertEquals(10f, v[18])
        assertEquals(EqFilterType.LOW_SHELF.id.toFloat(), v[19]); assertEquals(105f, v[20])
        assertTrue(p.copy(enabled = true).processes)
        assertFalse(p.copy(enabled = true, eqOn = false, companderOn = false).processes)
    }
}
