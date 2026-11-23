package com.allmeyn.desktop.library

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The queue as it was when the app closed, so it comes back paused at the same spot: songs (by
 * path) in play order, the list's own order while shuffled, the song playing, its position,
 * shuffle and repeat.
 */
data class QueueSnapshot(
    val queue: List<String>,
    val original: List<String>?,  // non-null while shuffled
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: String,           // RepeatMode name
)

/**
 * `queue.tsv` beside the library cache: header, an `S` line (shuffle, repeat, index, position),
 * then `Q<tab>path` per queued song and `O<tab>path` per song of the original order. Replaced in
 * one step on save.
 */
object QueueStore {
    const val HEADER = "allmeyn-queue\t1"

    fun write(file: File, s: QueueSnapshot) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write(HEADER); w.newLine()
                w.write("S\t${if (s.shuffle) 1 else 0}\t${s.repeat}\t${s.index}\t${s.positionMs}"); w.newLine()
                for (u in s.queue) { w.write("Q\t" + LibraryCache.escape(u)); w.newLine() }
                s.original?.forEach { u -> w.write("O\t" + LibraryCache.escape(u)); w.newLine() }
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            System.err.println("Couldn't save the queue: $e")
        }
    }

    /** The saved queue; null if none, empty or from another format. */
    fun read(file: File): QueueSnapshot? {
        if (!file.isFile) return null
        return try {
            file.bufferedReader().useLines { lines ->
                val it = lines.iterator()
                if (!it.hasNext() || it.next() != HEADER) return@useLines null
                var shuffle = false
                var repeat = "OFF"
                var index = 0
                var position = 0L
                val queue = mutableListOf<String>()
                val original = mutableListOf<String>()
                for (line in it) {
                    val f = line.split('\t')
                    when {
                        f.size == 5 && f[0] == "S" -> {
                            shuffle = f[1] == "1"
                            repeat = f[2]
                            index = f[3].toIntOrNull() ?: 0
                            position = f[4].toLongOrNull() ?: 0
                        }
                        f.size == 2 && f[0] == "Q" -> queue += LibraryCache.unescape(f[1])
                        f.size == 2 && f[0] == "O" -> original += LibraryCache.unescape(f[1])
                    }
                }
                if (queue.isEmpty()) null
                else QueueSnapshot(queue, if (shuffle) original else null, index, position.coerceAtLeast(0), shuffle, repeat)
            }
        } catch (e: IOException) {
            null
        }
    }
}
