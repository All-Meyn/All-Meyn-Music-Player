package com.allmeyn.player.usb

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
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
) : AudioOutput {

    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
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
        val consumed = NativeUsb.write(buffer, position, buffer.remaining())
        if (consumed < 0) {
            writeFailed = true
            return false
        }
        buffer.position(position + consumed)
        return !buffer.hasRemaining()
    }

    override fun flush() {
        if (isCurrent) NativeUsb.flush()
        lastPositionUs = 0
    }

    /** End of stream: the queue keeps draining; nothing else to do. */
    override fun stop() {}

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
}
