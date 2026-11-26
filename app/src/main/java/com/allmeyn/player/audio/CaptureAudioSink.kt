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
import com.allmeyn.player.dsp.AirSource
import com.allmeyn.player.dsp.DspMeters
import com.allmeyn.player.dsp.ConvKind
import com.allmeyn.player.dsp.ConvolutionFiles
import com.allmeyn.player.dsp.DspParams
import com.allmeyn.player.dsp.DspSettings
import com.allmeyn.player.dsp.NativeDsp
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps the real AudioSink and taps every decoded PCM buffer for [PluginHost], then applies
 * ReplayGain and the DSP chain (dsp/), in that order.
 *
 * DSP: float audio (FFmpeg output) is processed in place. Integer audio (WAV, APE, WavPack) is
 * converted to float while the DSP is active, and the real sink is configured for float then; with
 * the DSP off it passes through untouched (bit-perfect through the USB driver). DoP / native DSD is
 * never processed (with the DSP on, DSD is decoded to PCM instead: see PlaybackService).
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

    // ---- DSP ----
    private var sourceFormat: Format? = null
    private var savedBufferSize = 0
    private var savedOutputChannels: IntArray? = null
    private var realPcm = false            // audio the DSP may process: PCM, 1-2 ch, not DoP / native DSD
    private var floatSink = false          // the real sink is configured for float (converted int input)
    private var chain = 0L
    private var chainRate = 0
    private var chainChannels = 0
    private var paramsVersion = -1
    private var sourceVersion = -1
    /** The convolution filter (and gain) the chain was given; rebuilt when it or the chain changes. */
    private var convKey: Any? = null
    private var convertBuffer: ByteBuffer? = null  // int -> float conversion (direct, little-endian)
    private var pendingOut: ByteBuffer? = null     // converted buffer still being fed to the sink
    private val meterValues = FloatArray(NativeDsp.METER_COUNT)

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
        sourceFormat = inputFormat
        savedBufferSize = specifiedBufferSize
        savedOutputChannels = outputChannels
        realPcm = isPcm && inputFormat.channelCount in 1..2 &&
            inputFormat.pcmEncoding in DSP_ENCODINGS &&
            !(DecodeRegistry.dopActive && inputFormat.pcmEncoding == C.ENCODING_PCM_24BIT) &&
            !DecodeRegistry.isNativeDsd(inputFormat.pcmEncoding, inputFormat.channelCount, inputFormat.sampleRate)
        pendingOut = null
        floatSink = realPcm && encoding != C.ENCODING_PCM_FLOAT && DspSettings.live.processes
        if (!realPcm) releaseChain()
        super.configure(if (floatSink) asFloat(inputFormat) else inputFormat, specifiedBufferSize, outputChannels)
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
            applyDsp(buffer)
        }
        // A converted (float) copy is fed until the sink has taken all of it; the original buffer
        // counts as consumed only then.
        pendingOut?.let { out ->
            if (!super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)) return false
            pendingOut = null
            buffer.position(buffer.limit())
            resetTap()
            return true
        }
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (consumed) resetTap()
        return consumed
    }

    /** Runs the DSP chain on a new buffer (in place for float, into [pendingOut] for int). */
    private fun applyDsp(buffer: ByteBuffer) {
        val params = DspSettings.live
        val processing = realPcm && params.processes
        val src = sourceFormat ?: return
        // DSP switched on/off for integer audio: reconfigure the real sink at this buffer boundary.
        if (realPcm && encoding != C.ENCODING_PCM_FLOAT && processing != floatSink) {
            floatSink = processing
            super.configure(if (processing) asFloat(src) else src, savedBufferSize, savedOutputChannels)
        }
        if (!processing) {
            if (chain != 0L) DspSettings.meters = null
            return
        }
        if (!ensureChain(src.sampleRate, src.channelCount)) return
        if (paramsVersion != DspSettings.version) {
            paramsVersion = DspSettings.version
            NativeDsp.setParams(chain, params.toNativeValues(), params.toNativeOrder())
            // Convolution EQ: build the filter for this chain when it changed (a few ms, between
            // buffers; the chain swaps it in without waiting).
            val key = if (params.convActive) params.conv to params.convGainDb else null
            if (key != convKey) {
                convKey = key
                giveConvolution(chain, params)
            }
        }
        if (sourceVersion != AirSource.version) {
            sourceVersion = AirSource.version
            val hint = AirSource.hint
            NativeDsp.setSource(chain, hint.lossy, hint.priorCutoffHz)
        }
        val target = if (floatSink) toFloat(buffer).also { pendingOut = it } else buffer
        val frames = target.remaining() / (4 * src.channelCount)
        if (frames > 0 && !NativeDsp.process(chain, target, target.position(), frames, meterValues)) {
            // Not a direct buffer (rare): process a direct copy and copy it back.
            val copy = directFloat(target.remaining())
            copy.put(target.duplicate()).flip()
            NativeDsp.process(chain, copy, 0, frames, meterValues)
            target.duplicate().put(copy)
        }
        DspSettings.meters = DspMeters.from(meterValues)
    }

    private fun ensureChain(rate: Int, channels: Int): Boolean {
        if (chain != 0L && chainRate == rate && chainChannels == channels) return true
        releaseChain()
        chain = NativeDsp.create(rate, channels)
        chainRate = rate
        chainChannels = channels
        paramsVersion = -1
        convKey = null
        sourceVersion = -1
        return chain != 0L
    }

    private fun giveConvolution(chain: Long, params: DspParams) {
        val f = params.conv
        when {
            !params.convActive -> NativeDsp.setConvolution(chain, null, null, 0f, null, null, 0f)
            f.kind == ConvKind.GRAPHIC -> NativeDsp.setConvolution(
                chain, null, null, 0f,
                FloatArray(f.graphic.size) { f.graphic[it].hz }, FloatArray(f.graphic.size) { f.graphic[it].db }, params.convGainDb,
            )
            else -> {
                val ir = ConvolutionFiles.load(f.irFile)
                if (ir == null) NativeDsp.setConvolution(chain, null, null, 0f, null, null, 0f)
                else NativeDsp.setConvolution(chain, ir.channels[0], ir.channels.getOrNull(1), ir.rate.toFloat(), null, null, params.convGainDb)
            }
        }
    }

    private fun releaseChain() {
        if (chain != 0L) NativeDsp.destroy(chain)
        chain = 0L
        DspSettings.meters = null
    }

    private fun directFloat(bytes: Int): ByteBuffer {
        val b = convertBuffer?.takeIf { it.capacity() >= bytes }
            ?: ByteBuffer.allocateDirect(maxOf(bytes, 64 * 1024)).order(ByteOrder.nativeOrder()).also { convertBuffer = it }
        b.clear()
        return b
    }

    /** 16/24/32-bit little-endian integer PCM -> float, into the conversion buffer. */
    private fun toFloat(buffer: ByteBuffer): ByteBuffer {
        val inBytes = Util.getPcmFrameSize(encoding, 1)
        val samples = buffer.remaining() / inBytes
        val out = directFloat(samples * 4)
        val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var i = src.position()
        repeat(samples) {
            val v = when (encoding) {
                C.ENCODING_PCM_16BIT -> src.getShort(i) / 32768f
                C.ENCODING_PCM_24BIT -> ((src.get(i).toInt() and 0xFF) or ((src.get(i + 1).toInt() and 0xFF) shl 8) or
                    (src.get(i + 2).toInt() shl 16)) / 8_388_608f
                else -> src.getInt(i) / 2_147_483_648f
            }
            out.putFloat(v)
            i += inBytes
        }
        out.flip()
        return out
    }

    override fun flush() {
        resetTap()
        pendingOut = null
        if (chain != 0L) NativeDsp.reset(chain)
        PluginHost.dispatchFlush()
        super.flush()
    }

    override fun reset() {
        resetTap()
        pendingOut = null
        releaseChain()
        super.reset()
    }

    override fun release() {
        resetTap()
        pendingOut = null
        releaseChain()
        PluginHost.dispatchRelease()
        super.release()
    }

    private fun resetTap() {
        lastBuffer = null
        lastPresentationTimeUs = C.TIME_UNSET
    }

    private companion object {
        val DSP_ENCODINGS = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        fun asFloat(f: Format): Format = f.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
    }
}
