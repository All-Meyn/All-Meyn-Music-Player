package com.allmeyn.player.decode

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Rf64Test {
    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun tag(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    @Test fun dopHeader() {
        // DSD64 as DoP: 24-bit stereo at 176.4 kHz, 10 minutes (> 4 GB would also be fine)
        val frames = 176_400L * 600
        val h = Rf64.header(176_400, 2, DecodedFormat.S24, frames)
        assertEquals(Rf64.HEADER_SIZE, h.size)
        assertEquals("RF64", tag(h, 0)); assertEquals("WAVE", tag(h, 8)); assertEquals("ds64", tag(h, 12))
        assertEquals("fmt ", tag(h, 48)); assertEquals("data", tag(h, 72))
        val b = le(h)
        assertEquals(-1, b.getInt(4))
        assertEquals(28, b.getInt(16))
        assertEquals(frames * 6, b.getLong(28))           // data size
        assertEquals(72 + frames * 6, b.getLong(20))      // RIFF size
        assertEquals(frames, b.getLong(36))
        assertEquals(1.toShort(), b.getShort(56))         // PCM
        assertEquals(2.toShort(), b.getShort(58))
        assertEquals(176_400, b.getInt(60))
        assertEquals(176_400 * 6, b.getInt(64))
        assertEquals(6.toShort(), b.getShort(68))
        assertEquals(24.toShort(), b.getShort(70))
        assertEquals(-1, b.getInt(76))
    }

    @Test fun floatHeaderOver4GB() {
        val frames = 176_400L * 3600 * 2 // 2 hours of float stereo: ~5 GB
        val b = le(Rf64.header(176_400, 2, DecodedFormat.F32, frames))
        assertEquals(3.toShort(), b.getShort(56))         // IEEE float
        assertEquals(32.toShort(), b.getShort(70))
        assertEquals(frames * 8, b.getLong(28))
    }
}
