package com.allmeyn.player.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.allmeyn.player.plugin.PcmFormat
import com.allmeyn.player.plugin.PluginHost
import java.nio.ByteBuffer

/**
 * Wraps the real AudioSink and taps every decoded PCM buffer for [PluginHost].
 *
 * Why a sink wrapper instead of an AudioProcessor: Media3's DefaultAudioSink skips custom
 * AudioProcessors for high-resolution PCM when float output is enabled, so a processor would miss
 * 24/32-bit files. Tapping handleBuffer() sees every PCM stream in its native decoded format.
 *
 * Runs on the playback thread.
 */
@UnstableApi
class CaptureAudioSink(
    sink: AudioSink,
    private val onPcmConfigured: (format: PcmFormat) -> Unit,
) : ForwardingAudioSink(sink) {

    private var isPcm = false

    // handleBuffer() is called repeatedly with the same buffer until the sink accepts it all,
    // so remember what we've already delivered to avoid double-capturing.
    private var lastBuffer: ByteBuffer? = null
    private var lastPresentationTimeUs = C.TIME_UNSET

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        isPcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType
        if (isPcm) {
            val format = PcmFormat(
                encoding = inputFormat.pcmEncoding,
                sampleRate = inputFormat.sampleRate,
                channelCount = inputFormat.channelCount,
                bytesPerFrame = Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount),
            )
            // Must happen before the AudioTrack is created inside super.configure()/handleBuffer()
            // so a USB DAC can be switched to the matching bit-perfect mode first.
            onPcmConfigured(format)
            PluginHost.dispatchConfigure(format)
        }
        resetTap()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (isPcm && (buffer !== lastBuffer || presentationTimeUs != lastPresentationTimeUs)) {
            lastBuffer = buffer
            lastPresentationTimeUs = presentationTimeUs
            PluginHost.dispatchBuffer(buffer, presentationTimeUs)
        }
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (consumed) resetTap()
        return consumed
    }

    override fun flush() {
        resetTap()
        PluginHost.dispatchFlush()
        super.flush()
    }

    override fun reset() {
        resetTap()
        super.reset()
    }

    override fun release() {
        resetTap()
        PluginHost.dispatchRelease()
        super.release()
    }

    private fun resetTap() {
        lastBuffer = null
        lastPresentationTimeUs = C.TIME_UNSET
    }
}
