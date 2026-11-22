package com.allmeyn.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropSpinTest {
    @Test
    fun turnsClockwiseOnceAMinute() {
        assertEquals(6f, advanceBackdropSpin(0f, 1_000), 1e-3f) // 6 degrees a second
        assertEquals(90f, advanceBackdropSpin(0f, BACKDROP_SPIN_PERIOD_MS / 4), 1e-3f)
    }

    @Test
    fun resumesFromWhereItStoppedAndWraps() {
        // Paused at 120 degrees: a later step continues from there, not from 0.
        assertEquals(126f, advanceBackdropSpin(120f, 1_000), 1e-3f)
        assertEquals(3f, advanceBackdropSpin(357f, 1_000), 1e-3f) // past a full turn: wraps to 0..360
        assertEquals(120f, advanceBackdropSpin(120f, 0), 0f)
        assertEquals(120f, advanceBackdropSpin(120f, -5), 0f) // a clock glitch never turns it back
    }
}
