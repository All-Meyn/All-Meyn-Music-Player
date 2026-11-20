package com.allmeyn.player.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import com.allmeyn.player.usb.UsbDacManager

/**
 * - Prefers the FFmpeg audio decoder (FLAC, ALAC, Opus, Vorbis, MP3, AAC, AC-3, DTS, TrueHD, ...)
 *   over the phone's MediaCodec, so decoding is consistent across devices and high-res isn't
 *   silently truncated by vendor decoders. Falls back to MediaCodec for anything FFmpeg can't do.
 * - Wraps the audio sink with [CaptureAudioSink] so plugins see every buffer before output.
 */
@UnstableApi
class AllMeynRenderersFactory(
    context: Context,
    private val usbDac: UsbDacManager,
) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        // Keeps 24/32-bit sources at full resolution instead of truncating them to 16-bit.
        setEnableAudioFloatOutput(FLOAT_OUTPUT)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            ?: return null
        return CaptureAudioSink(sink, usbDac::onPcmConfigured)
    }

    companion object {
        const val FLOAT_OUTPUT = true
    }
}
