package com.allmeyn.desktop.engine

import com.allmeyn.desktop.library.DesktopLibrary
import com.allmeyn.player.dsp.ConvKind
import com.allmeyn.player.dsp.ConvolutionFiles
import com.allmeyn.player.dsp.ConvolutionFilter
import com.allmeyn.player.dsp.DspMeters
import com.allmeyn.player.dsp.DspParams
import com.allmeyn.player.dsp.DspProcessor
import com.allmeyn.player.dsp.EqLibrary
import com.allmeyn.player.dsp.GraphicEqParser
import java.io.File
import java.util.concurrent.Executors
import java.util.prefs.Preferences

/**
 * The DSP settings (the Android app's DspParams), saved between runs under the same key names as
 * on Android, and sent to the engine.
 */
object DspStore {
    private val prefs: Preferences = Preferences.userRoot().node("com/allmeyn/desktop/dsp")

    init { ConvolutionFiles.init(File(DesktopLibrary.defaultDataDir(), "convolution")) }

    // Convolution filters are read and built off the UI thread, one at a time (the last one wins).
    private val convWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "All-Meyn convolution").apply { isDaemon = true } }
    private var convKey: Any? = null  // UI thread

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
            eqOn = prefs.getBoolean("eqOn", d.eqOn),
            eq = prefs.get("eqProfile", null)?.let { EqLibrary.decode(it).firstOrNull() } ?: d.eq,
            convOn = prefs.getBoolean("convOn", d.convOn),
            convGainDb = prefs.getFloat("convGainDb", d.convGainDb),
            conv = ConvolutionFilter(
                kind = ConvKind.entries.firstOrNull { it.name == prefs.get("convKind", null) } ?: ConvKind.NONE,
                name = prefs.get("convName", ""),
                irFile = prefs.get("convFile", ""),
                graphic = prefs.get("convGraphic", null)?.let { runCatching { GraphicEqParser.parse(it) }.getOrNull() }.orEmpty(),
                info = prefs.get("convInfo", ""),
            ),
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
        prefs.putBoolean("eqOn", p.eqOn)
        prefs.put("eqProfile", EqLibrary.encode(listOf(p.eq)))  // ≤ 20 filters: well under Preferences' 8 KB
        prefs.putBoolean("convOn", p.convOn)
        prefs.putFloat("convGainDb", p.convGainDb)
        prefs.put("convKind", p.conv.kind.name)
        prefs.put("convName", p.conv.name)
        prefs.put("convFile", p.conv.irFile)
        // A GraphicEQ line: ≤ 400 points (GraphicEqParser.MAX_POINTS), so under Preferences' 8 KB per value.
        prefs.put("convGraphic", if (p.conv.graphic.isEmpty()) "" else GraphicEqParser.toText(p.conv.graphic))
        prefs.put("convInfo", p.conv.info)
    }

    /** Tells the engine (applies at once). */
    fun apply(p: DspParams) {
        if (!NativeEngine.isReady) return
        NativeEngine.playerSetDsp(p.enabled, p.toNativeValues(), p.toNativeOrder())
        val key = if (p.convActive) p.conv to p.convGainDb else null
        if (key != convKey) {
            convKey = key
            convWorker.execute { sendConvolution(p) }
        }
    }

    private fun sendConvolution(p: DspParams) {
        val f = p.conv
        when {
            !p.convActive -> NativeEngine.playerSetConvolution(null, null, 0f, null, null, 0f)
            f.kind == ConvKind.GRAPHIC -> NativeEngine.playerSetConvolution(
                null, null, 0f, FloatArray(f.graphic.size) { f.graphic[it].hz }, FloatArray(f.graphic.size) { f.graphic[it].db }, p.convGainDb,
            )
            else -> {
                val ir = ConvolutionFiles.load(f.irFile)
                if (ir == null) NativeEngine.playerSetConvolution(null, null, 0f, null, null, 0f)
                else NativeEngine.playerSetConvolution(ir.channels[0], ir.channels.getOrNull(1), ir.rate.toFloat(), null, null, p.convGainDb)
            }
        }
    }

    /** The chain's meters while it processes the song; null otherwise. */
    fun meters(): DspMeters? =
        if (NativeEngine.isReady) NativeEngine.playerDspMeters()?.let(DspMeters::from) else null

    internal fun parseOrder(saved: String): List<DspProcessor> =
        saved.split(',').mapNotNull { name -> DspProcessor.entries.firstOrNull { it.name == name } }
}
