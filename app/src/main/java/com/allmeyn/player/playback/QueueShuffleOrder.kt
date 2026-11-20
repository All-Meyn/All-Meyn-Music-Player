package com.allmeyn.player.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Shuffle order that keeps "Play next" and "Add to queue" meaningful while shuffle is on.
 *
 * Media3's DefaultShuffleOrder scatters newly added songs at random places. Here:
 * - Loading a new list (inserting into an empty order) shuffles it randomly, as before.
 * - Inserted songs play right after the song that precedes them in the list, so "Play next"
 *   (inserted after the current song) really plays next, and "Add to queue" (inserted after the
 *   song that plays last) plays last. Inserted at the very start, they play first.
 * Inserted songs keep their own relative order.
 *
 * [order] holds list indices in play order; [position] is its inverse.
 */
@UnstableApi
class QueueShuffleOrder private constructor(
    private val order: IntArray,
    private val random: Random,
) : ShuffleOrder {

    constructor(random: Random = Random.Default) : this(IntArray(0), random)

    private val position = IntArray(order.size).also { pos -> order.forEachIndexed { p, i -> pos[i] = p } }

    override fun getLength() = order.size
    override fun getFirstIndex() = if (order.isEmpty()) C.INDEX_UNSET else order[0]
    override fun getLastIndex() = if (order.isEmpty()) C.INDEX_UNSET else order[order.size - 1]

    override fun getNextIndex(index: Int): Int {
        val p = position[index] + 1
        return if (p < order.size) order[p] else C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        val p = position[index] - 1
        return if (p >= 0) order[p] else C.INDEX_UNSET
    }

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (insertionCount == 0) return this
        val added = IntArray(insertionCount) { insertionIndex + it }
        if (order.isEmpty()) {
            added.shuffle(random)
            return QueueShuffleOrder(added, random)
        }
        // Existing indices at or after the insertion point move up.
        val shifted = IntArray(order.size) { val i = order[it]; if (i >= insertionIndex) i + insertionCount else i }
        val at = if (insertionIndex == 0) 0 else position[insertionIndex - 1] + 1
        return QueueShuffleOrder(shifted.copyOfRange(0, at) + added + shifted.copyOfRange(at, shifted.size), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        if (count == 0) return this
        val kept = order.filter { it < indexFrom || it >= indexToExclusive }
            .map { if (it >= indexToExclusive) it - count else it }
            .toIntArray()
        return QueueShuffleOrder(kept, random)
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), random)

    /** A new list: the song the user picked plays first, the rest in random order after it. */
    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder {
        val all = IntArray(insertionCount) { it }
        all.shuffle(random)
        if (startIndex in 0 until insertionCount) {
            val p = all.indexOf(startIndex)
            all[p] = all[0]
            all[0] = startIndex
        }
        return QueueShuffleOrder(all, random)
    }

    /** A moved song keeps its place in the play order; only list indices are renumbered. */
    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        if (count == 0 || indexFrom == newIndexFrom) return this
        val list = (0 until order.size).toMutableList()
        val moved = list.subList(indexFrom, indexToExclusive).toList()
        repeat(count) { list.removeAt(indexFrom) }
        list.addAll(newIndexFrom, moved)
        val newIndexOf = IntArray(order.size).also { m -> list.forEachIndexed { newIdx, oldIdx -> m[oldIdx] = newIdx } }
        return QueueShuffleOrder(IntArray(order.size) { newIndexOf[order[it]] }, random)
    }
}
