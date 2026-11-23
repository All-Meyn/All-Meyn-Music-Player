package com.allmeyn.player.dsp

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

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
            vocalOn = p.getBoolean("vocalOn", d.vocalOn),
            presencePercent = p.getFloat("presencePercent", d.presencePercent),
            detailPercent = p.getFloat("detailPercent", d.detailPercent),
            airPercent = p.getFloat("airPercent", d.airPercent),
            volumeDb = p.getFloat("volumeDb", d.volumeDb),
            softClip = p.getBoolean("softClip", d.softClip),
            eqOn = p.getBoolean("eqOn", d.eqOn),
            eq = p.getString("eqProfile", null)?.let { EqLibrary.decode(it).firstOrNull() } ?: d.eq,
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
            .putBoolean("vocalOn", new.vocalOn)
            .putFloat("presencePercent", new.presencePercent)
            .putFloat("detailPercent", new.detailPercent)
            .putFloat("airPercent", new.airPercent)
            .putFloat("volumeDb", new.volumeDb)
            .putBoolean("softClip", new.softClip)
            .putString("order", new.order.joinToString(",") { it.name })
            .putBoolean("eqOn", new.eqOn)
            .putString("eqProfile", EqLibrary.encode(listOf(new.eq)))
            .apply()
        listeners.forEach { it(old, new) }
    }
}
