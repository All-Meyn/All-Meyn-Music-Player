package com.allmeyn.desktop.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allmeyn.player.library.Playlist
import com.allmeyn.player.library.PlaylistOps
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The user's playlists (the Android app's Playlist / PlaylistOps), saved in `playlists.tsv` beside
 * the library cache (its own file: the library cache is untouched). Loaded at once (small file), so
 * an edit can never race the load; saved on a background thread, replacing the file in one step.
 * Use from the UI thread.
 *
 * File: header line, then per playlist a `P<tab>id<tab>name` line followed by one `U<tab>path` line
 * per song (values escaped as in the library cache).
 */
class DesktopPlaylists(dataDir: File) {
    private val file = File(dataDir, "playlists.tsv")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "playlists").apply { isDaemon = true } }

    var all by mutableStateOf(read(file))
        private set

    fun create(name: String, uris: List<String> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        update { PlaylistOps.create(it, id, name, uris) }
        return id
    }

    fun rename(id: String, name: String) = update { PlaylistOps.rename(it, id, name) }
    fun delete(id: String) = update { PlaylistOps.delete(it, id) }
    fun add(id: String, uris: List<String>) = update { PlaylistOps.add(it, id, uris) }
    fun removeAt(id: String, index: Int) = update { PlaylistOps.removeAt(it, id, index) }
    fun move(id: String, from: Int, to: Int) = update { PlaylistOps.move(it, id, from, to) }

    private fun update(edit: (List<Playlist>) -> List<Playlist>) {
        val next = edit(all)
        if (next == all) return
        all = next
        io.execute { write(file, next) }
    }

    companion object {
        const val HEADER = "allmeyn-playlists\t1"

        fun write(file: File, list: List<Playlist>) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.bufferedWriter().use { w ->
                    w.write(HEADER); w.newLine()
                    for (p in list) {
                        w.write("P\t" + LibraryCache.escape(p.id) + "\t" + LibraryCache.escape(p.name)); w.newLine()
                        for (u in p.uris) { w.write("U\t" + LibraryCache.escape(u)); w.newLine() }
                    }
                }
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                System.err.println("Couldn't save playlists: $e")
            }
        }

        /** The saved playlists; an unknown header or a missing file = none (lines it can't read are skipped). */
        fun read(file: File): List<Playlist> {
            if (!file.isFile) return emptyList()
            return try {
                file.bufferedReader().useLines { lines ->
                    val it = lines.iterator()
                    if (!it.hasNext() || it.next() != HEADER) return@useLines emptyList()
                    val heads = mutableListOf<Pair<String, String>>()
                    val songs = mutableListOf<MutableList<String>>()
                    for (line in it) {
                        val f = line.split('\t').map(LibraryCache::unescape)
                        when {
                            f.size == 3 && f[0] == "P" -> { heads += f[1] to f[2]; songs += mutableListOf<String>() }
                            f.size == 2 && f[0] == "U" && songs.isNotEmpty() -> songs.last() += f[1]
                        }
                    }
                    heads.mapIndexed { i, (id, name) -> Playlist(id, name, songs[i]) }
                }
            } catch (e: IOException) {
                emptyList()
            }
        }
    }
}
