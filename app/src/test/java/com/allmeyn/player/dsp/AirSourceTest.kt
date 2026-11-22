package com.allmeyn.player.dsp

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class AirSourceTest {
    private fun hint(mime: String?, kbps: Int, channels: Int = 2) = AirSource.hintFor(mime, kbps * 1000, channels)

    @Test fun losslessHasNoCutoff() {
        for (m in listOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_TRUEHD, null)) {
            assertEquals(AirSource.Hint(false, 0f), hint(m, 900))
        }
    }

    @Test fun mp3AndAacByBitrate() {
        assertEquals(AirSource.Hint(true, 11_000f), hint(MimeTypes.AUDIO_MPEG, 64))
        assertEquals(AirSource.Hint(true, 14_000f), hint(MimeTypes.AUDIO_MPEG, 96))
        assertEquals(AirSource.Hint(true, 16_000f), hint(MimeTypes.AUDIO_MPEG, 128))
        assertEquals(AirSource.Hint(true, 18_000f), hint(MimeTypes.AUDIO_AAC, 192))
        assertEquals(AirSource.Hint(true, 0f), hint(MimeTypes.AUDIO_AAC, 256))
        assertEquals(AirSource.Hint(true, 0f), hint(MimeTypes.AUDIO_MPEG, 320))
    }

    @Test fun monoCountsDoubleAndUnknownGuesses16k() {
        assertEquals(AirSource.Hint(true, 16_000f), hint(MimeTypes.AUDIO_MPEG, 64, channels = 1))
        assertEquals(AirSource.Hint(true, 16_000f), AirSource.hintFor(MimeTypes.AUDIO_MPEG, -1, 2))
    }

    @Test fun opusIsFullBand() {
        assertEquals(AirSource.Hint(true, 0f), hint(MimeTypes.AUDIO_OPUS, 96))
    }
}
