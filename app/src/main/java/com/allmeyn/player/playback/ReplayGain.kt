package com.allmeyn.player.playback

import android.content.Context
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * ReplayGain: evens out loudness between songs by scaling their samples down by the gain in the
 * file's tags. Only lowers volume (never boosts, so it can't clip).
 *
 * Applied to the samples themselves, from each song's first sample (so a song change switches
 * gain exactly at the join), and only for Android output: never while the bit-perfect USB driver
 * is on, and never to DoP / native DSD.
 * - Formats Media3 reads tags from (FLAC, MP3, Ogg, M4A, ...): in CaptureAudioSink, from the
 *   format Media3 configures for each song.
 * - Formats we decode (APE, WavPack, DSD -> PCM): in DecodingDataSource, from the file's tags.
 *
 * Tag sources: Vorbis comments (FLAC, Ogg; Opus R128_*_GAIN), ID3 TXXX (MP3, DSF, DFF), iTunes
 * freeform atoms (M4A), APEv2 (APE, WavPack). Pure parsing here; unit-tested.
 */
@UnstableApi
object ReplayGain {

    enum class Mode { OFF, TRACK, ALBUM }

    /** Gains in dB from a file's tags; null when the tag is missing. */
    data class Gains(val trackDb: Float? = null, val albumDb: Float? = null) {
        val isEmpty get() = trackDb == null && albumDb == null
    }

    /** The gain to use: album gain in album mode (track gain if missing), and vice versa. */
    fun pick(g: Gains, mode: Mode): Float? = when (mode) {
        Mode.OFF -> null
        Mode.TRACK -> g.trackDb ?: g.albumDb
        Mode.ALBUM -> g.albumDb ?: g.trackDb
    }

    /** Linear player volume for [db]: 10^(dB/20), at most 1 (no boosting, no clipping). */
    fun volumeFor(db: Float?): Float = if (db == null) 1f else 10.0.pow(db / 20.0).toFloat().coerceIn(0f, 1f)

    /** Gains from tag key/values (keys any case): REPLAYGAIN_*_GAIN ("-6.20 dB") or Opus R128_*_GAIN. */
    fun fromTags(tags: Map<String, String>): Gains {
        val t = tags.mapKeys { it.key.lowercase() }
        // Opus R128 gains: Q7.8 fixed point dB relative to -23 LUFS; ReplayGain's reference is
        // about 5 dB louder (-18 LUFS), hence the +5.
        fun r128(key: String) = t[key]?.trim()?.toIntOrNull()?.let { it / 256f + 5f }
        return Gains(
            trackDb = parseDb(t["replaygain_track_gain"]) ?: r128("r128_track_gain"),
            albumDb = parseDb(t["replaygain_album_gain"]) ?: r128("r128_album_gain"),
        )
    }

    /** Gains from Media3 metadata (as extracted for playback). */
    @Suppress("DEPRECATION") // the flac VorbisComment is the base class of the current one
    fun fromMetadata(metadata: Metadata?): Gains {
        if (metadata == null) return Gains()
        val tags = HashMap<String, String>()
        for (i in 0 until metadata.length()) {
            when (val e = metadata.get(i)) {
                is androidx.media3.extractor.metadata.flac.VorbisComment -> tags[e.key] = e.value
                is TextInformationFrame -> if (e.id == "TXXX" && e.description != null) {
                    e.values.firstOrNull()?.let { tags[e.description!!] = it }
                }
                is InternalFrame -> tags[e.description] = e.text
            }
        }
        return fromTags(tags)
    }

    /** "-6.20 dB", "+1.5", "-6,2 dB" -> dB; null if unreadable. */
    fun parseDb(s: String?): Float? {
        val v = s?.trim()?.removeSuffix("dB")?.removeSuffix("DB")?.trim()?.replace(',', '.') ?: return null
        return v.removePrefix("+").toFloatOrNull()?.takeIf { it.isFinite() && it in -60f..60f }
    }

    // ---- setting + what's applied now (read by the UI) ----

    private const val PREFS = "playback"
    private const val KEY_MODE = "replaygain"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): Mode =
        runCatching { Mode.valueOf(prefs(context).getString(KEY_MODE, Mode.OFF.name)!!) }.getOrDefault(Mode.OFF)

    fun setMode(context: Context, mode: Mode) {
        liveMode = mode
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** The current mode, readable from the playback thread. Set by [init] and [setMode]. */
    @Volatile var liveMode: Mode = Mode.OFF
        private set

    fun init(context: Context) {
        liveMode = mode(context)
    }

    /**
     * Multiplies the PCM samples in [buf] (position..limit, little-endian) by [gain], in place.
     * [encoding] is a Media3 C.ENCODING_PCM_* constant; others are left alone. Rounds, no dither.
     */
    fun scale(buf: ByteBuffer, encoding: Int, gain: Float) {
        if (gain >= 1f || buf.isReadOnly) return
        val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val end = buf.limit()
        var i = buf.position()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> while (i + 2 <= end) {
                b.putShort(i, (b.getShort(i) * gain).roundToInt().coerceIn(-32768, 32767).toShort()); i += 2
            }
            C.ENCODING_PCM_24BIT -> while (i + 3 <= end) {
                val v = (b.get(i).toInt() and 0xFF) or ((b.get(i + 1).toInt() and 0xFF) shl 8) or (b.get(i + 2).toInt() shl 16)
                val s = (v * gain).roundToInt().coerceIn(-8_388_608, 8_388_607)
                b.put(i, s.toByte()); b.put(i + 1, (s shr 8).toByte()); b.put(i + 2, (s shr 16).toByte()); i += 3
            }
            C.ENCODING_PCM_32BIT -> while (i + 4 <= end) {
                b.putInt(i, (b.getInt(i) * gain.toDouble()).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()); i += 4
            }
            C.ENCODING_PCM_FLOAT -> while (i + 4 <= end) {
                b.putFloat(i, b.getFloat(i) * gain); i += 4
            }
        }
    }

    const val KEY = KEY_MODE

    /** e.g. "-6.2 dB (album)", or null when off. Set by PlaybackService for the UI. */
    @Volatile var applied: String? = null

    /** Gains of files our decoders opened (by URI), for the UI label. */
    val decodedGains = java.util.concurrent.ConcurrentHashMap<String, Gains>()
}
