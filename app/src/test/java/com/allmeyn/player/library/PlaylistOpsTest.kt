package com.allmeyn.player.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOpsTest {
    private val base = listOf(Playlist("a", "Mix", listOf("u1", "u2", "u3")), Playlist("b", "Other", emptyList()))

    @Test fun createRenameDelete() {
        val c = PlaylistOps.create(base, "c", "  New  ", listOf("u9"))
        assertEquals(Playlist("c", "New", listOf("u9")), c.last())
        assertEquals("Playlist", PlaylistOps.create(base, "d", "   ", emptyList()).last().name)
        assertEquals("Renamed", PlaylistOps.rename(base, "a", "Renamed")[0].name)
        assertEquals("Mix", PlaylistOps.rename(base, "a", "  ")[0].name) // blank keeps the old name
        assertEquals(listOf("b"), PlaylistOps.delete(base, "a").map { it.id })
    }

    @Test fun addRemoveMove() {
        assertEquals(listOf("u1", "u2", "u3", "u4", "u1"), PlaylistOps.add(base, "a", listOf("u4", "u1"))[0].uris)
        assertEquals(listOf("u1", "u3"), PlaylistOps.removeAt(base, "a", 1)[0].uris)
        assertEquals(base, PlaylistOps.removeAt(base, "a", 7)) // out of range: unchanged
        assertEquals(listOf("u2", "u3", "u1"), PlaylistOps.move(base, "a", 0, 2)[0].uris)
        assertEquals(listOf("u3", "u1", "u2"), PlaylistOps.move(base, "a", 2, 0)[0].uris)
        assertEquals(emptyList<String>(), PlaylistOps.add(base, "a", listOf("x"))[1].uris) // other playlists untouched
    }

    @Test fun resolveSkipsMissing() {
        val t = Track("u2", "", "", "", "Two", "", "", "", 0, 0, 0, 0, "", 0, 0, "", 0, 0)
        val (found, missing) = PlaylistOps.resolve(base[0], mapOf("u2" to t))
        assertEquals(listOf(t), found)
        assertEquals(2, missing)
    }
}
