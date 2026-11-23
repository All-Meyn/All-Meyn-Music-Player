package com.allmeyn.desktop.ui

import com.allmeyn.player.library.Track
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueOrderTest {
    private fun t(n: Int) = Track(
        uri = "C:\\Music\\$n.flac", root = "C:\\Music", folder = "Music", fileName = "$n.flac", title = "Song $n", artist = "A", album = "B",
        albumArtist = "", trackNo = n, discNo = 1, year = 2000, durationMs = 1000, format = "FLAC", sampleRate = 44100, bits = 16,
        art = "", modified = 0, size = 0,
    )
    private val list = (1..8).map(::t)

    @Test fun shuffleKeepsThePlayingSongFirstAndEverySong() {
        val s = QueueOrder.shuffled(list, 3, Random(7))
        assertEquals(list[3], s[0])
        assertEquals(list.toSet(), s.toSet())
        assertEquals(list.size, s.size)
    }

    @Test fun unshuffleRestoresOrderKeepsAddedSongsAndFindsThePlayingOne() {
        val shuffled = QueueOrder.shuffled(list, 2, Random(1))
        val extra = t(99)
        val queue = (shuffled - list[5]) + extra  // removed one, added one while shuffled
        val (back, at) = QueueOrder.unshuffled(queue, 0, list)
        assertEquals((list - list[5]) + extra, back)
        assertEquals(list[2], back[at])
    }

    @Test fun duplicatesSurviveUnshuffle() {
        val withDup = list + list[0]
        val (back, _) = QueueOrder.unshuffled(QueueOrder.shuffled(withDup, 0, Random(3)), 0, withDup)
        assertEquals(withDup, back)
    }

    @Test fun playingSongFollowsAMove() {
        assertEquals(5, QueueOrder.indexAfterMove(2, 2, 5))  // the playing song itself
        assertEquals(1, QueueOrder.indexAfterMove(2, 0, 4))  // one from before it went after
        assertEquals(3, QueueOrder.indexAfterMove(2, 6, 1))  // one from after it went before
        assertEquals(2, QueueOrder.indexAfterMove(2, 4, 6))  // both after: unchanged
    }

    @Test fun repeatRules() {
        assertEquals(3, QueueOrder.autoNext(2, 8, RepeatMode.OFF))
        assertNull(QueueOrder.autoNext(7, 8, RepeatMode.OFF))
        assertEquals(0, QueueOrder.autoNext(7, 8, RepeatMode.ALL))
        assertEquals(7, QueueOrder.autoNext(7, 8, RepeatMode.ONE))   // the same song again at its end
        assertNull(QueueOrder.manualNext(7, 8, RepeatMode.ONE))      // Next moves on (none after the last)
        assertEquals(0, QueueOrder.manualNext(7, 8, RepeatMode.ALL))
        assertEquals(7, QueueOrder.manualPrevious(0, 8, RepeatMode.ALL))
        assertNull(QueueOrder.manualPrevious(0, 8, RepeatMode.OFF))
        assertNull(QueueOrder.autoNext(-1, 0, RepeatMode.ALL))
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())
    }
}
