package com.allmeyn.player.dsp

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * What the file says about the song being decoded, for Air: lossy or not, and for lossy codecs a
 * first guess at the encoder's treble cutoff from the bitrate. The DSP chain's own cutoff
 * detection confirms or corrects it from the sound (and catches lossless files made from lossy
 * ones). Set from the player when a song's audio format changes; read by the audio thread.
 */
object AirSource {
    data class Hint(val lossy: Boolean, val priorCutoffHz: Float)

    @Volatile var hint = Hint(lossy = false, priorCutoffHz = 0f)
        private set

    /** Bumped on every new song, so the audio thread passes it on once. */
    @Volatile var version = 0
        private set

    fun set(format: Format) {
        hint = hintFor(format.sampleMimeType, bitrateOf(format), format.channelCount)
        version++
    }

    private val LOSSY = setOf(
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2,
        MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS,
        MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4,
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_AMR_NB, MimeTypes.AUDIO_AMR_WB,
    )

    private fun bitrateOf(f: Format): Int = when {
        f.averageBitrate != Format.NO_VALUE -> f.averageBitrate
        else -> f.bitrate
    }

    /**
     * Typical encoder lowpass by bitrate (stereo; mono counts double). Rough on purpose: the
     * detector corrects it. Opus is full-band at usable bitrates; unknown bitrate guesses 16 kHz.
     */
    internal fun hintFor(mime: String?, bitrate: Int, channels: Int): Hint {
        if (mime !in LOSSY) return Hint(lossy = false, priorCutoffHz = 0f)
        if (mime == MimeTypes.AUDIO_OPUS) return Hint(lossy = true, priorCutoffHz = 0f)
        if (bitrate <= 0) return Hint(lossy = true, priorCutoffHz = 16_000f)
        val kbps = bitrate / 1000 * (if (channels == 1) 2 else 1)
        val cutoff = when {
            kbps <= 64 -> 11_000f
            kbps <= 96 -> 14_000f
            kbps <= 128 -> 16_000f
            kbps <= 192 -> 18_000f
            else -> 0f // 256 kbps and up: at or above ~19 kHz, which Air treats as full range
        }
        return Hint(lossy = true, priorCutoffHz = cutoff)
    }
}
