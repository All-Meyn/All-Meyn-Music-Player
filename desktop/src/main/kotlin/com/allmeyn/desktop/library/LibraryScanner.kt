package com.allmeyn.desktop.library

import com.allmeyn.player.library.LibraryIndex
import com.allmeyn.player.library.Track
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** What the engine read from one file (see NativeEngine.readTags). */
data class FileTags(
    val title: String, val artist: String, val album: String, val albumArtist: String,
    val track: Int, val disc: Int, val year: Int, val durationMs: Long,
    val format: String, val sampleRate: Int, val bits: Int, val hasCover: Boolean,
) {
    companion object {
        /** The engine's 14 fields; null if the file couldn't be read. */
        fun from(f: Array<String>): FileTags? {
            if (f.size != 14 || f[0] != "1") return null
            return FileTags(
                title = f[1], artist = f[2], album = f[3], albumArtist = f[4],
                track = f[5].toIntOrNull() ?: 0, disc = f[6].toIntOrNull() ?: 0, year = f[7].toIntOrNull() ?: 0,
                durationMs = f[8].toLongOrNull() ?: 0, format = f[9], sampleRate = f[10].toIntOrNull() ?: 0,
                bits = f[11].toIntOrNull() ?: 0, hasCover = f[12] == "1",
            )
        }
    }
}

/**
 * Walks the music folders and builds the library, like the Android app's MusicLibrary: the same
 * extensions, the same Track fields (folder = the music folder's name + the path inside it), the
 * same covers (embedded first, else cover/folder/front images next to the songs). Files unchanged
 * since the last scan (same size and date) are taken from [previous] without reading them again.
 */
class LibraryScanner(
    private val readTags: (File) -> FileTags?,
    private val readCover: (File) -> ByteArray?,
    private val covers: CoverStore,
    private val parallelism: Int = 4,
) {
    suspend fun scan(roots: List<File>, previous: List<Track>, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<Track> {
        val files = roots.filter { it.isDirectory }.flatMap { root -> audioFiles(root).map { root to it } }
        val known = previous.associateBy { it.uri }
        val total = files.size
        var done = 0
        val gate = Semaphore(parallelism)
        val coverFrom = HashMap<String, File>()  // album key -> a song with an embedded cover (this scan)
        val results = coroutineScope {
            files.map { (root, file) ->
                async {
                    gate.withPermit {
                        val old = known[file.absolutePath]
                        val track = if (old != null && old.size == file.length() && old.modified == file.lastModified()) {
                            old
                        } else {
                            readTags(file)?.let { tags -> toTrack(root, file, tags).also { t -> if (tags.hasCover) synchronized(coverFrom) { coverFrom.putIfAbsent(LibraryIndex.albumKey(t), file) } } }
                        }
                        synchronized(this@LibraryScanner) { progress(++done, total) }
                        track
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return attachCovers(results, coverFrom)
    }

    /** Every album gets one cover file; songs point at it (or "" if the album has none). */
    private fun attachCovers(tracks: List<Track>, coverFrom: Map<String, File>): List<Track> {
        val art = HashMap<String, String>()
        for ((key, list) in tracks.groupBy(LibraryIndex::albumKey)) {
            var path = covers.existing(key)
            if (path.isEmpty()) {
                // Embedded picture first, then an image next to the songs.
                path = coverFrom[key]?.let { f -> readCover(f)?.let { covers.store(key, it) } }.orEmpty()
                if (path.isEmpty()) path = folderImage(File(list.first().uri).parentFile)?.let { covers.store(key, it.readBytes()) }.orEmpty()
            }
            art[key] = path
        }
        return tracks.map { t -> art[LibraryIndex.albumKey(t)].orEmpty().let { a -> if (a == t.art) t else t.copy(art = a) } }
    }

    private fun toTrack(root: File, file: File, tags: FileTags): Track {
        val ext = file.extension.lowercase()
        return Track(
            uri = file.absolutePath, root = root.absolutePath, folder = folderOf(root, file), fileName = file.name,
            title = tags.title.ifBlank { file.nameWithoutExtension },
            artist = tags.artist, album = tags.album, albumArtist = tags.albumArtist,
            trackNo = tags.track, discNo = tags.disc, year = tags.year, durationMs = tags.durationMs,
            format = tags.format.ifBlank { ext.uppercase() }, sampleRate = tags.sampleRate, bits = tags.bits,
            art = "", modified = file.lastModified(), size = file.length(),
        )
    }

    companion object {
        /** As on Android (MusicLibrary.AUDIO_EXTENSIONS). */
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "mp4", "aac", "alac", "ogg", "oga", "opus", "wav", "aif", "aiff",
            "dsf", "dff", "ape", "wv",
        )
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val PREFERRED_COVERS = listOf(
            "cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png", "albumart.jpg",
        )

        fun audioFiles(root: File): List<File> =
            root.walkTopDown()
                .onEnter { !it.isHidden || it == root }
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS && !it.name.startsWith("._") }
                .toList()

        /** "Music/Yes - Big Generator": the music folder's name, then the path inside it. */
        fun folderOf(root: File, file: File): String {
            val rel = file.parentFile.relativeTo(root).invariantSeparatorsPath
            return if (rel.isEmpty()) root.name else "${root.name}/$rel"
        }

        /** cover.jpg / folder.jpg / front.jpg..., else any image in the folder. */
        fun folderImage(dir: File?): File? {
            val images = dir?.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }?.sortedBy { it.name.lowercase() }
                ?: return null
            return PREFERRED_COVERS.firstNotNullOfOrNull { want -> images.firstOrNull { it.name.lowercase() == want } } ?: images.firstOrNull()
        }
    }
}
