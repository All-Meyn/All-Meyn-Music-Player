package com.allmeyn.player.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryIndexTest {
    private fun t(
        title: String, artist: String = "Yes", album: String = "Big Generator", albumArtist: String = "",
        track: Int = 0, disc: Int = 0, folder: String = "Music/Yes", file: String = "$title.flac", year: Int = 1987,
    ) = Track(
        uri = "content://x/$folder/$file", root = "content://root", folder = folder, fileName = file, title = title,
        artist = artist, album = album, albumArtist = albumArtist, trackNo = track, discNo = disc, year = year,
        durationMs = 1000, format = "FLAC", sampleRate = 44100, bits = 16, art = "", modified = 0, size = 0,
    )

    @Test fun groupsAlbumsAndOrdersTracks() {
        val tracks = listOf(
            t("Shoot High", track = 3), t("Rhythm of Love", track = 1), t("Big Generator", track = 2),
            t("Owner", artist = "Yes", album = "90125", track = 1, year = 1983),
        )
        val albums = LibraryIndex.albums(tracks)
        assertEquals(listOf("90125", "Big Generator"), albums.map { it.title }) // same artist: by year
        assertEquals(listOf("Rhythm of Love", "Big Generator", "Shoot High"), albums[1].tracks.map { it.title })
    }

    @Test fun compilationsGroupByAlbumArtist() {
        val tracks = listOf(
            t("A", artist = "One", album = "Hits", albumArtist = "Various", track = 1),
            t("B", artist = "Two", album = "Hits", albumArtist = "Various", track = 2),
        )
        val albums = LibraryIndex.albums(tracks)
        assertEquals(1, albums.size)
        assertEquals("Various", albums[0].artist)
    }

    @Test fun untaggedFilesGroupByFolder() {
        val tracks = listOf(
            t("x", artist = "", album = "", folder = "Music/Rips/Disc A"),
            t("y", artist = "", album = "", folder = "Music/Rips/Disc A"),
            t("z", artist = "", album = "", folder = "Music/Rips/Disc B"),
        )
        val albums = LibraryIndex.albums(tracks)
        assertEquals(listOf("Disc A", "Disc B"), albums.map { it.title })
        assertEquals("Unknown artist", albums[0].artist)
    }

    @Test fun multiDiscOrder() {
        val tracks = listOf(t("d2t1", disc = 2, track = 1), t("d1t2", disc = 1, track = 2), t("d1t1", disc = 1, track = 1))
        assertEquals(listOf("d1t1", "d1t2", "d2t1"), LibraryIndex.albums(tracks)[0].tracks.map { it.title })
    }

    @Test fun artistsIgnoreLeadingThe() {
        val tracks = listOf(t("a", artist = "The Beatles", album = "Help"), t("b", artist = "Abba", album = "Gold"), t("c", artist = "Cream", album = "Wheels"))
        assertEquals(listOf("Abba", "The Beatles", "Cream"), LibraryIndex.artists(LibraryIndex.albums(tracks)).map { it.name })
    }

    @Test fun folderTree() {
        val tracks = listOf(t("a", folder = "Music/Yes/Big Generator"), t("b", folder = "Music/Yes/90125"), t("c", folder = "Music"))
        val tree = LibraryIndex.folderTree(tracks)
        assertEquals(1, tree.size)
        val music = tree[0]
        assertEquals(listOf("c"), music.tracks.map { it.title })
        assertEquals(listOf("90125", "Big Generator"), music.children.single().children.map { it.name })
        assertEquals("Music/Yes/Big Generator", music.children.single().children[1].path)
    }

    @Test fun numbersAndYears() {
        assertEquals(3, LibraryIndex.parseNumber("3/12"))
        assertEquals(7, LibraryIndex.parseNumber(" 07 "))
        assertEquals(0, LibraryIndex.parseNumber("A1"))
        assertEquals(1987, LibraryIndex.parseYear("1987-06-15"))
        assertEquals(0, LibraryIndex.parseYear(""))
    }

    @Test fun qualityLabels() {
        assertEquals("FLAC · 16/44.1", t("a").qualityLabel)
        assertEquals("FLAC · 24/96", t("a").copy(bits = 24, sampleRate = 96000).qualityLabel)
        assertEquals("DSF DSD64", t("a").copy(format = "DSF DSD64", sampleRate = 2822400, bits = 1).qualityLabel)
    }
}
