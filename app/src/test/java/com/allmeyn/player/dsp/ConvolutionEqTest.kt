package com.allmeyn.player.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class ConvolutionEqTest {
    /** A WAV file: [format] 1 = PCM, 3 = float; [samples] interleaved. */
    private fun wav(rate: Int, channels: Int, bits: Int, format: Int, samples: List<Double>, extensible: Boolean = false): ByteArray {
        val bytesPer = bits / 8
        val data = ByteBuffer.allocate(samples.size * bytesPer).order(ByteOrder.LITTLE_ENDIAN)
        for (v in samples) when {
            format == 3 && bits == 32 -> data.putFloat(v.toFloat())
            format == 3 && bits == 64 -> data.putDouble(v)
            bits == 16 -> data.putShort(Math.round(v * 32767).toInt().toShort())
            bits == 24 -> { val i = Math.round(v * 8388607).toInt(); data.put(i.toByte()); data.put((i shr 8).toByte()); data.put((i shr 16).toByte()) }
            else -> data.putInt(Math.round(v * 2147483647.0).toInt())
        }
        val fmtLen = if (extensible) 40 else 16
        val b = ByteBuffer.allocate(12 + 8 + fmtLen + 8 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(b.capacity() - 8).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(fmtLen)
        b.putShort((if (extensible) 0xFFFE else format).toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bytesPer).putShort((channels * bytesPer).toShort()).putShort(bits.toShort())
        if (extensible) {
            b.putShort(22).putShort(bits.toShort()).putInt(0)
            b.putShort(format.toShort()).put(ByteArray(14))  // sub-format GUID: its first two bytes are the format
        }
        b.put("data".toByteArray()).putInt(data.capacity()).put(data.array())
        return b.array()
    }

    @Test fun readsPcm16Mono() {
        val ir = WavReader.read(wav(48000, 1, 16, 1, listOf(0.5, -0.25, 0.0)))
        assertEquals(48000, ir.rate)
        assertEquals(1, ir.channels.size)
        assertArrayEquals(floatArrayOf(0.5f, -0.25f, 0f), ir.channels[0], 1e-4f)
    }

    @Test fun readsPcm24Stereo() {
        val ir = WavReader.read(wav(44100, 2, 24, 1, listOf(0.5, -0.5, 0.25, -0.75)))
        assertEquals(2, ir.channels.size)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f), ir.channels[0], 1e-6f)
        assertArrayEquals(floatArrayOf(-0.5f, -0.75f), ir.channels[1], 1e-6f)
    }

    @Test fun readsFloatAndExtensible() {
        assertArrayEquals(floatArrayOf(0.125f, -1.5f), WavReader.read(wav(96000, 1, 32, 3, listOf(0.125, -1.5))).channels[0], 0f)
        assertArrayEquals(floatArrayOf(0.125f, -1.5f), WavReader.read(wav(96000, 1, 64, 3, listOf(0.125, -1.5))).channels[0], 0f)
        assertArrayEquals(floatArrayOf(0.5f), WavReader.read(wav(48000, 1, 32, 3, listOf(0.5), extensible = true)).channels[0], 0f)
        assertArrayEquals(floatArrayOf(0.5f), WavReader.read(wav(48000, 1, 32, 1, listOf(0.5), extensible = true)).channels[0], 1e-6f)
    }

    @Test fun keepsTheFirstTwoChannels() {
        val ir = WavReader.read(wav(48000, 3, 16, 1, listOf(0.1, 0.2, 0.3)))
        assertEquals(2, ir.channels.size)
        assertEquals(0.2f, ir.channels[1][0], 1e-4f)
    }

    @Test fun refusesWhatItCannotRead() {
        for (bad in listOf(ByteArray(8), "RIFF....WAVEjunk".toByteArray(), wav(48000, 1, 8, 1, emptyList()))) {
            try { WavReader.read(bad); fail("read ${bad.size} bytes") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun impulseResponseOfAUnitImpulseIsFlat() {
        val ir = WavReader.read(wav(48000, 1, 32, 3, listOf(1.0, 0.0, 0.0, 0.0)))
        val db = ir.responseDb(floatArrayOf(20f, 1000f, 20000f), gainDb = -3f)
        db.forEach { assertEquals(-3f, it, 0.01f) }
    }

    @Test fun graphicEqParsesAndRoundTrips() {
        val pts = GraphicEqParser.parse("# comment\nGraphicEQ: 20 -1.5; 1000 0; 20000 4,5")
        assertEquals(listOf(GraphicPoint(20f, -1.5f), GraphicPoint(1000f, 0f), GraphicPoint(20000f, 4.5f)), pts)
        assertEquals(pts, GraphicEqParser.parse(GraphicEqParser.toText(pts)))
        // Between points: interpolated on log frequency.
        val mid = GraphicEqParser.responseDb(pts, floatArrayOf(Math.sqrt(20.0 * 1000.0).toFloat()))[0]
        assertEquals(-0.75f, mid, 0.01f)
        try { GraphicEqParser.parse("GraphicEQ: 20 1"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun graphicEqIsKeptSmall() {
        val text = "GraphicEQ: " + (1..2000).joinToString("; ") { "${it * 10} ${it % 7}" }
        val pts = GraphicEqParser.parse(text)
        assertEquals(GraphicEqParser.MAX_POINTS, pts.size)
        assertEquals(10f, pts.first().hz); assertEquals(20000f, pts.last().hz)
        assertTrue(GraphicEqParser.toText(pts).length < 8192)
    }

    @Test fun filesGoToTheRightEditor() {
        assertTrue(EqFiles.read("Preamp: -1 dB\nFilter 1: ON PK Fc 1000 Hz Gain 2 dB Q 1".toByteArray(), "X ParametricEQ.txt") is EqFile.Parametric)
        val g = EqFiles.read("GraphicEQ: 20 0; 20000 1".toByteArray(), "HD 650 GraphicEQ.txt")
        assertTrue(g is EqFile.Graphic); assertEquals("HD 650", (g as EqFile.Graphic).name)
        val w = EqFiles.read(wav(48000, 1, 16, 1, listOf(1.0)), "HD 650 minimum phase 48000Hz.wav")
        assertTrue(w is EqFile.Impulse); assertEquals("HD 650", (w as EqFile.Impulse).name)
        assertEquals("a_b_c.wav", EqFiles.storageName("a/b\\c"))
    }

    @Test fun importStoresTheWavAndSwitchesItOn() {
        val dir = Files.createTempDirectory("conv").toFile()
        try {
            ConvolutionFiles.init(dir)
            val r = importEqFile(wav(48000, 2, 16, 1, listOf(1.0, 0.5, 0.0, 0.0)), "Mine.wav", fromConvolution = false)
            val p = r.change!!(DspParams())
            assertEquals(ConvKind.IMPULSE, p.conv.kind)
            assertTrue(p.convActive)
            assertNotNull(r.eqNotes)  // "it went to Convolution EQ"
            assertTrue(File(dir, p.conv.irFile).isFile)
            val ir = ConvolutionFiles.load(p.conv.irFile)!!
            assertEquals(2, ir.channels.size)
            assertEquals(1f, p.toNativeValues().last())

            // Each import keeps its own copy (another output may use the first); prune drops the unused.
            val r2 = importEqFile(wav(48000, 1, 16, 1, listOf(1.0)), "Other.wav", fromConvolution = true)
            val p2 = r2.change!!(p)
            assertEquals(setOf(p.conv.irFile, p2.conv.irFile), dir.list()!!.toSet())
            ConvolutionFiles.prune(setOf(p2.conv.irFile))
            assertEquals(listOf(p2.conv.irFile), dir.list()!!.toList())

            // A different response with the same name doesn't replace the first; the same one is reused.
            val a = ConvolutionFiles.save("Same", wav(48000, 1, 16, 1, listOf(1.0)))
            val b = ConvolutionFiles.save("Same", wav(48000, 1, 16, 1, listOf(0.5)))
            assertEquals("Same.wav", a); assertEquals("Same (2).wav", b)
            assertEquals(a, ConvolutionFiles.save("Same", wav(48000, 1, 16, 1, listOf(1.0))))

            // A bad file changes nothing and says why in the section it came from.
            val bad = importEqFile("nonsense".toByteArray(), "x.txt", fromConvolution = true)
            assertEquals(null, bad.change); assertEquals(null, bad.eqNotes); assertNotNull(bad.convNotes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun convolutionNeedsAFilter() {
        assertFalse(DspParams(convOn = true).convActive)
        val p = DspParams(enabled = true, convOn = true, conv = ConvolutionFilter(ConvKind.GRAPHIC, "x", graphic = listOf(GraphicPoint(20f, 0f), GraphicPoint(2e4f, 1f))))
        assertTrue(p.convActive); assertTrue(p.processes)
        assertEquals(10f, p.copy(convGainDb = 99f).sanitized().convGainDb)
    }
}
