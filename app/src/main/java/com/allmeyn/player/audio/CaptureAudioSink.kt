package com.allmeyn.player.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.allmeyn.player.decode.DecodeRegistry
import com.allmeyn.player.playback.ReplayGain
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
    /** True while output goes to the bit-perfect USB driver: no ReplayGain then. */
    private val bitPerfectOutput: () -> Boolean = { false },
) : ForwardingAudioSink(sink) {

    private var isPcm = false

    // ReplayGain of the song being configured. Media3 configures the sink for each new song right
    // before that song's first buffer (FFmpeg rebuilds its decoder per song), so the gain switches
    // exactly at the join. Formats we decode ourselves carry no tags here; DecodingDataSource
    // scales those.
    private var gains = ReplayGain.Gains()
    private var encoding = C.ENCODING_INVALID

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
                isDop = DecodeRegistry.dopActive && inputFormat.pcmEncoding == C.ENCODING_PCM_24BIT,
                isNativeDsd = DecodeRegistry.isNativeDsd(inputFormat.pcmEncoding, inputFormat.channelCount, inputFormat.sampleRate),
            )
            // Must happen before the AudioTrack is created inside super.configure()/handleBuffer()
            // so a USB DAC can be switched to the matching bit-perfect mode first.
            onPcmConfigured(format)
            PluginHost.dispatchConfigure(format)
            encoding = inputFormat.pcmEncoding
            gains = if (format.isDop || format.isNativeDsd) ReplayGain.Gains() else ReplayGain.fromMetadata(inputFormat.metadata)
        }
        if (!isPcm) gains = ReplayGain.Gains()
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
            // After the plugins (they see the audio as decoded), once per buffer.
            if (!gains.isEmpty && !bitPerfectOutput()) {
                ReplayGain.scale(buffer, encoding, ReplayGain.volumeFor(ReplayGain.pick(gains, ReplayGain.liveMode)))
            }
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
