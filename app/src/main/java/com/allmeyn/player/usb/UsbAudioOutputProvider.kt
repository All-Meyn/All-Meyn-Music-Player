package com.allmeyn.player.usb

import android.content.Context
import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Routes Media3's final PCM to the custom USB driver while it's enabled, and to a normal
 * AudioTrack otherwise (or for anything the driver can't take: offload, tunneling, >2 channels,
 * a sample rate the DAC refuses).
 */
@UnstableApi
class UsbAudioOutputProvider(
    context: Context,
    private val driver: UsbAudioDriver,
) : AudioOutputProvider {

    private val fallback = AudioTrackAudioOutputProvider.Builder(context).build()
    /** Each listener with the looper it registered on (DefaultAudioSink expects the playback thread). */
    private val listeners = CopyOnWriteArrayList<Pair<AudioOutputProvider.Listener, Handler>>()

    private val driverListener = object : UsbAudioDriver.Listener {
        override fun onDriverStateChanged(active: Boolean, reason: UsbAudioDriver.Reason) {
            // Lets DefaultAudioSink re-query format support after the driver turns on/off.
            listeners.forEach { (l, handler) -> handler.post { l.onFormatSupportChanged() } }
        }
    }

    init {
        driver.addListener(driverListener)
    }

    private fun usable(formatConfig: FormatConfig): Boolean {
        val f = formatConfig.format
        return driver.isActive && MimeTypes.AUDIO_RAW == f.sampleMimeType &&
            driver.supportsPcm(f.pcmEncoding, f.channelCount) && !formatConfig.enableTunneling
    }

    override fun getFormatSupport(formatConfig: FormatConfig): FormatSupport =
        if (usable(formatConfig)) {
            FormatSupport.Builder().setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()
        } else {
            fallback.getFormatSupport(formatConfig)
        }

    override fun getOutputConfig(formatConfig: FormatConfig): OutputConfig {
        if (!usable(formatConfig)) return fallback.getOutputConfig(formatConfig)
        val f = formatConfig.format
        val frameBytes = Util.getPcmFrameSize(f.pcmEncoding, f.channelCount)
        return OutputConfig.Builder()
            .setEncoding(f.pcmEncoding)
            .setSampleRate(f.sampleRate)
            .setChannelMask(if (f.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
            .setBufferSize(f.sampleRate * UsbAudioDriver.RING_MS / 1000 * frameBytes)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setAudioSessionId(formatConfig.audioSessionId)
            .build()
    }

    override fun getAudioOutput(config: OutputConfig): AudioOutput {
        val channels = Integer.bitCount(config.channelMask)
        val driverCanTake = !config.isOffload && !config.isTunneling && driver.supportsPcm(config.encoding, channels)
        if (driverCanTake && driver.isActive) {
            val r = driver.configureStream(config.encoding, config.sampleRate, channels)
            if (r.token != null) {
                driver.mediaRoute = "USB driver · ${config.sampleRate} Hz"
                return UsbAudioOutput(driver, r.token, config.sampleRate, config.audioSessionId)
            }
            // e.g. the DAC refused this sample rate: play through Android instead of failing.
            Log.w(TAG, "USB driver can't take ${config.sampleRate} Hz: ${r.error}; using AudioTrack")
            driver.mediaRoute = "Android (DAC refused ${config.sampleRate} Hz)"
            return DopGuardOutput.wrapIfNeeded(fallback.getAudioOutput(config), config.encoding, config.sampleRate, channels)
        }
        driver.mediaRoute = "Android AudioTrack"
        val out = DopGuardOutput.wrapIfNeeded(fallback.getAudioOutput(config), config.encoding, config.sampleRate, channels)
        // If the driver turns on later, hand this stream over to it.
        return if (driverCanTake) ReroutingFallbackOutput(out, driver) else out
    }

    /**
     * Android output used while the driver is off. Once the driver becomes active it stops
     * accepting audio and reports itself stalled, so DefaultAudioSink rebuilds the output, which
     * then goes to the driver. Works whatever order replug / auto-enable / play happen in.
     */
    private class ReroutingFallbackOutput(output: AudioOutput, private val driver: UsbAudioDriver) :
        ForwardingAudioOutput(output) {
        private var rerouting = false

        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            if (rerouting || driver.isActive) {
                rerouting = true
                return false
            }
            return super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        }

        override fun isStalled() = rerouting || super.isStalled()
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {
        listeners.add(listener to Handler(Looper.myLooper() ?: Looper.getMainLooper()))
        fallback.addListener(listener)
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        listeners.removeAll { it.first === listener }
        fallback.removeListener(listener)
    }

    override fun setClock(clock: Clock) = fallback.setClock(clock)

    override fun release() {
        driver.removeListener(driverListener)
        fallback.release()
    }

    private companion object {
        const val TAG = "UsbAudioOutputProvider"
    }
}
