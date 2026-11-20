package com.allmeyn.player.library

/** One audio file in the library. Strings are never null so the JSON cache stays simple. */
data class Track(
    val uri: String,
    val root: String,          // tree URI of the music folder it was found under
    val folder: String,        // path of its folder from the music folder, e.g. "Music/Yes - Big Generator"
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val trackNo: Int,
    val discNo: Int,
    val year: Int,
    val durationMs: Long,
    val format: String,        // e.g. "FLAC", "DSF DSD64", "APE"
    val sampleRate: Int,       // Hz (DSD: the DSD rate)
    val bits: Int,             // 0 if unknown; 1 for DSD
    val art: String,           // path of the cached cover image, "" if none
    val modified: Long,
    val size: Long,
) {
    /** Album artist if tagged, otherwise the track artist. */
    val albumArtistOrArtist get() = albumArtist.ifBlank { artist }

    /** e.g. "FLAC · 24/96" or "DSF DSD64". */
    val qualityLabel: String
        get() = when {
            format.contains("DSD") -> format
            sampleRate > 0 && bits > 0 -> "$format · $bits/${khz(sampleRate)}"
            sampleRate > 0 -> "$format · ${khz(sampleRate)} kHz"
            else -> format
        }

    private fun khz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(hz / 1000.0)
}

data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int,
    val tracks: List<Track>,
    val art: String,
) {
    val durationMs get() = tracks.sumOf { it.durationMs }
}

data class Artist(val name: String, val albums: List<Album>) {
    val trackCount get() = albums.sumOf { it.tracks.size }
}

/** A folder in the Folders tab: sub-folders by name and the tracks directly inside it. */
data class FolderNode(
    val path: String,
    val name: String,
    val children: List<FolderNode>,
    val tracks: List<Track>,
)

/** Grouping and sorting of the library. Pure functions, unit-tested. */
object LibraryIndex {
    private val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
    private val byName = Comparator<String> { a, b -> collator.compare(sortKey(a), sortKey(b)) }

    /** "The Beatles" sorts under B. */
    fun sortKey(s: String) = s.trim().removePrefix("The ").removePrefix("the ")

    fun albumKey(t: Track): String {
        val album = t.album.ifBlank { "\u0000" + t.root + "/" + t.folder }
        return t.albumArtistOrArtist.lowercase() + "\u0001" + album.lowercase()
    }

    fun albums(tracks: List<Track>): List<Album> =
        tracks.groupBy(::albumKey).map { (key, list) ->
            val sorted = list.sortedWith(trackOrder)
            val first = sorted.first()
            Album(
                key = key,
                title = first.album.ifBlank { first.folder.substringAfterLast('/').ifBlank { "Unknown album" } },
                artist = first.albumArtistOrArtist.ifBlank { "Unknown artist" },
                year = sorted.maxOf { it.year },
                tracks = sorted,
                art = sorted.firstOrNull { it.art.isNotEmpty() }?.art ?: "",
            )
        }.sortedWith(compareBy(byName) { a: Album -> a.artist }.thenBy { it.year }.thenBy(byName) { it.title })

    fun artists(albums: List<Album>): List<Artist> =
        albums.groupBy { it.artist.lowercase() }
            .map { (_, list) -> Artist(list.first().artist, list.sortedWith(compareBy<Album> { it.year }.thenBy(byName) { it.title })) }
            .sortedWith(compareBy(byName) { it.name })

    fun tracksByTitle(tracks: List<Track>): List<Track> = tracks.sortedWith(compareBy(byName) { it.title })

    val trackOrder: Comparator<Track> =
        compareBy<Track> { it.discNo }.thenBy { if (it.trackNo > 0) it.trackNo else Int.MAX_VALUE }.thenBy(byName) { it.fileName }

    fun folderTree(tracks: List<Track>): List<FolderNode> {
        // Note: named "own", not "tracks": inside this local class a bare "tracks" would resolve to
        // the function parameter (locals win over members) and every folder would get every track.
        class Mutable(val path: String, val name: String) {
            val children = sortedMapOf<String, Mutable>(byName)
            val own = mutableListOf<Track>()
            fun freeze(): FolderNode = FolderNode(path, name, children.values.map { it.freeze() }, own.sortedWith(trackOrder))
        }
        val top = sortedMapOf<String, Mutable>(byName)
        for (t in tracks) {
            val parts = t.folder.split('/').filter { it.isNotEmpty() }.ifEmpty { listOf("Music") }
            var level = top
            var node: Mutable? = null
            var path = ""
            for (p in parts) {
                path = if (path.isEmpty()) p else "$path/$p"
                val n = level.getOrPut(p) { Mutable(path, p) }
                node = n
                level = n.children
            }
            node!!.own += t
        }
        return top.values.map { it.freeze() }
    }

    /** "3", "03", "3/12" -> 3; anything else -> 0. */
    fun parseNumber(s: String?): Int = s?.trim()?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0

    /** "1987", "1987-06-15" -> 1987. */
    fun parseYear(s: String?): Int = s?.trim()?.take(4)?.toIntOrNull() ?: 0
}
