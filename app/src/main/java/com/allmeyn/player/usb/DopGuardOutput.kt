package com.allmeyn.player.usb

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import com.allmeyn.player.decode.DecodeRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Safety net for Android (AudioTrack) outputs: if DoP-packed DSD ever reaches one, it would play
 * as loud noise, so this output detects the DoP marker pattern (alternating 0x05 / 0xFA in each
 * sample's top byte) and plays silence instead. Native DSD has no markers, so a native DSD stream
 * (recognised by its shape, see DecodeRegistry.isNativeDsd) is silenced from the start. Normally
 * DSD is reloaded as PCM before that can happen; this covers the gaps (e.g. the DAC refusing the
 * rate).
 */
@UnstableApi
class DopGuardOutput private constructor(
    output: AudioOutput,
    private val encoding: Int,
    private val channels: Int,
    startMuted: Boolean = false,
) : ForwardingAudioOutput(output) {

    private var muted = startMuted
    private var zeros: ByteBuffer = ByteBuffer.allocateDirect(0)

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (!muted && looksLikeDop(buffer)) muted = true
        if (!muted) return super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        // Feed the same amount of silence so playback timing carries on.
        val len = buffer.remaining()
        if (zeros.capacity() < len) zeros = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder())
        zeros.clear().limit(len)
        val done = super.write(zeros, encodedAccessUnitCount, presentationTimeUs)
        buffer.position(buffer.position() + (len - zeros.remaining()))
        return done
    }

    private fun looksLikeDop(b: ByteBuffer): Boolean {
        val sampleBytes = if (encoding == C.ENCODING_PCM_24BIT) 3 else 4
        val frameBytes = sampleBytes * channels
        val frames = minOf(b.remaining() / frameBytes, CHECK_FRAMES)
        if (frames < 8) return false
        var prev = -1
        for (f in 0 until frames) {
            val at = b.position() + f * frameBytes
            val top = when (encoding) {
                C.ENCODING_PCM_24BIT -> b.get(at + 2).toInt() and 0xFF
                C.ENCODING_PCM_32BIT -> b.get(at + 3).toInt() and 0xFF
                else -> { // float: assemble little-endian bits without touching the buffer's byte order
                    val bits = (b.get(at).toInt() and 0xFF) or ((b.get(at + 1).toInt() and 0xFF) shl 8) or
                        ((b.get(at + 2).toInt() and 0xFF) shl 16) or (b.get(at + 3).toInt() shl 24)
                    ((Float.fromBits(bits) * 8_388_608f).roundToInt() shr 16) and 0xFF
                }
            }
            if (top != 0x05 && top != 0xFA) return false
            if (top == prev) return false
            prev = top
        }
        return true
    }

    companion object {
        private const val CHECK_FRAMES = 32
        private val DOP_RATES = setOf(176_400, 192_000, 352_800, 384_000, 705_600, 768_000)

        fun wrapIfNeeded(output: AudioOutput, encoding: Int, sampleRate: Int, channels: Int): AudioOutput {
            if (DecodeRegistry.isNativeDsd(encoding, channels, sampleRate)) {
                return DopGuardOutput(output, encoding, channels, startMuted = true)
            }
            val maybeDop = sampleRate in DOP_RATES && channels in 1..2 && encoding in setOf(
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT,
            )
            return if (maybeDop) DopGuardOutput(output, encoding, channels) else output
        }
    }
}
