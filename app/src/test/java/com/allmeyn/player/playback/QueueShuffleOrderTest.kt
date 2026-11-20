package com.allmeyn.player.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class QueueShuffleOrderTest {
    private fun ShuffleOrder.playOrder(): List<Int> {
        val out = mutableListOf<Int>()
        var i = getFirstIndex()
        while (i != C.INDEX_UNSET) { out += i; i = getNextIndex(i) }
        return out
    }

    private fun loaded(n: Int) = QueueShuffleOrder(Random(42)).cloneAndInsert(0, n)

    @Test fun newListIsAPermutation() {
        val o = loaded(10)
        assertEquals((0 until 10).toList(), o.playOrder().sorted())
        assertEquals(o.playOrder().reversed().first(), o.getLastIndex())
        // previous is the inverse of next
        for (i in 0 until 10) { val n = o.getNextIndex(i); if (n != C.INDEX_UNSET) assertEquals(i, o.getPreviousIndex(n)) }
    }

    @Test fun playNextGoesRightAfterCurrent() {
        val o = loaded(8)
        val current = o.playOrder()[3]                 // list index of the song now playing
        val after = o.cloneAndInsert(current + 1, 2)   // "Play next" inserts after it in the list
        val order = after.playOrder()
        val p = order.indexOf(current)
        assertEquals(listOf(current + 1, current + 2), order.subList(p + 1, p + 3))
        assertEquals(10, order.size)
    }

    @Test fun insertAfterLastPlayedSongPlaysLast() {
        val o = loaded(5)
        val last = o.getLastIndex()
        val after = o.cloneAndInsert(last + 1, 1)      // what "Add to queue" does with shuffle on
        assertEquals(last + 1, after.getLastIndex())
    }

    @Test fun playNextWhenCurrentIsLastInList() {
        val o = loaded(8)
        val after = o.cloneAndInsert(8, 1)             // current song is list item 7
        val order = after.playOrder()
        assertEquals(8, order[order.indexOf(7) + 1])
    }

    @Test fun removeKeepsOrderOfTheRest() {
        val o = loaded(6)
        val before = o.playOrder()
        val removed = o.cloneAndRemove(2, 4)           // drop list items 2 and 3
        val expected = before.filter { it !in 2..3 }.map { if (it >= 4) it - 2 else it }
        assertEquals(expected, removed.playOrder())
    }

    @Test fun clearEmpties() {
        assertEquals(0, loaded(4).cloneAndClear().getLength())
        assertEquals(C.INDEX_UNSET, loaded(4).cloneAndClear().getFirstIndex())
    }

    @Test fun setStartsWithPickedSong() {
        val o = QueueShuffleOrder(Random(7)).cloneAndSet(12, 5)
        assertEquals(5, o.getFirstIndex())
        assertEquals((0 until 12).toList(), o.playOrder().sorted())
    }

    @Test fun moveKeepsEachSongsPlace() {
        val o = loaded(6)
        val before = o.playOrder()
        // list [0 1 2 3 4 5] -> move item 1 to position 4 -> list [0 2 3 4 1 5]
        val newIndexOf = intArrayOf(0, 4, 1, 2, 3, 5)
        val after = o.cloneAndMove(1, 2, 4).playOrder()
        assertEquals(before.map { newIndexOf[it] }, after)
    }
}
