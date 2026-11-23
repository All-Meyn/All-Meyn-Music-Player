package com.allmeyn.player.library

// Pure Kotlin (no Android): also compiled into the Windows app (desktop/build.gradle.kts, sharedSources).

/** A user playlist: songs by URI, in order. Duplicates are allowed (a song can appear twice). */
data class Playlist(val id: String, val name: String, val uris: List<String>)

/** Pure list edits, unit-tested. Each returns the new list of playlists. */
object PlaylistOps {
    fun create(all: List<Playlist>, id: String, name: String, uris: List<String>) =
        all + Playlist(id, name.trim().ifEmpty { "Playlist" }, uris)

    fun rename(all: List<Playlist>, id: String, name: String) =
        all.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }

    fun delete(all: List<Playlist>, id: String) = all.filter { it.id != id }

    fun add(all: List<Playlist>, id: String, uris: List<String>) =
        all.map { if (it.id == id) it.copy(uris = it.uris + uris) else it }

    fun removeAt(all: List<Playlist>, id: String, index: Int) =
        all.map { p -> if (p.id == id && index in p.uris.indices) p.copy(uris = p.uris.filterIndexed { i, _ -> i != index }) else p }

    fun move(all: List<Playlist>, id: String, from: Int, to: Int) = all.map { p ->
        if (p.id != id || from !in p.uris.indices || to !in p.uris.indices || from == to) p
        else p.copy(uris = p.uris.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** The playlist's songs that are in the library (in order), and how many weren't found. */
    fun resolve(p: Playlist, byUri: Map<String, Track>): Pair<List<Track>, Int> {
        val found = p.uris.mapNotNull { byUri[it] }
        return found to (p.uris.size - found.size)
    }
}
