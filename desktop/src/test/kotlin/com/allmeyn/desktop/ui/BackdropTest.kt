package com.allmeyn.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackdropTest {
    @Test fun spinTurnsOnceAMinuteAndWraps() {
        assertEquals(6f, advanceBackdropSpin(0f, 1_000))
        assertEquals(90f, advanceBackdropSpin(0f, 15_000))
        assertEquals(10f, advanceBackdropSpin(358f, 2_000), 1e-3f)
        assertEquals(42f, advanceBackdropSpin(42f, -5))
    }

    @Test fun boxBlurAveragesAndKeepsFlatAreas() {
        val w = 9
        val h = 1
        val px = IntArray(w) { if (it == 4) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        boxBlur(px, w, h, radius = 1)
        assertEquals(0x55, (px[4] shr 16) and 0xFF)  // 255 / 3 across the 3-wide box
        assertEquals(0x55, (px[3] shr 16) and 0xFF)
        assertEquals(0, (px[0] shr 16) and 0xFF)
        val flat = IntArray(16) { 0xFF336699.toInt() }
        boxBlur(flat, 4, 4, radius = 2)
        assertTrue(flat.all { it == 0xFF336699.toInt() })
    }
}
