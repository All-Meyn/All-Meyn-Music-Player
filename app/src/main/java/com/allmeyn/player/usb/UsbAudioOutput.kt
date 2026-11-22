package com.allmeyn.player.usb

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import android.media.AudioDeviceInfo
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Media3 [AudioOutput] that writes to the custom USB driver instead of an AudioTrack.
 *
 * DefaultAudioSink creates and releases one of these on every seek and track change. Releasing
 * only pauses and clears the driver's queue; the DAC stays claimed and streaming, so seeks are
 * instant and Android never gets the DAC back mid-session.
 *
 * Called on the playback thread. If the driver was reconfigured by someone else (tone test,
 * driver turned off, DAC unplugged), this output reports itself stalled: DefaultAudioSink then
 * flushes and builds a fresh output, which [UsbAudioOutputProvider] routes to the right place.
 * (Throwing instead would become a player error if nothing had been written yet.)
 */
@UnstableApi
class UsbAudioOutput(
    private val driver: UsbAudioDriver,
    private val token: Int,
    private val sampleRate: Int,
    private val audioSessionId: Int,
    /**
     * Native DSD only (else 0): bytes at the start of this output that Media3 has faded in and
     * that must become DSD silence (see [silenceRamp]).
     */
    private val dsdRampBytes: Long = 0,
) : AudioOutput {

    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
    private var bytesWritten = 0L
    private var notifiedAdvancing = false
    private var lastPositionUs = 0L
    private var released = false
    private var writeFailed = false

    private val isCurrent get() = !released && driver.currentToken() == token && driver.isActive

    override fun play() {
        if (!isCurrent) return
        NativeUsb.play()
        if (!notifiedAdvancing) {
            notifiedAdvancing = true
            val now = System.currentTimeMillis()
            listeners.forEach { it.onPositionAdvancing(now) }
        }
    }

    override fun pause() {
        if (isCurrent) NativeUsb.pause()
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (!isCurrent) return false // isStalled() is now true: the sink rebuilds the output
        val position = buffer.position()
        if (bytesWritten < dsdRampBytes) silenceRamp(buffer, bytesWritten, dsdRampBytes)
        val consumed = NativeUsb.write(buffer, position, buffer.remaining())
        if (consumed < 0) {
            writeFailed = true
            return false
        }
        bytesWritten += consumed
        buffer.position(position + consumed)
        return !buffer.hasRemaining()
    }

    override fun flush() {
        if (isCurrent) NativeUsb.flush()
        lastPositionUs = 0
    }

    /** End of stream: the queue keeps draining; nothing else to do. */
    // Media3 calls stop() when it plays out the end of the queue (like AudioTrack.stop()).
    override fun stop() {
        if (isCurrent) NativeUsb.endOfStream()
    }

    override fun release() {
        if (released) return
        if (isCurrent) {
            NativeUsb.pause()
            NativeUsb.flush()
        }
        released = true
        listeners.forEach { it.onReleased() } // DefaultAudioSink counts pending releases
    }

    /** Bit-perfect: software volume would alter the samples, so it's ignored. */
    override fun setVolume(volume: Float) {}

    override fun isOffloadedPlayback() = false
    override fun getAudioSessionId() = audioSessionId
    override fun getSampleRate() = sampleRate
    override fun getBufferSizeInFrames() = NativeUsb.ringFrames().toLong()

    override fun getPositionUs(): Long {
        if (isCurrent) lastPositionUs = NativeUsb.playedFrames() * 1_000_000L / sampleRate
        return lastPositionUs
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    /** True once this output can no longer play (stale route or failed stream). */
    override fun isStalled() = !released && (writeFailed || !isCurrent)
    override fun addListener(listener: AudioOutput.Listener) { listeners.add(listener) }
    override fun removeListener(listener: AudioOutput.Listener) { listeners.remove(listener) }
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {}
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}
    override fun setOffloadEndOfStream() {}
    override fun attachAuxEffect(effectId: Int) {}
    override fun setAuxEffectSendLevel(level: Float) {}
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {}

    companion object {
        /** DefaultAudioSink's AUDIO_OUTPUT_VOLUME_RAMP_TIME_MS (Media3 1.9). */
        private const val MEDIA3_RAMP_US = 20_000L
        private const val DSD_IDLE: Byte = 0x69

        /**
         * Media3's DefaultAudioSink fades in the first 20 ms written to every new output (each
         * seek, skip and format change) by scaling the samples. For PCM that's a quiet fade-in;
         * native DSD travels as 16-bit PCM, so scaling its raw DSD bytes turns them into a burst
         * of noise. Those bytes can't be restored, so they are sent as DSD silence instead.
         */
        fun dsdRampBytes(sampleRate: Int, frameBytes: Int): Long =
            Util.durationUsToSampleCount(MEDIA3_RAMP_US, sampleRate) * frameBytes

        /**
         * Overwrites the part of [buffer] (from its position) that lies within the first
         * [rampBytes] bytes of the output, given [written] bytes already sent, with DSD silence.
         * Doesn't move the position. Writing the same bytes again (a retried write) is harmless.
         */
        internal fun silenceRamp(buffer: ByteBuffer, written: Long, rampBytes: Long) {
            val n = minOf(buffer.remaining().toLong(), rampBytes - written).toInt()
            val start = buffer.position()
            for (i in start until start + n) buffer.put(i, DSD_IDLE)
        }
    }
}
