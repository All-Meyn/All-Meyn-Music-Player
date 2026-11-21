package com.allmeyn.player.library

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

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

/**
 * Playlists saved as JSON in the app's files (`playlists.json`, its own file: the library cache
 * is untouched). Writes happen on a background thread, atomically (temp file + rename).
 */
class PlaylistStore(context: Context) {
    private val file = File(context.filesDir, "playlists.json")
    private val io = Executors.newSingleThreadExecutor { Thread(it, "playlists") }
    // Loaded right away (a small file) so an edit can never race the initial load.
    private val _state = MutableStateFlow(load())
    val state: StateFlow<List<Playlist>> = _state.asStateFlow()

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

    @Synchronized
    private fun update(edit: (List<Playlist>) -> List<Playlist>) {
        val next = edit(_state.value)
        _state.value = next
        io.execute { save(next) }
    }

    private fun load(): List<Playlist> = try {
        if (!file.exists()) emptyList() else {
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("playlists")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val u = o.getJSONArray("uris")
                Playlist(o.getString("id"), o.getString("name"), (0 until u.length()).map { u.getString(it) })
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't read playlists", e)
        emptyList()
    }

    private fun save(list: List<Playlist>) {
        try {
            val arr = JSONArray()
            list.forEach { p ->
                arr.put(JSONObject().put("id", p.id).put("name", p.name).put("uris", JSONArray(p.uris)))
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("version", 1).put("playlists", arr).toString())
            if (!tmp.renameTo(file)) Log.e(TAG, "Couldn't replace playlists.json")
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't save playlists", e)
        }
    }

    private companion object {
        const val TAG = "PlaylistStore"
    }
}
