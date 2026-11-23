package com.allmeyn.desktop.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allmeyn.desktop.engine.NativeEngine
import com.allmeyn.player.library.Album
import com.allmeyn.player.library.Artist
import com.allmeyn.player.library.FolderNode
import com.allmeyn.player.library.LibraryIndex
import com.allmeyn.player.library.Track
import java.io.File
import java.util.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The library grouped for the screens; built once per scan (the Android app's LibraryIndex). */
class LibraryView(val tracks: List<Track>) {
    val albums: List<Album> = LibraryIndex.albums(tracks)
    val artists: List<Artist> = LibraryIndex.artists(albums)
    val songs: List<Track> = LibraryIndex.tracksByTitle(tracks)
    val folders: List<FolderNode> = LibraryIndex.folderTree(tracks)
    val search = LibraryIndex.SearchIndex(artists, albums, tracks)
    val byUri: Map<String, Track> = tracks.associateBy { it.uri }
    val isEmpty get() = tracks.isEmpty()
}

/** Music folders, the scanned library and its cache. Use from the UI thread. */
class DesktopLibrary(private val dataDir: File = defaultDataDir()) {
    private val prefs = Preferences.userRoot().node("com/allmeyn/desktop")
    private val cacheFile = File(dataDir, "library.tsv")
    private val covers = CoverStore(File(dataDir, "covers"))
    val playlists = DesktopPlaylists(dataDir)

    var folders by mutableStateOf(prefs.get(KEY_FOLDERS, "").split('\n').filter { it.isNotBlank() })
        private set
    var view by mutableStateOf(LibraryView(emptyList()))
        private set
    var loaded by mutableStateOf(false)
        private set
    /** "done / total" while scanning, null otherwise. */
    var scanProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** Shows the cached library at once, then rescans for changes. */
    suspend fun start() {
        view = LibraryView(withContext(Dispatchers.IO) { LibraryCache.read(cacheFile) })
        loaded = true
        rescan()
    }

    suspend fun rescan() {
        if (scanProgress != null || !NativeEngine.isReady) return
        scanProgress = 0 to 0
        try {
            val scanner = LibraryScanner(
                readTags = { f -> FileTags.from(NativeEngine.readTags(f.absolutePath)) },
                readCover = { f -> NativeEngine.readCover(f.absolutePath) },
                covers = covers,
            )
            val roots = folders.map(::File)
            val previous = view.tracks
            val tracks = withContext(Dispatchers.IO) {
                scanner.scan(roots, previous) { done, total -> if (done % 25 == 0 || done == total) scanProgress = done to total }
                    .also { LibraryCache.write(cacheFile, it) }
            }
            view = LibraryView(tracks)
        } finally {
            scanProgress = null
        }
    }

    suspend fun addFolder(path: String) {
        if (path in folders) return
        saveFolders(folders + path)
        rescan()
    }

    suspend fun removeFolder(path: String) {
        saveFolders(folders - path)
        rescan()
    }

    private fun saveFolders(list: List<String>) {
        folders = list
        prefs.put(KEY_FOLDERS, list.joinToString("\n"))
    }

    companion object {
        private const val KEY_FOLDERS = "musicFolders"

        /** %LOCALAPPDATA%\All-Meyn (library cache, covers). */
        fun defaultDataDir(): File =
            File(System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + File.separator + ".allmeyn"), "All-Meyn")
    }
}
