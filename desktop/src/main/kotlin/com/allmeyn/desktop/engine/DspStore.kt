package com.allmeyn.desktop.engine

import com.allmeyn.player.dsp.DspMeters
import com.allmeyn.player.dsp.DspParams
import com.allmeyn.player.dsp.DspProcessor
import java.util.prefs.Preferences

/**
 * The DSP settings (the Android app's DspParams), saved between runs under the same key names as
 * on Android, and sent to the engine.
 */
object DspStore {
    private val prefs: Preferences = Preferences.userRoot().node("com/allmeyn/desktop/dsp")

    fun load(): DspParams {
        val d = DspParams()
        return DspParams(
            enabled = prefs.getBoolean("enabled", d.enabled),
            companderOn = prefs.getBoolean("companderOn", d.companderOn),
            expansionPercent = prefs.getFloat("expansionPercent", d.expansionPercent),
            transitionDb = prefs.getFloat("transitionDb", d.transitionDb),
            hfTransitionDb = prefs.getFloat("hfTransitionDb", d.hfTransitionDb),
            bassOn = prefs.getBoolean("bassOn", d.bassOn),
            bassPercent = prefs.getFloat("bassPercent", d.bassPercent),
            bassTransitionDb = prefs.getFloat("bassTransitionDb", d.bassTransitionDb),
            impactOn = prefs.getBoolean("impactOn", d.impactOn),
            impactPercent = prefs.getFloat("impactPercent", d.impactPercent),
            impactReleaseMs = prefs.getFloat("impactReleaseMs", d.impactReleaseMs),
            vocalOn = prefs.getBoolean("vocalOn", d.vocalOn),
            presencePercent = prefs.getFloat("presencePercent", d.presencePercent),
            detailPercent = prefs.getFloat("detailPercent", d.detailPercent),
            airPercent = prefs.getFloat("airPercent", d.airPercent),
            volumeDb = prefs.getFloat("volumeDb", d.volumeDb),
            softClip = prefs.getBoolean("softClip", d.softClip),
            order = prefs.get("order", null)?.let(::parseOrder) ?: d.order,
        ).sanitized()
    }

    fun save(p: DspParams) {
        prefs.putBoolean("enabled", p.enabled)
        prefs.putBoolean("companderOn", p.companderOn)
        prefs.putFloat("expansionPercent", p.expansionPercent)
        prefs.putFloat("transitionDb", p.transitionDb)
        prefs.putFloat("hfTransitionDb", p.hfTransitionDb)
        prefs.putBoolean("bassOn", p.bassOn)
        prefs.putFloat("bassPercent", p.bassPercent)
        prefs.putFloat("bassTransitionDb", p.bassTransitionDb)
        prefs.putBoolean("impactOn", p.impactOn)
        prefs.putFloat("impactPercent", p.impactPercent)
        prefs.putFloat("impactReleaseMs", p.impactReleaseMs)
        prefs.putBoolean("vocalOn", p.vocalOn)
        prefs.putFloat("presencePercent", p.presencePercent)
        prefs.putFloat("detailPercent", p.detailPercent)
        prefs.putFloat("airPercent", p.airPercent)
        prefs.putFloat("volumeDb", p.volumeDb)
        prefs.putBoolean("softClip", p.softClip)
        prefs.put("order", p.order.joinToString(",") { it.name })
    }

    /** Tells the engine (applies at once). */
    fun apply(p: DspParams) {
        if (NativeEngine.isReady) NativeEngine.playerSetDsp(p.enabled, p.toNativeValues(), p.toNativeOrder())
    }

    /** The chain's meters while it processes the song; null otherwise. */
    fun meters(): DspMeters? =
        if (NativeEngine.isReady) NativeEngine.playerDspMeters()?.let(DspMeters::from) else null

    internal fun parseOrder(saved: String): List<DspProcessor> =
        saved.split(',').mapNotNull { name -> DspProcessor.entries.firstOrNull { it.name == name } }
}
