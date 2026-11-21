package com.allmeyn.player.dsp

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/** The three processors of the chain; [id] matches the native Processor enum. */
enum class DspProcessor(val id: Int, val label: String) {
    COMPANDER(0, "Compressor / Expander"),
    BASS(1, "Bass Restoration"),
    IMPACT(2, "Impact Restoration"),
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
    val volumeDb: Float = 0f,            // -40..+10
    val softClip: Boolean = false,
    val order: List<DspProcessor> = DspProcessor.entries.toList(),
) {
    /** Clamped to the valid ranges; the order fixed up to contain each processor once. */
    fun sanitized(): DspParams {
        val fixedOrder = (order.distinct() + DspProcessor.entries).distinct()
        return copy(
            expansionPercent = expansionPercent.coerceIn(-50f, 50f),
            transitionDb = transitionDb.coerceIn(-40f, 0f),
            hfTransitionDb = hfTransitionDb.coerceIn(-10f, 10f),
            bassPercent = bassPercent.coerceIn(0f, 100f),
            bassTransitionDb = bassTransitionDb.coerceIn(-40f, 0f),
            impactPercent = impactPercent.coerceIn(0f, 100f),
            impactReleaseMs = impactReleaseMs.coerceIn(25f, 1500f),
            volumeDb = volumeDb.coerceIn(-40f, 10f),
            order = fixedOrder,
        )
    }

    /** Whether the chain changes the audio at all (master on and something to do). */
    val processes: Boolean get() = enabled && (companderOn || bassOn || impactOn || volumeDb != 0f || softClip)

    /** The layout NativeDsp.setParams expects. */
    fun toNativeValues() = floatArrayOf(
        if (companderOn) 1f else 0f, expansionPercent, transitionDb, hfTransitionDb,
        if (bassOn) 1f else 0f, bassPercent, bassTransitionDb,
        if (impactOn) 1f else 0f, impactPercent, impactReleaseMs,
        volumeDb, if (softClip) 1f else 0f,
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
) {
    companion object {
        fun from(m: FloatArray) = DspMeters(m.copyOfRange(0, 4), m[4], m.copyOfRange(5, 9), m[9])
    }
}

/** Saved DSP settings (SharedPreferences "dsp"), readable from the playback thread. */
object DspSettings {
    private const val PREFS = "dsp"

    @Volatile var live: DspParams = DspParams()
        private set

    /** Bumped on every change, so the playback thread re-sends parameters only when needed. */
    @Volatile var version = 0
        private set

    /** Latest meters while the chain runs; null when it isn't running. */
    @Volatile var meters: DspMeters? = null

    private val listeners = CopyOnWriteArrayList<(old: DspParams, new: DspParams) -> Unit>()
    fun addListener(l: (old: DspParams, new: DspParams) -> Unit) = listeners.add(l)
    fun removeListener(l: (old: DspParams, new: DspParams) -> Unit) = listeners.remove(l)

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = DspParams()
        live = DspParams(
            enabled = p.getBoolean("enabled", d.enabled),
            companderOn = p.getBoolean("companderOn", d.companderOn),
            expansionPercent = p.getFloat("expansionPercent", d.expansionPercent),
            transitionDb = p.getFloat("transitionDb", d.transitionDb),
            hfTransitionDb = p.getFloat("hfTransitionDb", d.hfTransitionDb),
            bassOn = p.getBoolean("bassOn", d.bassOn),
            bassPercent = p.getFloat("bassPercent", d.bassPercent),
            bassTransitionDb = p.getFloat("bassTransitionDb", d.bassTransitionDb),
            impactOn = p.getBoolean("impactOn", d.impactOn),
            impactPercent = p.getFloat("impactPercent", d.impactPercent),
            impactReleaseMs = p.getFloat("impactReleaseMs", d.impactReleaseMs),
            volumeDb = p.getFloat("volumeDb", d.volumeDb),
            softClip = p.getBoolean("softClip", d.softClip),
            order = p.getString("order", null)?.split(',')
                ?.mapNotNull { name -> DspProcessor.entries.firstOrNull { it.name == name } } ?: d.order,
        ).sanitized()
        version++
    }

    fun update(context: Context, change: (DspParams) -> DspParams) {
        val old = live
        val new = change(old).sanitized()
        if (new == old) return
        live = new
        version++
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", new.enabled)
            .putBoolean("companderOn", new.companderOn)
            .putFloat("expansionPercent", new.expansionPercent)
            .putFloat("transitionDb", new.transitionDb)
            .putFloat("hfTransitionDb", new.hfTransitionDb)
            .putBoolean("bassOn", new.bassOn)
            .putFloat("bassPercent", new.bassPercent)
            .putFloat("bassTransitionDb", new.bassTransitionDb)
            .putBoolean("impactOn", new.impactOn)
            .putFloat("impactPercent", new.impactPercent)
            .putFloat("impactReleaseMs", new.impactReleaseMs)
            .putFloat("volumeDb", new.volumeDb)
            .putBoolean("softClip", new.softClip)
            .putString("order", new.order.joinToString(",") { it.name })
            .apply()
        listeners.forEach { it(old, new) }
    }
}
