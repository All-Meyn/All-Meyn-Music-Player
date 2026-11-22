package com.allmeyn.desktop.ui

import com.allmeyn.desktop.engine.DspStore
import com.allmeyn.player.dsp.DspParams
import com.allmeyn.player.dsp.DspProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

class DspScreenTest {
    private val default = DspParams().sanitized()

    @Test fun movesProcessorsButVocalStaysFirst() {
        val down = move(default, DspProcessor.COMPANDER, +1)
        assertEquals(listOf(DspProcessor.VOCAL, DspProcessor.BASS, DspProcessor.COMPANDER, DspProcessor.IMPACT), down.order)
        assertEquals(down, move(down, DspProcessor.IMPACT, +1))  // already last
        assertEquals(default, move(default, DspProcessor.COMPANDER, -1))  // can't pass Vocal
        assertEquals(default, move(default, DspProcessor.VOCAL, +1))      // Vocal doesn't move
    }

    @Test fun savedOrderReadsLikeAndroidAndVocalIsPutFirst() {
        val read = DspStore.parseOrder("IMPACT,BASS,UNKNOWN,COMPANDER")
        assertEquals(listOf(DspProcessor.IMPACT, DspProcessor.BASS, DspProcessor.COMPANDER), read)
        assertEquals(DspProcessor.VOCAL, default.copy(order = read).sanitized().order.first())
    }
}
