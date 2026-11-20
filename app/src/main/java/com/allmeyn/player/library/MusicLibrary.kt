package com.allmeyn.player.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The music library: folders the user picked (persistent tree permissions), scanned for audio
 * files, with tags and covers. Results are cached in app storage, so the library appears
 * instantly; rescans only re-read files whose size or date changed.
 */
@UnstableApi
class MusicLibrary(private val context: Context) {

    data class State(
        val folders: List<Uri> = emptyList(),
        val tracks: List<Track> = emptyList(),
        val albums: List<Album> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val folderTree: List<FolderNode> = emptyList(),
        val scanning: Boolean = false,
        val progress: String = "",
        val loaded: Boolean = false,
    )

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "library.json")
    private val art = ArtCache(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(State(folders = savedFolders()))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        scope.launch {
            publish(loadCache(), loaded = true)
            if (_state.value.folders.isNotEmpty()) rescan()
        }
    }

    // ---- folders ----------------------------------------------------------------------------------

    private fun savedFolders(): List<Uri> =
        prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().map(Uri::parse).sortedBy { it.toString() }

    /** Adds a folder picked with ACTION_OPEN_DOCUMENT_TREE and scans it. */
    fun addFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val folders = (savedFolders() + treeUri).distinct()
        prefs.edit().putStringSet(KEY_FOLDERS, folders.map { it.toString() }.toSet()).apply()
        _state.update { it.copy(folders = folders) }
        rescan()
    }

    fun removeFolder(treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val folders = savedFolders() - treeUri
        prefs.edit().putStringSet(KEY_FOLDERS, folders.map { it.toString() }.toSet()).apply()
        _state.update { it.copy(folders = folders) }
        val remaining = _state.value.tracks.filter { it.root != treeUri.toString() }
        publish(remaining)
        scope.launch { saveCache(remaining) }
    }

    fun folderLabel(treeUri: Uri): String =
        DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':').ifBlank { "Internal storage" }

    // ---- scanning ---------------------------------------------------------------------------------

    fun rescan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            _state.update { it.copy(scanning = true, progress = "Looking for music…") }
            val previous = _state.value.tracks.associateBy { it.uri }
            val found = mutableListOf<Track>()
            var seen = 0
            try {
                for (root in _state.value.folders) {
                    walk(root) { file ->
                        if (!isActive) return@walk
                        seen++
                        val old = previous[file.uri.toString()]
                        val track = if (old != null && old.modified == file.modified && old.size == file.size &&
                            (old.art.isEmpty() || File(old.art).exists())
                        ) {
                            old
                        } else {
                            readTrack(root, file)
                        }
                        if (track != null) found += track
                        if (seen % 25 == 0) {
                            _state.update { it.copy(progress = "Scanning… $seen files") }
                        }
                    }
                }
                if (isActive) {
                    publish(found)
                    saveCache(found)
                }
            } catch (e: Exception) {
                Log.e(TAG, "scan failed", e)
                _state.update { it.copy(progress = "Scan failed: ${e.message}") }
            } finally {
                _state.update {
                    it.copy(
                        scanning = false,
                        progress = if (it.progress.startsWith("Scan failed")) it.progress else "${it.tracks.size} tracks",
                    )
                }
            }
        }
    }

    private class FileEntry(
        val uri: Uri,
        val name: String,
        val folder: String,
        val modified: Long,
        val size: Long,
        val folderImages: () -> List<Pair<String, Uri>>,
    )

    /** Depth-first walk of a picked folder via DocumentsContract (no file-path access needed). */
    private fun walk(root: Uri, onAudio: (FileEntry) -> Unit) {
        val rootId = DocumentsContract.getTreeDocumentId(root)
        val rootName = folderLabel(root).substringAfterLast('/').ifBlank { "Music" }
        val stack = ArrayDeque<Pair<String, String>>() // documentId, folder path
        stack.addLast(rootId to rootName)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (stack.isNotEmpty()) {
            val (dirId, path) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, dirId)
            val audio = mutableListOf<FileEntry>()
            val images = mutableListOf<Pair<String, Uri>>()
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) stack.addLast(id to "$path/$name")
                        continue
                    }
                    val uri = DocumentsContract.buildDocumentUriUsingTree(root, id)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    when {
                        ext in AUDIO_EXTENSIONS -> audio += FileEntry(uri, name, path, c.getLong(3), c.getLong(4)) { images }
                        ext in IMAGE_EXTENSIONS -> images += name.lowercase() to uri
                    }
                }
            }
            audio.forEach(onAudio)
        }
    }

    private fun readTrack(root: Uri, f: FileEntry): Track? {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val tags = try {
            context.contentResolver.openFileDescriptor(f.uri, "r")?.use { TagReader.read(it, ext) }
        } catch (e: Exception) {
            Log.w(TAG, "can't read ${f.name}: ${e.message}")
            null
        } ?: return null

        val base = Track(
            uri = f.uri.toString(), root = root.toString(), folder = f.folder, fileName = f.name,
            title = tags.title.ifBlank { f.name.substringBeforeLast('.') },
            artist = tags.artist, album = tags.album, albumArtist = tags.albumArtist,
            trackNo = tags.trackNo, discNo = tags.discNo, year = tags.year, durationMs = tags.durationMs,
            format = tags.format.ifBlank { ext.uppercase() }, sampleRate = tags.sampleRate, bits = tags.bits,
            art = "", modified = f.modified, size = f.size,
        )
        val key = LibraryIndex.albumKey(base)
        val cover = art.existing(key).ifEmpty {
            tags.cover?.let { art.store(key, it) }.orEmpty().ifEmpty { folderCover(key, f) }
        }
        return base.copy(art = cover)
    }

    /** cover.jpg / folder.jpg / front.jpg (or any image) next to the track. */
    private fun folderCover(key: String, f: FileEntry): String {
        val images = f.folderImages()
        val pick = PREFERRED_COVERS.firstNotNullOfOrNull { want -> images.firstOrNull { it.first == want } }
            ?: images.firstOrNull()
            ?: return ""
        return try {
            context.contentResolver.openInputStream(pick.second)?.use { art.store(key, it.readBytes()) }.orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    // ---- state + cache ----------------------------------------------------------------------------

    private fun publish(tracks: List<Track>, loaded: Boolean = true) {
        val albums = LibraryIndex.albums(tracks)
        _state.update {
            it.copy(
                tracks = tracks,
                albums = albums,
                artists = LibraryIndex.artists(albums),
                folderTree = LibraryIndex.folderTree(tracks),
                loaded = loaded || it.loaded,
            )
        }
    }

    private fun loadCache(): List<Track> = try {
        if (!cacheFile.exists()) emptyList() else {
            val a = JSONArray(cacheFile.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Track(
                    uri = o.getString("uri"), root = o.getString("root"), folder = o.getString("folder"),
                    fileName = o.getString("fileName"), title = o.getString("title"), artist = o.getString("artist"),
                    album = o.getString("album"), albumArtist = o.getString("albumArtist"), trackNo = o.getInt("trackNo"),
                    discNo = o.getInt("discNo"), year = o.getInt("year"), durationMs = o.getLong("durationMs"),
                    format = o.getString("format"), sampleRate = o.getInt("sampleRate"), bits = o.getInt("bits"),
                    art = o.getString("art"), modified = o.getLong("modified"), size = o.getLong("size"),
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "library cache unreadable, rescanning", e)
        emptyList()
    }

    private fun saveCache(tracks: List<Track>) {
        val a = JSONArray()
        for (t in tracks) {
            a.put(
                JSONObject()
                    .put("uri", t.uri).put("root", t.root).put("folder", t.folder).put("fileName", t.fileName)
                    .put("title", t.title).put("artist", t.artist).put("album", t.album).put("albumArtist", t.albumArtist)
                    .put("trackNo", t.trackNo).put("discNo", t.discNo).put("year", t.year).put("durationMs", t.durationMs)
                    .put("format", t.format).put("sampleRate", t.sampleRate).put("bits", t.bits).put("art", t.art)
                    .put("modified", t.modified).put("size", t.size),
            )
        }
        val tmp = File(cacheFile.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(cacheFile)
    }

    companion object {
        private const val TAG = "MusicLibrary"
        private const val KEY_FOLDERS = "folders"
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "mp4", "aac", "alac", "ogg", "oga", "opus", "wav", "aif", "aiff",
            "dsf", "dff", "ape", "wv",
        )
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val PREFERRED_COVERS = listOf(
            "cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png", "albumart.jpg",
        )
    }
}
