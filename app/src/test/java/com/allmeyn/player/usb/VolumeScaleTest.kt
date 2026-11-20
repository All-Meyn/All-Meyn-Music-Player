package com.allmeyn.player.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeScaleTest {

    @Test fun typicalDacRange_minus127To0_1dB() {
        val s = VolumeScale(minRaw = -127 * 256, maxRaw = 0, resRaw = 256)
        assertEquals(-80 * 256, s.floorRaw) // capped at -80 dB
        assertEquals(80, s.maxStep)         // 1 dB per step
        assertEquals(0, s.rawForStep(80))
        assertEquals(-80 * 256, s.rawForStep(0))
        assertEquals(-40 * 256, s.rawForStep(40))
    }

    @Test fun roundTripEveryStep() {
        val s = VolumeScale(-100 * 256, 6 * 256, 128)
        for (step in 0..s.maxStep) assertEquals(step, s.stepForRaw(s.rawForStep(step)))
    }

    @Test fun fineResolutionCappedAt100Steps() {
        val s = VolumeScale(-127 * 256, 0, 1) // 1/256 dB resolution
        assertEquals(VolumeScale.MAX_STEPS, s.maxStep)
    }

    @Test fun minusInfinityMinIsHandled() {
        val s = VolumeScale(-32768, 0, 256)
        assertTrue(s.floorRaw > -32768)
        assertEquals(80, s.maxStep)
    }

    @Test fun smallRangeStillHasSteps() {
        val s = VolumeScale(-10 * 256, 0, 256)
        assertEquals(-10 * 256, s.floorRaw)
        assertEquals(10, s.maxStep)
    }

    @Test fun outOfRangeIsClamped() {
        val s = VolumeScale(-60 * 256, 0, 256)
        assertEquals(0, s.stepForRaw(-32767))
        assertEquals(s.maxStep, s.stepForRaw(10 * 256))
        assertEquals(0, s.rawForStep(999))
        assertEquals(-60 * 256, s.rawForStep(-5))
    }
}
