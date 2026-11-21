package com.allmeyn.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {
    private fun red(c: Int) = (c shr 16) and 0xFF

    @Test
    fun flatColourStaysTheSame() {
        val c = (0xFF shl 24) or (40 shl 16) or (120 shl 8) or 200
        val px = IntArray(12 * 7) { c }
        repeat(3) { boxBlur(px, 12, 7, radius = 4) }
        assertTrue(px.all { it == c })
    }

    @Test
    fun brightDotSpreadsSymmetricallyAndStaysOpaque() {
        val w = 21; val h = 21
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        px[10 * w + 10] = 0xFFFFFFFF.toInt()
        boxBlur(px, w, h, radius = 2)
        // 255 spread over a 5x5 box: integer division per pass leaves 255 / 5 / 5 = 10 in each cell.
        assertEquals(10, red(px[10 * w + 10]))
        assertEquals(red(px[8 * w + 8]), red(px[12 * w + 12]))
        assertEquals(10, red(px[8 * w + 12]))
        assertEquals(0, red(px[7 * w + 10]))
        assertTrue(px.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun edgesAreClampedNotDarkened() {
        val c = 0xFF808080.toInt()
        val px = IntArray(5 * 3) { c }
        boxBlur(px, 5, 3, radius = 4) // radius wider than the image
        assertTrue(px.all { it == c })
    }
}
