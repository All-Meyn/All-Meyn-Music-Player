package com.allmeyn.player.usb

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Maps a UAC2 volume range (values in 1/256 dB, signed 16-bit) to user-facing steps 0..[maxStep].
 * Step 0 is the bottom of the usable range (and mute when the DAC supports mute); [maxStep] is the
 * DAC's maximum. Steps are even in dB, at least 0.5 dB apart, at most [MAX_STEPS] of them, and
 * the bottom is capped at 80 dB below maximum (below that is inaudible on headphones).
 */
class VolumeScale(minRaw: Int, val maxRaw: Int, resRaw: Int) {

    /** Lowest raw value a step maps to. */
    val floorRaw: Int
    val maxStep: Int

    init {
        // UAC2: -32768 (0x8000) as MIN means "-infinity"; treat as the lowest finite value.
        val min = if (minRaw <= -32768) -32767 else minRaw
        require(maxRaw > min) { "empty volume range $minRaw..$maxRaw" }
        floorRaw = max(min, maxRaw - 80 * 256)
        val step = max(resRaw, 128) // at least 0.5 dB per step
        val count = (maxRaw - floorRaw) / step
        maxStep = count.coerceIn(1, MAX_STEPS)
    }

    fun rawForStep(step: Int): Int {
        val s = step.coerceIn(0, maxStep)
        return floorRaw + ((maxRaw - floorRaw).toLong() * s / maxStep).toInt()
    }

    fun stepForRaw(raw: Int): Int {
        val r = raw.coerceIn(floorRaw, maxRaw)
        return ((r - floorRaw).toDouble() * maxStep / (maxRaw - floorRaw)).roundToInt()
    }

    companion object {
        const val MAX_STEPS = 100

        fun rawToDb(raw: Int): Float = raw / 256f
    }
}
