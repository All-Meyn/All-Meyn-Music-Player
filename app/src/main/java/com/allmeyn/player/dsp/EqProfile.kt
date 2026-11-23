package com.allmeyn.player.dsp

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Pure Kotlin (no Android): also compiled into the Windows app (desktop/build.gradle.kts, sharedSources).

/** Headphone EQ filter types; [id] matches the native EqType, [apo] is EqualizerAPO's name. */
enum class EqFilterType(val id: Int, val apo: String, val label: String, val hasGain: Boolean) {
    PEAK(0, "PK", "Peak", true),
    LOW_SHELF(1, "LSC", "Low shelf", true),
    HIGH_SHELF(2, "HSC", "High shelf", true),
    LOW_PASS(3, "LPQ", "Low pass", false),
    HIGH_PASS(4, "HPQ", "High pass", false),
}

data class EqBand(
    val type: EqFilterType = EqFilterType.PEAK,
    val freqHz: Float = 1000f,   // 10..24000
    val gainDb: Float = 0f,      // -30..+30 (peak and shelves)
    val q: Float = 0.71f,        // 0.05..50
) {
    fun sanitized() = copy(freqHz = freqHz.coerceIn(10f, 24000f), gainDb = gainDb.coerceIn(-30f, 30f), q = q.coerceIn(0.05f, 50f))
}

/**
 * A headphone EQ: a preamp and up to [MAX_BANDS] filters, the same filters AutoEq and EqualizerAPO
 * use (RBJ cookbook). [name] is what the user calls it, e.g. "Sennheiser HD 650 (AutoEq)".
 */
data class EqProfile(
    val name: String = "",
    val preampDb: Float = 0f,    // -30..+10
    val bands: List<EqBand> = emptyList(),
) {
    fun sanitized() = copy(
        name = name.trim().take(80),
        preampDb = preampDb.coerceIn(-30f, 10f),
        bands = bands.take(MAX_BANDS).map { it.sanitized() },
    )

    /** EqualizerAPO / AutoEq "ParametricEQ.txt" text (with the name as a comment). */
    fun toApoText(): String = buildString {
        if (name.isNotBlank()) append("# ").append(name.replace('\n', ' ')).append('\n')
        append("Preamp: ").append(num(preampDb, 1)).append(" dB\n")
        bands.forEachIndexed { i, b ->
            append("Filter ").append(i + 1).append(": ON ").append(b.type.apo)
                .append(" Fc ").append(num(b.freqHz, if (b.freqHz < 100) 1 else 0)).append(" Hz")
            if (b.type.hasGain) append(" Gain ").append(num(b.gainDb, 1)).append(" dB")
            append(" Q ").append(num(b.q, 2)).append('\n')
        }
    }

    /** Magnitude response in dB (preamp included) at [freqs], as the native chain computes it. */
    fun responseDb(freqs: FloatArray, sampleRate: Double = 48000.0): FloatArray {
        val coeffs = bands.map { biquad(it, sampleRate) }
        return FloatArray(freqs.size) { i ->
            var sum = preampDb.toDouble()
            for (c in coeffs) sum += c.magnitudeDb(freqs[i].toDouble(), sampleRate)
            sum.toFloat()
        }
    }

    companion object {
        const val MAX_BANDS = 20  // native kMaxEqBands

        private fun num(v: Float, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", v)

        /** Log-spaced frequencies 20 Hz - 20 kHz for drawing the curve. */
        fun plotFrequencies(points: Int = 160) = FloatArray(points) { i -> (20.0 * 1000.0.pow(i / (points - 1.0))).toFloat() }
    }
}

/** Result of reading an EQ file: the profile, plus what couldn't be used. */
data class EqImport(val profile: EqProfile, val notes: List<String>)

/**
 * Reads EqualizerAPO / AutoEq parametric EQ text ("ParametricEQ.txt", "FixedBandEQ.txt", Peace,
 * Wavelet exports): `Preamp: -6.1 dB` and lines like `Filter 1: ON PK Fc 105 Hz Gain 6.4 dB Q 0.70`.
 * Types PK/PEQ/Modal, LSC/LS, HSC/HS, LPQ/LP, HPQ/HP; Q given as `Q x` or `BW Oct x`. Several
 * Preamp lines add up. Channel-specific sections are applied to both channels (noted).
 * Throws IllegalArgumentException with a readable reason if nothing usable is found.
 */
object EqParser {
    private val filterLine = Regex("""^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Za-z]+)\s+(.*)$""", RegexOption.IGNORE_CASE)
    private val preampLine = Regex("""^\s*Preamp\s*:\s*([-+]?[0-9]*[.,]?[0-9]+)\s*dB""", RegexOption.IGNORE_CASE)
    private fun value(rest: String, key: String): Float? =
        Regex("""(?i)\b$key\s+([-+]?[0-9]*[.,]?[0-9]+)""").find(rest)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()

    fun parse(text: String, fallbackName: String = ""): EqImport {
        val notes = mutableListOf<String>()
        if (text.lineSequence().any { it.trimStart().startsWith("GraphicEQ:", ignoreCase = true) }) {
            throw IllegalArgumentException("This is a GraphicEQ file: it needs the convolution EQ (coming next). Use the ParametricEQ.txt file for now.")
        }
        var preamp = 0f
        var name = ""
        val bands = mutableListOf<EqBand>()
        var skippedOff = 0
        var unknown = 0
        var channels = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#")) { if (name.isEmpty()) name = line.removePrefix("#").trim(); continue }
            if (line.startsWith("Channel:", ignoreCase = true)) { channels = true; continue }
            val pre = preampLine.find(line)
            if (pre != null) { preamp += pre.groupValues[1].replace(',', '.').toFloat(); continue }
            val m = filterLine.find(line) ?: continue
            if (m.groupValues[1].equals("OFF", true)) { skippedOff++; continue }
            val type = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ", "MODAL" -> EqFilterType.PEAK
                "LSC", "LS", "LSQ" -> EqFilterType.LOW_SHELF
                "HSC", "HS", "HSQ" -> EqFilterType.HIGH_SHELF
                "LPQ", "LP" -> EqFilterType.LOW_PASS
                "HPQ", "HP" -> EqFilterType.HIGH_PASS
                else -> { unknown++; null }
            } ?: continue
            val rest = m.groupValues[3]
            val fc = value(rest, "Fc") ?: run { unknown++; null } ?: continue
            val gain = value(rest, "Gain") ?: 0f
            val q = value(rest, "Q") ?: value(rest, "BW\\s+Oct")?.let(::octavesToQ) ?: 0.71f
            bands += EqBand(type, fc, gain, q)
        }
        if (bands.isEmpty() && preamp == 0f) throw IllegalArgumentException("No EqualizerAPO filters found (lines like \"Filter 1: ON PK Fc 100 Hz Gain -2 dB Q 1.0\").")
        if (bands.size > EqProfile.MAX_BANDS) notes += "Only the first ${EqProfile.MAX_BANDS} of ${bands.size} filters are used."
        if (channels) notes += "Channel sections are applied to both channels."
        if (skippedOff > 0) notes += "$skippedOff filter(s) marked OFF were left out."
        if (unknown > 0) notes += "$unknown filter(s) of unsupported types were left out."
        val profile = EqProfile(name.ifEmpty { fallbackName }, preamp, bands).sanitized()
        // Boosts above the preamp can clip: say so (Soft Clip or a lower preamp helps).
        val peak = profile.responseDb(EqProfile.plotFrequencies(400)).max()
        if (peak > 0.5f) notes += String.format(Locale.ROOT, "Peaks at %+.1f dB: lower the preamp by that or turn on Soft Clip.", peak)
        return EqImport(profile, notes)
    }

    /** Bandwidth in octaves to Q (EqualizerAPO "BW Oct"). */
    fun octavesToQ(n: Float): Float {
        val p = 2.0.pow(n.toDouble())
        return (sqrt(p) / (p - 1)).toFloat()
    }
}

/**
 * The saved profiles, as one text: EqualizerAPO text per profile, each starting with a
 * `# Profile: <name>` line. Readable and editable by hand.
 */
object EqLibrary {
    private const val HEADER = "# Profile: "

    fun encode(profiles: List<EqProfile>): String = profiles.joinToString("\n") { p ->
        HEADER + p.name.replace('\n', ' ') + "\n" + p.copy(name = "").toApoText()
    }

    fun decode(text: String): List<EqProfile> {
        val out = mutableListOf<EqProfile>()
        var name: String? = null
        val body = StringBuilder()
        fun flush() {
            val n = name ?: return
            runCatching { EqParser.parse(body.toString(), n).profile.copy(name = n) }.getOrNull()?.let { out += it }
            body.clear()
        }
        for (line in text.lineSequence()) {
            if (line.startsWith(HEADER)) { flush(); name = line.removePrefix(HEADER).trim() } else body.append(line).append('\n')
        }
        flush()
        return out
    }
}

/** RBJ-cookbook biquad for [band] at [fs], normalised (a0 = 1); the native EqFilter's maths. */
private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun magnitudeDb(hz: Double, fs: Double): Double {
        val w = 2 * PI * hz / fs
        val cr = cos(w); val ci = -sin(w); val c2r = cos(2 * w); val c2i = -sin(2 * w)
        val nr = b0 + b1 * cr + b2 * c2r; val ni = b1 * ci + b2 * c2i
        val dr = 1 + a1 * cr + a2 * c2r; val di = a1 * ci + a2 * c2i
        return 10 * log10(max((nr * nr + ni * ni) / (dr * dr + di * di), 1e-30))
    }
}

private fun biquad(band: EqBand, fs: Double): Biquad {
    val fc = band.freqHz.toDouble().coerceIn(10.0, 0.49 * fs)
    val q = band.q.toDouble().coerceIn(0.05, 50.0)
    val a = 10.0.pow(band.gainDb.toDouble().coerceIn(-30.0, 30.0) / 40.0)
    val w = 2 * PI * fc / fs
    val c = cos(w); val alpha = sin(w) / (2 * q); val sa = sqrt(a)
    val (b, d) = when (band.type) {
        EqFilterType.LOW_SHELF -> doubleArrayOf(a * ((a + 1) - (a - 1) * c + 2 * sa * alpha), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - 2 * sa * alpha)) to
            doubleArrayOf((a + 1) + (a - 1) * c + 2 * sa * alpha, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - 2 * sa * alpha)
        EqFilterType.HIGH_SHELF -> doubleArrayOf(a * ((a + 1) + (a - 1) * c + 2 * sa * alpha), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - 2 * sa * alpha)) to
            doubleArrayOf((a + 1) - (a - 1) * c + 2 * sa * alpha, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - 2 * sa * alpha)
        EqFilterType.LOW_PASS -> doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2) to doubleArrayOf(1 + alpha, -2 * c, 1 - alpha)
        EqFilterType.HIGH_PASS -> doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2) to doubleArrayOf(1 + alpha, -2 * c, 1 - alpha)
        EqFilterType.PEAK -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a) to doubleArrayOf(1 + alpha / a, -2 * c, 1 - alpha / a)
    }
    return Biquad(b[0] / d[0], b[1] / d[0], b[2] / d[0], d[1] / d[0], d[2] / d[0])
}
