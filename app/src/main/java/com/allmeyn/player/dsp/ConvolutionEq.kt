package com.allmeyn.player.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

// Pure Kotlin (no Android): also compiled into the Windows app (desktop/build.gradle.kts, sharedSources).

/** What the convolution EQ convolves with. */
enum class ConvKind { NONE, IMPULSE, GRAPHIC }

data class GraphicPoint(val hz: Float, val db: Float)

/**
 * The convolution EQ's filter: an impulse response (a WAV the user imported, kept as a copy in the
 * app's storage under [irFile]) or GraphicEQ points (turned into a minimum-phase filter by the
 * engine, as AutoEq does). [info] describes it ("WAV · 48 kHz · stereo · 4,800 taps").
 */
data class ConvolutionFilter(
    val kind: ConvKind = ConvKind.NONE,
    val name: String = "",
    val irFile: String = "",
    val graphic: List<GraphicPoint> = emptyList(),
    val info: String = "",
) {
    val isSet: Boolean get() = kind != ConvKind.NONE
}

/** An impulse response read from a WAV: per channel (1 or 2), at [rate]. */
class WavIr(val rate: Int, val channels: List<FloatArray>) {
    val frames: Int get() = channels.firstOrNull()?.size ?: 0
    val info: String
        get() = String.format(Locale.ROOT, "WAV · %s kHz · %s · %,d taps",
            if (rate % 1000 == 0) "${rate / 1000}" else String.format(Locale.ROOT, "%.1f", rate / 1000.0),
            if (channels.size == 2) "stereo" else "mono", frames)

    /** Magnitude in dB at [freqs] (left channel), for the curve. */
    fun responseDb(freqs: FloatArray, gainDb: Float = 0f): FloatArray {
        val h = channels.firstOrNull() ?: return FloatArray(freqs.size)
        val n = minOf(h.size, rate)  // the engine uses at most 1 s
        return FloatArray(freqs.size) { i ->
            val w = 2 * PI * freqs[i] / rate
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) { re += h[t] * cos(w * t); im -= h[t] * sin(w * t) }
            (10 * log10(max(re * re + im * im, 1e-20)) + gainDb).toFloat()
        }
    }
}

object WavReader {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_SECONDS = 10

    /**
     * Reads a WAV impulse response: PCM 16/24/32-bit or float 32/64, mono or stereo (more
     * channels: the first two), WAVE_FORMAT_EXTENSIBLE too. Throws IllegalArgumentException with a
     * readable reason otherwise.
     */
    fun read(bytes: ByteArray): WavIr {
        fun bad(why: String): Nothing = throw IllegalArgumentException(why)
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") bad("Not a WAV file.")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = -1; var channels = 0; var rate = 0; var bits = 0
        var dataAt = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = b.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (len < 16 || body + 16 > bytes.size) bad("Damaged WAV header.")
                    format = b.getShort(body).toInt() and 0xFFFF
                    channels = b.getShort(body + 2).toInt() and 0xFFFF
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt() and 0xFFFF
                    if (format == 0xFFFE && len >= 26 && body + 26 <= bytes.size) format = b.getShort(body + 24).toInt() and 0xFFFF
                }
                "data" -> { dataAt = body; dataLen = minOf(len, (bytes.size - body).toLong()).toInt() }
            }
            if (dataAt >= 0 && format >= 0) break
            if (len > bytes.size) break
            pos = body + len.toInt() + (len.toInt() and 1)
        }
        if (format < 0 || dataAt < 0) bad("Damaged WAV: no format or data.")
        if (channels !in 1..8 || rate !in 8000..768000) bad("Unsupported WAV: $channels channels at $rate Hz.")
        val bytesPer = bits / 8
        val sample: (Int) -> Float = when {
            format == 1 && bits == 16 -> { i -> b.getShort(i) / 32768f }
            format == 1 && bits == 24 -> { i -> ((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() shl 16)) / 8388608f }
            format == 1 && bits == 32 -> { i -> (b.getInt(i) / 2147483648.0).toFloat() }
            format == 3 && bits == 32 -> { i -> b.getFloat(i) }
            format == 3 && bits == 64 -> { i -> b.getDouble(i).toFloat() }
            else -> bad("Unsupported WAV sample format ($bits-bit, format $format).")
        }
        val frameBytes = bytesPer * channels
        val frames = minOf(dataLen / frameBytes, rate * MAX_SECONDS)
        if (frames <= 0) bad("The WAV has no samples.")
        val used = minOf(channels, 2)
        val out = List(used) { c -> FloatArray(frames) { f -> sample(dataAt + f * frameBytes + c * bytesPer) } }
        return WavIr(rate, out)
    }
}

object GraphicEqParser {
    /** EqualizerAPO GraphicEQ ("GraphicEQ: 20 -0.2; 21 -0.2; …"), points sorted by frequency. */
    fun parse(text: String): List<GraphicPoint> {
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("GraphicEQ:", ignoreCase = true) }
            ?: throw IllegalArgumentException("No GraphicEQ line found.")
        val points = line.substringAfter(':').split(';').mapNotNull { part ->
            val f = part.trim().split(Regex("\\s+"))
            if (f.size < 2) null else {
                val hz = f[0].replace(',', '.').toFloatOrNull()
                val db = f[1].replace(',', '.').toFloatOrNull()
                if (hz == null || db == null || hz <= 0f || !db.isFinite()) null else GraphicPoint(hz, db.coerceIn(-40f, 40f))
            }
        }.sortedBy { it.hz }.distinctBy { it.hz }
        if (points.size < 2) throw IllegalArgumentException("The GraphicEQ line has fewer than two points.")
        // Kept to MAX_POINTS (evenly picked, ends kept) so the saved line stays small (Windows
        // keeps settings values under 8 KB). AutoEq's have ~130.
        if (points.size <= MAX_POINTS) return points
        return List(MAX_POINTS) { i -> points[(i.toLong() * (points.size - 1) / (MAX_POINTS - 1)).toInt()] }
    }

    const val MAX_POINTS = 400

    fun toText(points: List<GraphicPoint>): String =
        "GraphicEQ: " + points.joinToString("; ") { String.format(Locale.ROOT, "%s %.1f", trim(it.hz), it.db) }

    private fun trim(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(Locale.ROOT, "%.1f", v)

    /** dB at [freqs], interpolated on log frequency (as the engine does), plus [gainDb]. */
    fun responseDb(points: List<GraphicPoint>, freqs: FloatArray, gainDb: Float = 0f): FloatArray = FloatArray(freqs.size) { i ->
        val f = freqs[i]
        val v = when {
            points.isEmpty() -> 0f
            f <= points.first().hz -> points.first().db
            f >= points.last().hz -> points.last().db
            else -> {
                val k = points.indexOfFirst { it.hz > f }
                val a = points[k - 1]; val b = points[k]
                val t = (kotlin.math.ln(f / a.hz) / kotlin.math.ln(b.hz / a.hz))
                a.db + t * (b.db - a.db)
            }
        }
        v + gainDb
    }
}

/** What an imported EQ file turned out to be. */
sealed interface EqFile {
    data class Parametric(val import: EqImport) : EqFile
    data class Graphic(val name: String, val points: List<GraphicPoint>) : EqFile
    class Impulse(val name: String, val ir: WavIr, val bytes: ByteArray) : EqFile
}

object EqFiles {
    /**
     * Reads any EQ file the app takes: a WAV impulse response, an EqualizerAPO GraphicEQ, or a
     * parametric EQ (ParametricEQ.txt / FixedBandEQ.txt). [fileName] names it (AutoEq's suffixes
     * dropped). Throws IllegalArgumentException with a readable reason.
     */
    fun read(bytes: ByteArray, fileName: String): EqFile {
        val name = displayName(fileName)
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") return EqFile.Impulse(name, WavReader.read(bytes), bytes)
        val text = bytes.decodeToString()
        if (text.lineSequence().any { it.trimStart().startsWith("GraphicEQ:", ignoreCase = true) }) return EqFile.Graphic(name, GraphicEqParser.parse(text))
        return EqFile.Parametric(EqParser.parse(text, name))
    }

    fun displayName(fileName: String): String = fileName.substringBeforeLast('.')
        .removeSuffix(" ParametricEQ").removeSuffix(" FixedBandEQ").removeSuffix(" GraphicEQ")
        .replace(Regex(" minimum phase \\d+Hz$"), "").replace(Regex(" linear phase \\d+Hz$"), "")

    /** A safe file name for a stored copy. */
    fun storageName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").trim().take(80).ifEmpty { "impulse" } + ".wav"
}
