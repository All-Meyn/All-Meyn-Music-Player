package com.allmeyn.desktop.library

import com.allmeyn.player.library.Track
import java.io.File

/**
 * The scanned library on disk, so it appears at once on the next start: one line per track,
 * tab-separated, with tabs/newlines/backslashes in values escaped. A different header (format
 * change) means "no cache" and a full scan.
 */
object LibraryCache {
    const val HEADER = "allmeyn-library\t1"
    private const val FIELDS = 18

    fun write(file: File, tracks: List<Track>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write(HEADER)
            w.newLine()
            for (t in tracks) {
                w.write(encode(t))
                w.newLine()
            }
        }
        // Replace in one step, so a crash mid-write never leaves half a library.
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun read(file: File): List<Track> {
        if (!file.isFile) return emptyList()
        return file.bufferedReader().useLines { lines ->
            val it = lines.iterator()
            if (!it.hasNext() || it.next() != HEADER) emptyList()
            else it.asSequence().mapNotNull(::decode).toList()
        }
    }

    fun encode(t: Track): String = listOf(
        t.uri, t.root, t.folder, t.fileName, t.title, t.artist, t.album, t.albumArtist,
        t.trackNo.toString(), t.discNo.toString(), t.year.toString(), t.durationMs.toString(),
        t.format, t.sampleRate.toString(), t.bits.toString(), t.art, t.modified.toString(), t.size.toString(),
    ).joinToString("\t") { escape(it) }

    fun decode(line: String): Track? {
        val f = line.split('\t').map(::unescape)
        if (f.size != FIELDS) return null
        return try {
            Track(
                uri = f[0], root = f[1], folder = f[2], fileName = f[3], title = f[4], artist = f[5], album = f[6],
                albumArtist = f[7], trackNo = f[8].toInt(), discNo = f[9].toInt(), year = f[10].toInt(),
                durationMs = f[11].toLong(), format = f[12], sampleRate = f[13].toInt(), bits = f[14].toInt(),
                art = f[15], modified = f[16].toLong(), size = f[17].toLong(),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun escape(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val b = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> b.append("\\\\")
            '\t' -> b.append("\\t")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            else -> b.append(c)
        }
        return b.toString()
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> b.append('\t')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    else -> b.append(s[i + 1])
                }
                i += 2
            } else {
                b.append(c)
                i++
            }
        }
        return b.toString()
    }
}
