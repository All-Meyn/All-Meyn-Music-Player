package com.allmeyn.player.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OutputEqsTest {
    private val dac = OutputId("usb:FiiO K7", "USB: FiiO K7")
    private val bt = OutputId("bt:WH-1000XM5 [L\\R]", "Bluetooth: WH-1000XM5 [L\\R]")
    private val hd650 = EqProfile("HD 650", -6f, listOf(EqBand(EqFilterType.LOW_SHELF, 105f, 6f, 0.7f)))
    private val sony = EqProfile("Sony \\ odd = [name]", -3f, listOf(EqBand(EqFilterType.PEAK, 3000f, -2f, 2f)))
    private val graphic = ConvolutionFilter(ConvKind.GRAPHIC, "G", graphic = listOf(GraphicPoint(20f, 1f), GraphicPoint(20000f, -1f)), info = "GraphicEQ · 2 points")

    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("outs").toFile()
        try { block(File(dir, "output_eqs.txt")) } finally { dir.deleteRecursively() }
    }

    @Test fun eachOutputKeepsItsEq() = withFile { f ->
        val s = OutputEqStore(f)
        assertTrue(s.enabled)
        var p = DspParams(eqOn = true, eq = hd650, companderOn = false)
        p = s.switchTo(dac, p)                       // new output: keeps the EQ in use
        assertEquals(hd650, p.eq)
        p = s.switchTo(bt, p)                        // another new one: starts with the same
        p = p.copy(eq = sony, convOn = true, conv = graphic, convGainDb = -2f)
        s.remember(bt, p)
        p = s.switchTo(dac, p)                       // back to the DAC: its EQ, no convolution
        assertEquals(hd650, p.eq); assertFalse(p.convOn)
        p = s.switchTo(bt, p)
        assertEquals(sony.sanitized(), p.eq); assertEquals(graphic, p.conv); assertEquals(-2f, p.convGainDb)
        assertEquals(listOf(dac, bt), s.outputs())

        // Saved and read back the same (odd characters included).
        val again = OutputEqStore(f)
        assertEquals(listOf(dac, bt), again.outputs())
        assertEquals(sony.sanitized(), again.switchTo(bt, DspParams()).eq)
        assertEquals(graphic, again.switchTo(bt, DspParams()).conv)
        assertEquals(hd650, again.switchTo(dac, DspParams()).eq)
    }

    @Test fun otherSettingsAreLeftAlone() = withFile { f ->
        val s = OutputEqStore(f)
        s.remember(dac, DspParams(eqOn = true, eq = hd650, volumeDb = -3f))
        val p = s.switchTo(dac, DspParams(volumeDb = 2f, bassOn = true))
        assertEquals(2f, p.volumeDb); assertTrue(p.bassOn); assertEquals(hd650, p.eq)
    }

    @Test fun offMeansOneEqForAll() = withFile { f ->
        val s = OutputEqStore(f)
        s.remember(dac, DspParams(eqOn = true, eq = hd650))
        s.setEnabled(false)
        val p = DspParams(eq = sony)
        assertEquals(p, s.switchTo(dac, p))
        s.remember(bt, p)                            // not remembered while off
        assertEquals(listOf(dac), s.outputs())
        assertFalse(OutputEqStore(f).enabled)
    }

    @Test fun forgetAndFiles() = withFile { f ->
        val s = OutputEqStore(f)
        s.remember(dac, DspParams(conv = ConvolutionFilter(ConvKind.IMPULSE, "IR", irFile = "IR.wav")))
        s.remember(bt, DspParams(conv = graphic))
        assertEquals(setOf("IR.wav"), s.irFiles())
        s.forget(dac.key)
        assertEquals(listOf(bt), OutputEqStore(f).outputs())
        assertTrue(s.irFiles().isEmpty())
    }

    @Test fun aDamagedFileIsIgnored() = withFile { f ->
        f.writeText("[usb:x]\neqProfile=\\\nnonsense\n[\n")
        val s = OutputEqStore(f)
        s.outputs()  // no exception
        assertTrue(s.enabled)
    }
}
