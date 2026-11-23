package com.allmeyn.desktop.ui

import com.allmeyn.desktop.engine.DspStore
import com.allmeyn.player.dsp.DspParams
import com.allmeyn.player.dsp.DspProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

class DspScreenTest {
    private val default = DspParams().sanitized()

    @Test fun movesProcessorsButVocalStaysFirst() {
        // default after Vocal: COMPANDER, BASS, IMPACT
        val moved = moveTo(default, 0, 2)
        assertEquals(listOf(DspProcessor.VOCAL, DspProcessor.BASS, DspProcessor.IMPACT, DspProcessor.COMPANDER), moved.order)
        assertEquals(listOf(DspProcessor.VOCAL, DspProcessor.IMPACT, DspProcessor.COMPANDER, DspProcessor.BASS), moveTo(default, 2, 0).order)
        assertEquals(default, moveTo(default, 1, 1))
        assertEquals(default, moveTo(default, 0, 3))  // out of range: unchanged
    }

    @Test fun savedOrderReadsLikeAndroidAndVocalIsPutFirst() {
        val read = DspStore.parseOrder("IMPACT,BASS,UNKNOWN,COMPANDER")
        assertEquals(listOf(DspProcessor.IMPACT, DspProcessor.BASS, DspProcessor.COMPANDER), read)
        assertEquals(DspProcessor.VOCAL, default.copy(order = read).sanitized().order.first())
    }
}
