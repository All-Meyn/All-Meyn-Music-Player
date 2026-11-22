package com.allmeyn.desktop.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackInfoTest {
    private fun info(vararg f: String) = TrackInfo.from(arrayOf(*f, "C:/Music/01 Song.flac"))!!

    @Test fun labelsAndTitles() {
        val t = info("FLAC", "96000", "2", "24", "960000", "", "Artist", "", "Speakers (FiiO Q series) · Windows mixer converts to 48.0 kHz")
        assertEquals("FLAC · 96 kHz · 24-bit", t.formatLabel)
        assertEquals("01 Song", t.displayTitle)
        assertEquals("Artist", t.subtitle)
        assertEquals(10_000, t.durationMs)
        assertEquals("MP3 · 44.1 kHz · mono", info("MP3", "44100", "1", "0", "0", "T", "A", "B", "x").formatLabel)
        assertEquals("A — B", info("MP3", "44100", "1", "0", "0", "T", "A", "B", "x").subtitle)
        assertEquals("DSF DSD64 → PCM 176.4 kHz", info("DSF DSD64 → PCM 176.4 kHz", "176400", "2", "0", "1", "", "", "", "x").formatLabel)
    }

    @Test fun rejectsWrongFieldCount() {
        assertNull(TrackInfo.from(arrayOf("FLAC")))
    }

    @Test fun formatsTimes() {
        assertEquals("0:00", TrackInfo.formatTime(0))
        assertEquals("3:07", TrackInfo.formatTime(187_000))
        assertEquals("1:02:03", TrackInfo.formatTime(3_723_000))
        assertEquals("0:00", TrackInfo.formatTime(-5))
    }

    @Test fun playStates() {
        assertEquals(PlayState.PLAYING, PlayState.of(1))
        assertEquals(PlayState.ERROR, PlayState.of(4))
        assertEquals(PlayState.IDLE, PlayState.of(99))
    }
}
