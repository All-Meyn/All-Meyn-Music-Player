package com.allmeyn.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropSpinTest {
    @Test
    fun turnsClockwiseOnceAMinuteAndWrapsSmoothly() {
        assertEquals(0f, backdropSpinDegrees(0), 0f)
        assertEquals(90f, backdropSpinDegrees(BACKDROP_SPIN_PERIOD_MS / 4), 1e-3f)
        assertEquals(359.994f, backdropSpinDegrees(BACKDROP_SPIN_PERIOD_MS - 1), 1e-3f)
        assertEquals(0f, backdropSpinDegrees(BACKDROP_SPIN_PERIOD_MS), 0f) // a full turn = back at 0
        assertEquals(6f, backdropSpinDegrees(3 * BACKDROP_SPIN_PERIOD_MS + 1_000), 1e-3f) // 6°/s
    }
}
