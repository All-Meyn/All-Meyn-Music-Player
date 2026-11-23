package com.allmeyn.desktop.ui

import com.allmeyn.player.library.Track
import kotlin.random.Random

enum class RepeatMode { OFF, ALL, ONE;
    fun next() = entries[(ordinal + 1) % entries.size]
}

/**
 * Shuffle and repeat for the desktop queue (pure, unit-tested). The queue is always kept in play
 * order: shuffling reorders it (the playing song first, the rest at random) and remembers the
 * original list, so turning shuffle off puts it back. That keeps "Play next" (insert after the
 * playing song) and "Add to queue" (append) meaning what they say while shuffled, as on Android.
 */
object QueueOrder {
    /** [tracks] shuffled with the song at [first] (if any) playing first. */
    fun shuffled(tracks: List<Track>, first: Int, random: Random = Random.Default): List<Track> {
        if (first !in tracks.indices) return tracks.shuffled(random)
        val rest = tracks.toMutableList().apply { removeAt(first) }.shuffled(random)
        return listOf(tracks[first]) + rest
    }

    /**
     * Back to the [original] order: its songs still in [queue] in their original order, then songs
     * added while shuffled that it doesn't have. Returns the list and where [playing] is in it.
     */
    fun unshuffled(queue: List<Track>, playing: Int, original: List<Track>): Pair<List<Track>, Int> {
        val remaining = queue.groupingBy { it }.eachCount().toMutableMap()
        val out = ArrayList<Track>(queue.size)
        for (t in original) {
            val n = remaining[t] ?: 0
            if (n > 0) { out += t; remaining[t] = n - 1 }
        }
        for (t in queue) {
            val n = remaining[t] ?: 0
            if (n > 0) { out += t; remaining[t] = n - 1 }
        }
        val current = queue.getOrNull(playing)
        return out to (if (current == null) -1 else out.indexOf(current))
    }

    /** Where the song at [playing] ends up after moving the song at [from] to [to]. */
    fun indexAfterMove(playing: Int, from: Int, to: Int): Int = when {
        playing == from -> to
        from < playing && to >= playing -> playing - 1
        from > playing && to <= playing -> playing + 1
        else -> playing
    }

    /** The song to follow [index] by itself (end of a song): repeat one = the same, all = wraps. */
    fun autoNext(index: Int, size: Int, repeat: RepeatMode): Int? = when {
        index !in 0 until size -> null
        repeat == RepeatMode.ONE -> index
        index + 1 < size -> index + 1
        repeat == RepeatMode.ALL -> 0
        else -> null
    }

    /** The Next button: moves on even with repeat one; wraps only with repeat all. */
    fun manualNext(index: Int, size: Int, repeat: RepeatMode): Int? = when {
        index !in 0 until size -> null
        index + 1 < size -> index + 1
        repeat == RepeatMode.ALL -> 0
        else -> null
    }

    /** The Previous button's song (when not just restarting this one): wraps only with repeat all. */
    fun manualPrevious(index: Int, size: Int, repeat: RepeatMode): Int? = when {
        index !in 0 until size -> null
        index > 0 -> index - 1
        repeat == RepeatMode.ALL && size > 1 -> size - 1
        else -> null
    }
}
