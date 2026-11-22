package com.allmeyn.desktop.library

import com.allmeyn.player.library.Track
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryCacheTest {
    private fun track(title: String) = Track(
        uri = "C:\\Music\\Sigur Rós\\$title.flac", root = "C:\\Music", folder = "Music/Sigur Rós", fileName = "$title.flac",
        title = title, artist = "Sigur Rós", album = "Takk...", albumArtist = "", trackNo = 3, discNo = 1, year = 2005,
        durationMs = 412_000, format = "FLAC", sampleRate = 96000, bits = 24, art = "C:\\covers\\a.jpg", modified = 1_700_000_000_000, size = 123_456_789,
    )

    @Test fun roundTripsIncludingTabsNewlinesAndBackslashes() {
        val tricky = track("Odd\ttitle\nwith \\ and \r")
        val f = File.createTempFile("library", ".tsv")
        LibraryCache.write(f, listOf(track("Hoppípolla"), tricky))
        assertEquals(listOf(track("Hoppípolla"), tricky), LibraryCache.read(f))
        f.delete()
    }

    @Test fun ignoresOtherFormatsAndBadLines() {
        val f = File.createTempFile("library", ".tsv")
        f.writeText("something-else\n" + LibraryCache.encode(track("x")) + "\n")
        assertEquals(emptyList(), LibraryCache.read(f))
        assertNull(LibraryCache.decode("too\tfew\tfields"))
        assertEquals(emptyList(), LibraryCache.read(File(f.parentFile, "missing-${System.nanoTime()}.tsv")))
        f.delete()
    }
}
