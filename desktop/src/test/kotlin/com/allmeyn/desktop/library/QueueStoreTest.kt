package com.allmeyn.desktop.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueStoreTest {
    @Test fun roundTripsShuffledQueueWithOddPaths() {
        val f = File.createTempFile("queue", ".tsv")
        val s = QueueSnapshot(
            queue = listOf("C:\\Music\\b\tx.flac", "C:\\Music\\Sigur Rós\\a.dsf", "C:\\Music\\b\tx.flac"),
            original = listOf("C:\\Music\\Sigur Rós\\a.dsf", "C:\\Music\\b\tx.flac", "C:\\Music\\b\tx.flac"),
            index = 1, positionMs = 123_456, shuffle = true, repeat = "ALL",
        )
        QueueStore.write(f, s)
        assertEquals(s, QueueStore.read(f))
        val plain = s.copy(original = null, shuffle = false, repeat = "OFF")
        QueueStore.write(f, plain)
        assertEquals(plain, QueueStore.read(f))
        f.delete()
    }

    @Test fun noneForMissingWrongHeaderOrEmpty() {
        val f = File.createTempFile("queue", ".tsv")
        f.writeText("allmeyn-queue\t1\nS\t0\tOFF\t0\t0\n")
        assertNull(QueueStore.read(f))
        f.writeText("other\t1\nQ\tx\n")
        assertNull(QueueStore.read(f))
        f.delete()
        assertNull(QueueStore.read(f))
    }
}
