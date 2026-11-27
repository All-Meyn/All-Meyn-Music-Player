package com.allmeyn.player.dsp

import java.io.File
import java.util.Locale

// Pure Kotlin (no Android): also compiled into the Windows app (desktop/build.gradle.kts, sharedSources).

/** An output the music can go to: [key] is stable (e.g. "usb:FiiO K7"), [label] is for people. */
data class OutputId(val key: String, val label: String)

/** The headphone EQ settings each output remembers: both EQs, their switches and the convolution gain. */
data class OutputEq(
    val eqOn: Boolean,
    val eq: EqProfile,
    val convOn: Boolean,
    val conv: ConvolutionFilter,
    val convGainDb: Float,
) {
    fun applyTo(p: DspParams) = p.copy(eqOn = eqOn, eq = eq, convOn = convOn, conv = conv, convGainDb = convGainDb).sanitized()

    companion object {
        fun of(p: DspParams) = OutputEq(p.eqOn, p.eq, p.convOn, p.conv, p.convGainDb)
    }
}

/**
 * Remembers the headphone EQ for each output and brings it back when that output is used again.
 * A new output starts with the EQ in use when it appears. Saved in [file] (both apps: the app's data
 * folder, output_eqs.txt). Thread-safe.
 */
class OutputEqStore(private val file: File) {
    private var loaded = false
    private var on = true
    private val saved = LinkedHashMap<String, Pair<String, OutputEq>>()  // key → (label, EQ)

    /** Off = one EQ for every output (as before this existed). On by default. */
    val enabled: Boolean get() = synchronized(this) { load(); on }

    fun setEnabled(value: Boolean) = synchronized(this) {
        load()
        if (on != value) { on = value; write() }
    }

    /** The outputs with a remembered EQ, in the order they first appeared. */
    fun outputs(): List<OutputId> = synchronized(this) { load(); saved.map { (k, v) -> OutputId(k, v.first) } }

    fun forget(key: String) = synchronized(this) { load(); if (saved.remove(key) != null) write() }

    /** The output [id] is in use with [p]: keep its EQ (only written when it changed). */
    fun remember(id: OutputId, p: DspParams) {
        synchronized(this) {
            load()
            if (!on) return
            val eq = OutputEq.of(p)
            val old = saved[id.key]
            if (old?.second == eq && old.first == id.label) return
            saved[id.key] = id.label to eq
            write()
        }
    }

    /**
     * The music now goes to [id]: [p] with that output's EQ, if it has one (and remembering is on);
     * otherwise [p] as it is, which becomes the output's EQ.
     */
    fun switchTo(id: OutputId, p: DspParams): DspParams {
        synchronized(this) {
            load()
            if (!on) return p
            val s = saved[id.key]?.second
            if (s == null) { remember(id, p); return p }
            return s.applyTo(p)
        }
    }

    /** Impulse response files the remembered EQs use (so they're kept when another is imported). */
    fun irFiles(): Set<String> = synchronized(this) {
        load()
        saved.values.mapNotNull { (_, e) -> e.conv.irFile.takeIf { e.conv.kind == ConvKind.IMPULSE && it.isNotEmpty() } }.toSet()
    }

    // --- File: "enabled=true", then per output "[key]" and key=value lines (values escaped). ---

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        val lines = try { file.readLines(Charsets.UTF_8) } catch (e: Exception) { return }
        var key: String? = null
        var fields = HashMap<String, String>()
        fun flush() {
            val k = key ?: return
            decode(fields)?.let { saved[k] = (fields["label"] ?: k) to it }
        }
        for (line in lines) {
            when {
                line.startsWith("[") && line.endsWith("]") -> { flush(); key = unescape(line.substring(1, line.length - 1)); fields = HashMap() }
                key == null && line.startsWith("enabled=") -> on = line.removePrefix("enabled=") != "false"
                key != null && '=' in line -> fields[line.substringBefore('=')] = unescape(line.substringAfter('='))
            }
        }
        flush()
    }

    private fun write() {
        val text = buildString {
            append("# All-Meyn: headphone EQ per output\n")
            append("enabled=").append(on).append('\n')
            for ((k, v) in saved) {
                append('[').append(escape(k)).append("]\n")
                val (label, e) = v
                fun put(name: String, value: String) { append(name).append('=').append(escape(value)).append('\n') }
                put("label", label)
                put("eqOn", e.eqOn.toString())
                put("eqProfile", EqLibrary.encode(listOf(e.eq)))
                put("convOn", e.convOn.toString())
                put("convGainDb", String.format(Locale.ROOT, "%.1f", e.convGainDb))
                put("convKind", e.conv.kind.name)
                put("convName", e.conv.name)
                put("convFile", e.conv.irFile)
                put("convGraphic", if (e.conv.graphic.isEmpty()) "" else GraphicEqParser.toText(e.conv.graphic))
                put("convInfo", e.conv.info)
            }
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            System.err.println("All-Meyn: couldn't save ${file.name}: $e")
        }
    }

    private fun decode(f: Map<String, String>): OutputEq? = runCatching {
        OutputEq(
            eqOn = f["eqOn"] == "true",
            eq = f["eqProfile"]?.let { EqLibrary.decode(it).firstOrNull() } ?: EqProfile(),
            convOn = f["convOn"] == "true",
            conv = ConvolutionFilter(
                kind = ConvKind.entries.firstOrNull { it.name == f["convKind"] } ?: ConvKind.NONE,
                name = f["convName"].orEmpty(),
                irFile = f["convFile"].orEmpty(),
                graphic = f["convGraphic"]?.takeIf { it.isNotBlank() }?.let { runCatching { GraphicEqParser.parse(it) }.getOrNull() }.orEmpty(),
                info = f["convInfo"].orEmpty(),
            ),
            convGainDb = f["convGainDb"]?.toFloatOrNull() ?: 0f,
        )
    }.getOrNull()

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")
    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(if (s[i + 1] == 'n') '\n' else s[i + 1]); i += 2
            } else { append(c); i++ }
        }
    }
}
