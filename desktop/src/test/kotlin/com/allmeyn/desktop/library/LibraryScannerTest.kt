package com.allmeyn.desktop.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LibraryScannerTest {
    /** Records covers instead of decoding them (no Skia needed). */
    private class FakeCovers(dir: File) : CoverStore(dir) {
        val stored = mutableMapOf<String, ByteArray>()
        override fun store(key: String, encoded: ByteArray): String {
            stored[key] = encoded
            val f = fileFor(key)
            f.parentFile.mkdirs()
            f.writeBytes(encoded)
            return f.absolutePath
        }
    }

    private fun tags(title: String, album: String, track: Int, cover: Boolean = false) =
        FileTags(title, "Yes", album, "", track, 0, 1987, 1000, "FLAC", 44100, 16, cover)

    @Test fun scansLikeAndroidWithCoversAndIncrementalRescans() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("scan").toFile()
        val music = File(root, "Music").apply { mkdirs() }
        val big = File(music, "Yes/Big Generator").apply { mkdirs() }
        val loose = File(music, "Loose").apply { mkdirs() }
        File(big, "01 Rhythm.flac").writeText("a")
        File(big, "02 Shoot.flac").writeText("b")
        File(big, "notes.txt").writeText("not music")
        File(big, "._01 Rhythm.flac").writeText("mac junk")
        File(loose, "song.mp3").writeText("c")
        File(loose, "back.png").writeBytes(byteArrayOf(9))
        File(loose, "Folder.JPG").writeBytes(byteArrayOf(7))   // preferred name beats "back.png"
        val reads = mutableListOf<String>()
        val readTags: (File) -> FileTags? = { f ->
            synchronized(reads) { reads += f.name }
            when (f.name) {
                "01 Rhythm.flac" -> tags("Rhythm of Love", "Big Generator", 1, cover = true)
                "02 Shoot.flac" -> tags("Shoot High", "Big Generator", 2)
                else -> tags("", "", 0)
            }
        }
        val covers = FakeCovers(File(root, "covers"))
        val scanner = LibraryScanner(readTags, readCover = { byteArrayOf(1, 2, 3) }, covers = covers, parallelism = 2)

        val first = scanner.scan(listOf(music), emptyList())
        assertEquals(setOf("01 Rhythm.flac", "02 Shoot.flac", "song.mp3"), reads.toSet())
        val rhythm = first.single { it.title == "Rhythm of Love" }
        assertEquals("Music/Yes/Big Generator", rhythm.folder)
        assertEquals(music.absolutePath, rhythm.root)
        val song = first.single { it.fileName == "song.mp3" }
        assertEquals("song", song.title)          // no title tag: the file name
        assertEquals("Music/Loose", song.folder)
        // Embedded cover for the album; the loose song's folder image (Folder.JPG) otherwise.
        assertTrue(first.filter { it.album == "Big Generator" }.all { it.art.isNotEmpty() && File(it.art).readBytes().contentEquals(byteArrayOf(1, 2, 3)) })
        assertTrue(File(song.art).readBytes().contentEquals(byteArrayOf(7)))

        // Rescan: unchanged files aren't read again; a changed one is.
        reads.clear()
        File(loose, "song.mp3").apply { writeText("changed"); setLastModified(lastModified() + 5000) }
        val second = scanner.scan(listOf(music), first)
        assertEquals(listOf("song.mp3"), reads)
        assertEquals(first.size, second.size)
        root.deleteRecursively()
    }

    @Test fun folderNames() {
        val root = File("C:/Users/me/Music")
        assertEquals("Music", LibraryScanner.folderOf(root, File(root, "a.flac")))
        assertEquals("Music/Yes/Big Generator", LibraryScanner.folderOf(root, File(root, "Yes/Big Generator/a.flac")))
    }

    @Test fun parsesEngineTags() {
        val t = FileTags.from(arrayOf("1", "T", "A", "Al", "AA", "3", "1", "2005", "412000", "DSF DSD64", "2822400", "1", "1", ""))!!
        assertEquals("DSF DSD64", t.format)
        assertEquals(2822400, t.sampleRate)
        assertTrue(t.hasCover)
        assertEquals(null, FileTags.from(arrayOf("0", "", "", "", "", "0", "0", "0", "0", "", "0", "0", "0", "unreadable")))
    }
}
