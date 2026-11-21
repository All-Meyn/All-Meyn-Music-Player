package com.allmeyn.player.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspParamsTest {
    @Test fun clampsAndFixesOrder() {
        val p = DspParams(expansionPercent = -80f, transitionDb = 5f, impactReleaseMs = 1f, volumeDb = 30f,
            order = listOf(DspProcessor.IMPACT, DspProcessor.IMPACT)).sanitized()
        assertEquals(-50f, p.expansionPercent)
        assertEquals(0f, p.transitionDb)
        assertEquals(25f, p.impactReleaseMs)
        assertEquals(10f, p.volumeDb)
        assertEquals(listOf(DspProcessor.IMPACT, DspProcessor.COMPANDER, DspProcessor.BASS), p.order)
    }

    @Test fun processesOnlyWhenOnAndSomethingToDo() {
        assertFalse(DspParams(enabled = false).processes)
        assertFalse(DspParams(enabled = true, companderOn = false, bassOn = false, impactOn = false).processes)
        assertTrue(DspParams(enabled = true, companderOn = false, volumeDb = -3f).processes)
        assertTrue(DspParams(enabled = true).processes) // compressor/expander on by default
    }

    @Test fun nativeLayout() {
        val p = DspParams(companderOn = true, expansionPercent = -20f, transitionDb = -25f, hfTransitionDb = 2f,
            bassOn = false, bassPercent = 40f, bassTransitionDb = -18f, impactOn = true, impactPercent = 75f,
            impactReleaseMs = 300f, volumeDb = -2f, softClip = true,
            order = listOf(DspProcessor.BASS, DspProcessor.IMPACT, DspProcessor.COMPANDER))
        assertArrayEquals(floatArrayOf(1f, -20f, -25f, 2f, 0f, 40f, -18f, 1f, 75f, 300f, -2f, 1f), p.toNativeValues(), 0f)
        assertArrayEquals(intArrayOf(1, 2, 0), p.toNativeOrder())
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
    }
}
