package com.allmeyn.player.dsp

import kotlin.math.roundToInt

// Pure Kotlin (no Android): also compiled into the Windows app (desktop/build.gradle.kts, sharedSources).

/** The processors of the chain; [id] matches the native Processor enum. */
enum class DspProcessor(val id: Int, val label: String) {
    COMPANDER(0, "Compressor / Expander"),
    BASS(1, "Bass Restoration"),
    IMPACT(2, "Impact Restoration"),
    VOCAL(3, "Vocal & Detail Restoration"),
}

/**
 * All DSP settings. Ranges follow Tetra-Expand, except [expansionPercent], which also goes
 * negative for compression. [enabled] is the master switch: off = untouched audio (bit-perfect
 * through the USB driver), and DSD plays as native DSD / DoP again.
 */
data class DspParams(
    val enabled: Boolean = false,
    val companderOn: Boolean = true,
    val expansionPercent: Float = 25f,   // -50..+50
    val transitionDb: Float = -20f,      // -40..0
    val hfTransitionDb: Float = 0f,      // -10..+10
    val bassOn: Boolean = false,
    val bassPercent: Float = 50f,        // 0..100
    val bassTransitionDb: Float = -20f,  // -40..0
    val impactOn: Boolean = false,
    val impactPercent: Float = 50f,      // 0..100
    val impactReleaseMs: Float = 200f,   // 25..1500
    val vocalOn: Boolean = false,
    val presencePercent: Float = 50f,    // 0..100
    val detailPercent: Float = 50f,      // 0..100
    val airPercent: Float = 25f,         // 0..100
    val volumeDb: Float = 0f,            // -40..+10
    val softClip: Boolean = false,
    /** Headphone EQ (parametric, after the processors and before Volume / Soft Clip). */
    val eqOn: Boolean = false,
    val eq: EqProfile = EqProfile(),
    /** Convolution EQ (right after the parametric one): an impulse response or a GraphicEQ. */
    val convOn: Boolean = false,
    val conv: ConvolutionFilter = ConvolutionFilter(),
    val convGainDb: Float = 0f,          // -30..+10
    val order: List<DspProcessor> = listOf(DspProcessor.VOCAL, DspProcessor.COMPANDER, DspProcessor.BASS, DspProcessor.IMPACT),
) {
    /**
     * Clamped to the valid ranges; the order fixed up to contain each processor once, with Vocal &
     * Detail Restoration always first.
     */
    fun sanitized(): DspParams {
        val fixedOrder = listOf(DspProcessor.VOCAL) +
            (order.distinct() + DspProcessor.entries).distinct().filter { it != DspProcessor.VOCAL }
        return copy(
            expansionPercent = expansionPercent.coerceIn(-50f, 50f),
            transitionDb = transitionDb.coerceIn(-40f, 0f),
            hfTransitionDb = hfTransitionDb.coerceIn(-10f, 10f),
            bassPercent = bassPercent.coerceIn(0f, 100f),
            bassTransitionDb = bassTransitionDb.coerceIn(-40f, 0f),
            impactPercent = impactPercent.coerceIn(0f, 100f),
            impactReleaseMs = impactReleaseMs.coerceIn(25f, 1500f),
            presencePercent = presencePercent.coerceIn(0f, 100f),
            detailPercent = detailPercent.coerceIn(0f, 100f),
            airPercent = airPercent.coerceIn(0f, 100f),
            volumeDb = volumeDb.coerceIn(-40f, 10f),
            eq = eq.sanitized(),
            convGainDb = convGainDb.coerceIn(-30f, 10f),
            order = fixedOrder,
        )
    }

    /** Whether the chain changes the audio at all (master on and something to do). */
    val processes: Boolean get() = enabled && (companderOn || bassOn || impactOn || vocalOn || volumeDb != 0f || softClip || eqActive || convActive)

    /** The convolution EQ changes the audio (on, with a filter). */
    val convActive: Boolean get() = convOn && conv.isSet

    /** The headphone EQ changes the audio (on, with filters or a preamp). */
    val eqActive: Boolean get() = eqOn && (eq.bands.isNotEmpty() || eq.preampDb != 0f)

    /** The layout NativeDsp.setParams expects. */
    fun toNativeValues() = floatArrayOf(
        if (companderOn) 1f else 0f, expansionPercent, transitionDb, hfTransitionDb,
        if (bassOn) 1f else 0f, bassPercent, bassTransitionDb,
        if (impactOn) 1f else 0f, impactPercent, impactReleaseMs,
        volumeDb, if (softClip) 1f else 0f,
        if (vocalOn) 1f else 0f, presencePercent, detailPercent, airPercent,
        // 16..: headphone EQ: on, preamp, band count, then (type, Hz, dB, Q) per band
        if (eqOn) 1f else 0f, eq.preampDb, eq.bands.size.toFloat(),
        *eq.bands.flatMap { listOf(it.type.id.toFloat(), it.freqHz, it.gainDb, it.q) }.toFloatArray(),
        // then: convolution on (the filter itself goes separately: NativeDsp.setConvolution)
        if (convActive) 1f else 0f,
    )

    fun toNativeOrder() = order.map { it.id }.toIntArray()

    /** "50 % (ratio 1:1.25 below 150 Hz)". (Numbers formatted on their own: a literal "%" in a format string crashes.) */
    val bassLabel: String get() =
        "${bassPercent.roundToInt()} % (ratio 1:${"%.2f".format(1f + bassPercent / 100f * 0.5f)} below 150 Hz)"

    /** "50 % (up to +6 dB)". */
    val impactLabel: String get() = "${impactPercent.roundToInt()} % (up to +${"%.0f".format(impactPercent / 100f * 12f)} dB)"

    /** Compression/expansion ratio for display: "1:1.25" (expand) or "1.33:1" (compress). */
    val ratioLabel: String get() {
        val r = 1f + expansionPercent / 100f
        return when {
            r >= 1f -> "1:%.2f".format(r)
            else -> "%.2f:1".format(1f / r)
        }
    }
}

/** Meter readings from the playback thread (NativeDsp layout). */
data class DspMeters(
    val companderDb: FloatArray = FloatArray(4),
    val bassDb: Float = 0f,
    val impactDb: FloatArray = FloatArray(4),
    val outputPeak: Float = 0f,
    val vocalDb: Float = 0f,
    /** Treble cutoff Air is working with; 0 = full-range source. */
    val airCutoffHz: Float = 0f,
) {
    companion object {
        fun from(m: FloatArray) = DspMeters(m.copyOfRange(0, 4), m[4], m.copyOfRange(5, 9), m[9], m[10], m[11])
    }
}
