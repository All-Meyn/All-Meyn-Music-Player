package com.allmeyn.player.usb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.PcmAudioUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Media3's start-of-output fade must not reach the DAC as scaled (noisy) native DSD. */
@OptIn(UnstableApi::class)
class DsdRampTest {
    private val rate = 2_822_400 / 32 // DSD64 native frame rate
    private val frameBytes = 8         // 4 x 16-bit
    private val rampFrames = Util.durationUsToSampleCount(20_000, rate).toInt()
    private val rampBytes = UsbAudioOutput.dsdRampBytes(rate, frameBytes)

    private fun dsd(frames: Int): ByteArray = Random(7).nextBytes(frames * frameBytes)

    /** Media3's ramp over the stream, then our fix, writing in [chunk]-byte pieces. */
    private fun sendThroughRampAndFix(src: ByteArray, chunk: Int): ByteArray {
        val input = ByteBuffer.allocateDirect(src.size).order(ByteOrder.nativeOrder()).put(src).flip() as ByteBuffer
        val ramped = PcmAudioUtil.rampUpVolume(input, C.ENCODING_PCM_16BIT, frameBytes, 0, rampFrames)
        val out = ByteArray(src.size)
        var written = 0L
        while (ramped.hasRemaining()) {
            val piece = ramped.duplicate().apply { limit(minOf(limit(), position() + chunk)) }
            if (written < rampBytes) UsbAudioOutput.silenceRamp(piece, written, rampBytes)
            if (written < rampBytes) UsbAudioOutput.silenceRamp(piece, written, rampBytes) // a retried write
            val n = piece.remaining()
            piece.get(out, written.toInt(), n)
            written += n
            ramped.position(ramped.position() + n)
        }
        return out
    }

    @Test
    fun media3RampAltersRawDsd() {
        val src = dsd(rampFrames * 2)
        val input = ByteBuffer.allocateDirect(src.size).order(ByteOrder.nativeOrder()).put(src).flip() as ByteBuffer
        val ramped = PcmAudioUtil.rampUpVolume(input, C.ENCODING_PCM_16BIT, frameBytes, 0, rampFrames)
        val bytes = ByteArray(src.size).also { ramped.get(it) }
        assertFalse(bytes.copyOf(rampBytes.toInt()).contentEquals(src.copyOf(rampBytes.toInt())))
    }

    @Test
    fun rampBecomesDsdSilenceAndTheRestIsBitExact() {
        for (chunk in listOf(frameBytes * 11, 4096, 64 * 1024)) {
            val src = dsd(rampFrames * 3 + 5)
            val out = sendThroughRampAndFix(src, chunk)
            for (i in 0 until rampBytes.toInt()) assertEquals("byte $i, chunk $chunk", 0x69.toByte(), out[i])
            for (i in rampBytes.toInt() until src.size) assertEquals("byte $i, chunk $chunk", src[i], out[i])
        }
    }

    @Test
    fun rampLengthIs20ms() {
        assertEquals(1764L * frameBytes, rampBytes)
    }
}
