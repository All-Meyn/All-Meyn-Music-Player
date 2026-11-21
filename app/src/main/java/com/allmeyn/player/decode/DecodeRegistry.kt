package com.allmeyn.player.decode

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.allmeyn.player.playback.ReplayGain
import java.util.concurrent.ConcurrentHashMap

/**
 * How decoded audio may leave the decoder. DSD: as native DSD up to [nativeMaxRate] Hz of DSD
 * (0 = not at all), otherwise as DoP if [dop], otherwise as PCM. [replayGain]: scale PCM output
 * by the file's ReplayGain (only when it's going to an Android output; OFF otherwise).
 */
@OptIn(UnstableApi::class) // ReplayGain.Mode
data class DsdOutput(
    val dop: Boolean,
    val nativeMaxRate: Int = 0,
    val replayGain: ReplayGain.Mode = ReplayGain.Mode.OFF,
)

/** What the decoders are doing, for the UI, the plugin tap, the output and the playback service. */
object DecodeRegistry {
    /** DSD output choice per file, fixed from the first (position 0) open until the item is reloaded. */
    private val outputByUri = ConcurrentHashMap<Uri, DsdOutput>()
    private val dsdUris = ConcurrentHashMap.newKeySet<Uri>()

    /** Frame rates (DSD rate / 32) of native DSD streams the decoder has produced. */
    private val nativeFrameRates = ConcurrentHashMap.newKeySet<Int>()

    /** Description of the most recently opened decoded file, e.g. "DSF DSD64 → DoP 176.4 kHz". */
    @Volatile var lastDescription: String? = null
        private set

    /** True while the most recently opened decoded stream is DoP (not real PCM). */
    @Volatile var dopActive: Boolean = false
        private set

    /** Last decoder failure (shown in the UI), cleared when a file opens fine. */
    @Volatile var lastError: String? = null
        internal set

    /** True while the most recently opened decoded stream is native DSD. */
    @Volatile var nativeActive: Boolean = false
        private set

    fun outputFor(uri: Uri): DsdOutput? = outputByUri[uri]

    /**
     * Whether a Media3 PCM stream is native DSD from our decoder: 16-bit, 4 channels (two per DSD
     * channel), at a frame rate our decoder has produced native DSD at. Media3's Format can't
     * carry a marker, and with gapless preloading "the last file opened" may already be the next
     * song, so this is decided from the stream's shape. (A real 4-channel 16-bit file at exactly
     * such a rate, played in the same session as native DSD, would be mistaken for DSD.)
     */
    fun isNativeDsd(pcmEncoding: Int, channels: Int, sampleRate: Int) =
        pcmEncoding == C.ENCODING_PCM_16BIT && channels == 4 && sampleRate in nativeFrameRates

    fun isDsd(uri: Uri) = uri in dsdUris

    internal fun onOpened(
        uri: Uri, isDsd: Boolean, isDop: Boolean, isNative: Boolean, nativeFrameRate: Int,
        output: DsdOutput, description: String, fromStart: Boolean,
    ) {
        if (fromStart) outputByUri[uri] = output
        if (isDsd) dsdUris += uri
        if (isNative) nativeFrameRates += nativeFrameRate
        lastDescription = description
        dopActive = isDop
        nativeActive = isNative
        lastError = null
    }

    /** Forget DSD output choices so the next load decides again (e.g. after the driver turns on/off). */
    fun resetModes() = outputByUri.clear()

    internal fun onPlainFile() {
        lastDescription = null
        dopActive = false
        nativeActive = false
    }
}
