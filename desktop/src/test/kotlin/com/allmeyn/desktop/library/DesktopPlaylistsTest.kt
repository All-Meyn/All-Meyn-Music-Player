package com.allmeyn.desktop.library

import com.allmeyn.player.library.Playlist
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlaylistsTest {
    @Test fun roundTripsNamesWithTabsAndEmptyPlaylists() {
        val f = File.createTempFile("playlists", ".tsv")
        val list = listOf(
            Playlist("a1", "Road\ttrip \\ mix\n", listOf("C:\\Music\\1.flac", "C:\\Music\\Sigur Rós\\2.dsf", "C:\\Music\\1.flac")),
            Playlist("b2", "Empty", emptyList()),
        )
        DesktopPlaylists.write(f, list)
        assertEquals(list, DesktopPlaylists.read(f))
        f.delete()
    }

    @Test fun unknownHeaderOrMissingFileMeansNone() {
        val f = File.createTempFile("playlists", ".tsv")
        f.writeText("something-else\t9\nP\tx\ty\n")
        assertEquals(emptyList(), DesktopPlaylists.read(f))
        f.delete()
        assertEquals(emptyList(), DesktopPlaylists.read(File(f.parentFile, "nope-${System.nanoTime()}.tsv")))
    }

    @Test fun editsAreSavedAndReloaded() {
        val dir = File(System.getProperty("java.io.tmpdir"), "allmeyn-pl-${System.nanoTime()}").apply { mkdirs() }
        val store = DesktopPlaylists(dir)
        val id = store.create("Mine", listOf("a", "b"))
        store.add(id, listOf("c"))
        store.move(id, 2, 0)
        store.removeAt(id, 1)
        assertEquals(listOf("c", "b"), store.all.single().uris)
        // Saves run on a background thread: wait until the file shows the last edit.
        val file = File(dir, "playlists.tsv")
        repeat(100) { if (DesktopPlaylists.read(file).singleOrNull()?.uris == listOf("c", "b")) return@repeat; Thread.sleep(20) }
        assertEquals(store.all, DesktopPlaylists(dir).all)
        dir.deleteRecursively()
    }
}
