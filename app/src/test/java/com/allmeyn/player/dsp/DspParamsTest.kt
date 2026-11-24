package com.allmeyn.player.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspParamsTest {
    @Test fun clampsAndFixesOrder() {
        val p = DspParams(expansionPercent = -80f, transitionDb = 5f, impactReleaseMs = 1f, volumeDb = 30f,
            presencePercent = 150f, detailPercent = -5f, airPercent = 101f,
            order = listOf(DspProcessor.IMPACT, DspProcessor.IMPACT)).sanitized()
        assertEquals(-50f, p.expansionPercent)
        assertEquals(0f, p.transitionDb)
        assertEquals(25f, p.impactReleaseMs)
        assertEquals(10f, p.volumeDb)
        assertEquals(100f, p.presencePercent)
        assertEquals(0f, p.detailPercent)
        assertEquals(100f, p.airPercent)
        assertEquals(listOf(DspProcessor.VOCAL, DspProcessor.IMPACT, DspProcessor.COMPANDER, DspProcessor.BASS), p.order)
    }

    // An order saved before Vocal & Detail Restoration existed loads with it first, the rest kept.
    @Test fun oldSavedOrderGetsVocalFirst() {
        val p = DspParams(order = listOf(DspProcessor.BASS, DspProcessor.IMPACT, DspProcessor.COMPANDER)).sanitized()
        assertEquals(listOf(DspProcessor.VOCAL, DspProcessor.BASS, DspProcessor.IMPACT, DspProcessor.COMPANDER), p.order)
    }

    // Vocal & Detail Restoration is always first, whatever order is asked for.
    @Test fun vocalAlwaysFirst() {
        val p = DspParams(order = listOf(DspProcessor.COMPANDER, DspProcessor.VOCAL, DspProcessor.IMPACT, DspProcessor.BASS)).sanitized()
        assertEquals(listOf(DspProcessor.VOCAL, DspProcessor.COMPANDER, DspProcessor.IMPACT, DspProcessor.BASS), p.order)
        assertEquals(DspProcessor.VOCAL, DspParams().order.first())
    }

    @Test fun processesOnlyWhenOnAndSomethingToDo() {
        assertFalse(DspParams(enabled = false).processes)
        assertFalse(DspParams(enabled = true, companderOn = false, bassOn = false, impactOn = false).processes)
        assertTrue(DspParams(enabled = true, companderOn = false, volumeDb = -3f).processes)
        assertTrue(DspParams(enabled = true, companderOn = false, vocalOn = true).processes)
        assertFalse(DspParams(enabled = false, vocalOn = true).processes)
        assertTrue(DspParams(enabled = true).processes) // compressor/expander on by default
    }

    @Test fun nativeLayout() {
        val p = DspParams(companderOn = true, expansionPercent = -20f, transitionDb = -25f, hfTransitionDb = 2f,
            bassOn = false, bassPercent = 40f, bassTransitionDb = -18f, impactOn = true, impactPercent = 75f,
            impactReleaseMs = 300f, volumeDb = -2f, softClip = true,
            vocalOn = true, presencePercent = 60f, detailPercent = 30f, airPercent = 10f,
            order = listOf(DspProcessor.VOCAL, DspProcessor.BASS, DspProcessor.IMPACT, DspProcessor.COMPANDER))
        // The 16 processor values, then the headphone EQ (off, 0 dB preamp, no filters).
        assertArrayEquals(floatArrayOf(1f, -20f, -25f, 2f, 0f, 40f, -18f, 1f, 75f, 300f, -2f, 1f, 1f, 60f, 30f, 10f, 0f, 0f, 0f), p.toNativeValues(), 0f)
        assertArrayEquals(intArrayOf(3, 1, 2, 0), p.toNativeOrder())
    }

    @Test fun ratioLabel() {
        assertEquals("1:1.25", DspParams(expansionPercent = 25f).ratioLabel)
        assertEquals("1:1.00", DspParams(expansionPercent = 0f).ratioLabel)
        assertEquals("2.00:1", DspParams(expansionPercent = -50f).ratioLabel)
    }

    @Test fun metersLayout() {
        val m = DspMeters.from(FloatArray(NativeDsp.METER_COUNT) { it.toFloat() })
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f), m.companderDb, 0f)
        assertEquals(4f, m.bassDb)
        assertArrayEquals(floatArrayOf(5f, 6f, 7f, 8f), m.impactDb, 0f)
        assertEquals(9f, m.outputPeak)
        assertEquals(10f, m.vocalDb)
        assertEquals(11f, m.airCutoffHz)
    }

    // These labels once used a format string containing "% (", which crashed the DSP screen.
    @Test fun labelsFormat() {
        assertEquals("50 % (ratio 1:1.25 below 150 Hz)", DspParams(bassPercent = 50f).bassLabel)
        assertEquals("100 % (ratio 1:1.50 below 150 Hz)", DspParams(bassPercent = 100f).bassLabel)
        assertEquals("50 % (up to +6 dB)", DspParams(impactPercent = 50f).impactLabel)
        assertEquals("0 % (up to +0 dB)", DspParams(impactPercent = 0f).impactLabel)
    }
}
