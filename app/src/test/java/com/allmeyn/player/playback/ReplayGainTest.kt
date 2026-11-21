package com.allmeyn.player.playback

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainTest {
    @Test fun parsesDb() {
        assertEquals(-6.2f, ReplayGain.parseDb("-6.20 dB")!!, 1e-4f)
        assertEquals(1.5f, ReplayGain.parseDb("+1.5")!!, 1e-4f)
        assertEquals(-6.2f, ReplayGain.parseDb(" -6,2 dB ")!!, 1e-4f)
        assertNull(ReplayGain.parseDb("loud"))
        assertNull(ReplayGain.parseDb(null))
        assertNull(ReplayGain.parseDb("-200 dB"))
    }

    @Test fun tagsAnyCaseAndR128() {
        val g = ReplayGain.fromTags(mapOf("REPLAYGAIN_TRACK_GAIN" to "-7.00 dB", "replaygain_album_gain" to "-5.00 dB"))
        assertEquals(-7f, g.trackDb!!, 1e-4f)
        assertEquals(-5f, g.albumDb!!, 1e-4f)
        // Opus: Q7.8 dB relative to -23 LUFS, +5 dB to ReplayGain's reference
        val o = ReplayGain.fromTags(mapOf("R128_TRACK_GAIN" to "-2560"))
        assertEquals(-5f, o.trackDb!!, 1e-4f)
        assertTrue(ReplayGain.fromTags(emptyMap()).isEmpty)
    }

    @Test fun picksByModeWithFallback() {
        val both = ReplayGain.Gains(trackDb = -7f, albumDb = -5f)
        assertEquals(-5f, ReplayGain.pick(both, ReplayGain.Mode.ALBUM))
        assertEquals(-7f, ReplayGain.pick(both, ReplayGain.Mode.TRACK))
        assertEquals(-7f, ReplayGain.pick(ReplayGain.Gains(trackDb = -7f), ReplayGain.Mode.ALBUM))
        assertNull(ReplayGain.pick(both, ReplayGain.Mode.OFF))
    }

    @Test fun volumeOnlyTurnsDown() {
        assertEquals(1f, ReplayGain.volumeFor(null), 0f)
        assertEquals(0.5012f, ReplayGain.volumeFor(-6f), 1e-3f)
        assertEquals(1f, ReplayGain.volumeFor(+4f), 0f) // never boosts
    }

    @Test fun fromMedia3Metadata() {
        val flac = Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-3.10 dB"))
        assertEquals(-3.1f, ReplayGain.fromMetadata(flac).trackDb!!, 1e-4f)
        val mp3 = Metadata(TextInformationFrame("TXXX", "REPLAYGAIN_ALBUM_GAIN", ImmutableList.of("-4.00 dB")))
        assertEquals(-4f, ReplayGain.fromMetadata(mp3).albumDb!!, 1e-4f)
        val m4a = Metadata(InternalFrame("com.apple.iTunes", "replaygain_track_gain", "-2.5 dB"))
        assertEquals(-2.5f, ReplayGain.fromMetadata(m4a).trackDb!!, 1e-4f)
        assertTrue(ReplayGain.fromMetadata(null).isEmpty)
    }
}
