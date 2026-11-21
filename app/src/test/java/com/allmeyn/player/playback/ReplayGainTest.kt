package com.allmeyn.player.playback

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    private fun le(vararg shorts: Int) = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        .apply { shorts.forEach { putShort(it.toShort()) }; flip() }

    @Test fun scalesEachFormat() {
        val s16 = le(10000, -10000, 32767, -32768)
        ReplayGain.scale(s16, C.ENCODING_PCM_16BIT, 0.5f)
        assertEquals(listOf(5000, -5000, 16384, -16384), (0 until 4).map { s16.getShort(it * 2).toInt() })
        assertEquals(0, s16.position()) // position/limit untouched

        val s24 = ByteBuffer.wrap(byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x00, 0xC0.toByte())) // +0x400000, -0x400000
        ReplayGain.scale(s24, C.ENCODING_PCM_24BIT, 0.5f)
        assertEquals(listOf(0x00, 0x00, 0x20, 0x00, 0x00, 0xE0), s24.array().map { it.toInt() and 0xFF })

        val s32 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1_000_000_000).putInt(-2_000_000_000).flip() as ByteBuffer
        ReplayGain.scale(s32, C.ENCODING_PCM_32BIT, 0.25f)
        assertEquals(250_000_000, s32.getInt(0)); assertEquals(-500_000_000, s32.getInt(4))

        val f = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.8f).putFloat(-1f).flip() as ByteBuffer
        ReplayGain.scale(f, C.ENCODING_PCM_FLOAT, 0.5f)
        assertEquals(0.4f, f.getFloat(0), 1e-7f); assertEquals(-0.5f, f.getFloat(4), 1e-7f)
    }

    @Test fun scaleOnlyTouchesPositionToLimitAndNeverBoosts() {
        val b = le(1000, 1000, 1000)
        b.position(2); b.limit(4)
        ReplayGain.scale(b, C.ENCODING_PCM_16BIT, 0.5f)
        assertEquals(listOf(1000, 500, 1000), (0 until 3).map { b.getShort(it * 2).toInt() })
        val c = le(1000)
        ReplayGain.scale(c, C.ENCODING_PCM_16BIT, 1f)
        assertEquals(1000, c.getShort(0).toInt())
    }
}
