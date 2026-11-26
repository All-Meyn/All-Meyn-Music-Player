package com.allmeyn.player.dsp

import java.io.File

/**
 * Impulse responses the user imported, kept as copies in the app's storage (Android:
 * filesDir/convolution; Windows: %LOCALAPPDATA%\All-Meyn\convolution),
 * so the convolution EQ doesn't depend on the original file staying where it was. The last one read
 * is kept in memory (the playback thread asks again for every new chain).
 */
object ConvolutionFiles {
    private var cachedName: String? = null
    private var cached: WavIr? = null
    @Volatile private var root: File? = null

    /** Called once at start, before anything else here. Shared with the desktop app: no Android types. */
    fun init(dir: File) { root = dir }

    private fun dir() = root ?: error("ConvolutionFiles.init not called")

    /**
     * Stores [bytes] for the filter called [name]; returns the stored file's name. Each import gets
     * its own file (a different response of the same name doesn't replace one another output uses):
     * call [prune] afterwards to drop the ones nothing uses.
     */
    fun save(name: String, bytes: ByteArray): String {
        val d = dir().apply { mkdirs() }
        val base = EqFiles.storageName(name).removeSuffix(".wav")
        var file = File(d, "$base.wav")
        var n = 2
        while (file.exists() && !file.readBytes().contentEquals(bytes)) file = File(d, "$base ($n).wav").also { n++ }
        if (!file.exists()) {
            val tmp = File(d, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        synchronized(this) { if (cachedName == file.name) { cachedName = null; cached = null } }
        return file.name
    }

    /** Deletes the stored responses not named in [keep] (the one in use, and those outputs remember). */
    fun prune(keep: Set<String>) {
        dir().listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        synchronized(this) { if (cachedName != null && cachedName !in keep) { cachedName = null; cached = null } }
    }

    /** The stored response [name], or null if it's gone or unreadable. */
    fun load(name: String): WavIr? = synchronized(this) {
        if (name == cachedName) return cached
        val ir = try {
            WavReader.read(File(dir(), name).readBytes())
        } catch (e: Exception) {
            System.err.println("All-Meyn: convolution file $name: $e")
            null
        }
        cachedName = name
        cached = ir
        ir
    }

}

/**
 * What importing an EQ file does, for either DSP screen: [change] updates the settings (null when the
 * file couldn't be used); [eqNotes] / [convNotes] are what the Headphone EQ / Convolution EQ sections
 * say (null = leave them as they are). A parametric EQ goes to Headphone EQ; a GraphicEQ or a WAV
 * impulse response to Convolution EQ, whichever section's Import was used. Stores a WAV's copy, so
 * call it off the UI thread for files.
 */
class EqImportOutcome(val change: ((DspParams) -> DspParams)?, val eqNotes: List<String>?, val convNotes: List<String>?)

fun importEqFile(bytes: ByteArray, fileName: String, fromConvolution: Boolean): EqImportOutcome = try {
    when (val f = EqFiles.read(bytes, fileName)) {
        is EqFile.Parametric -> EqImportOutcome(
            { it.copy(eq = f.import.profile, eqOn = true) },
            listOf("Imported ${f.import.profile.bands.size} filters.") + f.import.notes,
            if (fromConvolution) listOf("That was a parametric EQ: it went to Headphone EQ.") else null,
        )
        is EqFile.Graphic -> EqImportOutcome(
            { it.copy(conv = ConvolutionFilter(ConvKind.GRAPHIC, f.name, graphic = f.points, info = "GraphicEQ · ${f.points.size} points"), convOn = true) },
            if (fromConvolution) null else listOf("That was a GraphicEQ: it went to Convolution EQ."),
            listOf("Imported a GraphicEQ (${f.points.size} points)."),
        )
        is EqFile.Impulse -> {
            val stored = ConvolutionFiles.save(f.name, f.bytes)
            EqImportOutcome(
                { it.copy(conv = ConvolutionFilter(ConvKind.IMPULSE, f.name, irFile = stored, info = f.ir.info), convOn = true) },
                if (fromConvolution) null else listOf("That was an impulse response: it went to Convolution EQ."),
                listOf("Imported an impulse response.") +
                    if (f.ir.frames > f.ir.rate) listOf("Longer than 1 s: the first second is used.") else emptyList(),
            )
        }
    }
} catch (e: IllegalArgumentException) {
    val msg = listOf(e.message ?: "Couldn't read that file.")
    EqImportOutcome(null, if (fromConvolution) null else msg, if (fromConvolution) msg else null)
} catch (e: java.io.IOException) {
    val msg = listOf("Couldn't store that file: ${e.message}")
    EqImportOutcome(null, if (fromConvolution) null else msg, if (fromConvolution) msg else null)
}
