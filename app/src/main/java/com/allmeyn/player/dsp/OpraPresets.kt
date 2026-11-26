package com.allmeyn.player.dsp

import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

// Pure Kotlin (no Android): also compiled into the Windows app (desktop/build.gradle.kts, sharedSources).

/**
 * A headphone EQ preset from OPRA (https://github.com/opra-project/OPRA, CC BY-SA 4.0), bundled
 * with both apps (assets/opra/presets.tsv.gz, made by scripts/build-opra-presets.py).
 * [author] created it (AutoEQ, oratory1990, …); [details] says against which measurement or target.
 */
data class OpraPreset(
    val vendor: String,
    val product: String,
    val kind: String,
    val author: String,
    val details: String,
    val profile: EqProfile,
) {
    val headphone: String get() = "$vendor $product"
}

object OpraPresets {
    /** Android asset path; on Windows the same file is the resource "/opra_presets.tsv.gz". */
    const val ASSET = "opra/presets.tsv.gz"
    const val REPO_URL = "https://github.com/opra-project/OPRA"
    const val LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
    const val DESCRIPTION = "OPRA is an open, community-maintained directory of product information and EQ " +
        "compensation curves that optimize a wide range of headphone models."

    @Volatile private var loaded: List<OpraPreset>? = null

    /** The bundled list, read once ([open] opens the gzipped file). Call off the UI thread. */
    fun load(open: () -> InputStream): List<OpraPreset> =
        loaded ?: synchronized(this) { loaded ?: open().use(::read).also { loaded = it } }

    /** Reads the bundled (gzipped) file. Lines that don't parse are skipped. */
    fun read(gzipped: InputStream): List<OpraPreset> =
        GZIPInputStream(gzipped).bufferedReader(Charsets.UTF_8).useLines { lines -> lines.mapNotNull(::parseLine).toList() }

    /** One line: vendor, product, kind, author, details, preamp, bands ("PK 105 -1.4 0.7;…"), tab separated. */
    fun parseLine(line: String): OpraPreset? {
        if (line.isBlank() || line.startsWith("#")) return null
        val f = line.split('\t')
        if (f.size != 7) return null
        val bands = f[6].split(';').map { b ->
            val p = b.trim().split(' ')
            if (p.size != 4) return null
            val type = EqFilterType.entries.firstOrNull { it.apo == p[0] } ?: return null
            EqBand(type, p[1].toFloatOrNull() ?: return null, p[2].toFloatOrNull() ?: return null, p[3].toFloatOrNull() ?: return null)
        }
        if (bands.isEmpty() || bands.size > EqProfile.MAX_BANDS) return null
        val (vendor, product, kind, author, details) = f
        val preamp = f[5].toFloatOrNull() ?: return null
        val name = "$vendor $product (" + author + (if (details.isNotBlank()) ", ${shortDetails(details)}" else "") + ")"
        return OpraPreset(vendor, product, kind, author, details, EqProfile(name, preamp, bands).sanitized())
    }

    // "Measured by crinacle" → "crinacle"; targets stay as they are ("Harman Target").
    private fun shortDetails(d: String) = d.removePrefix("Measured by ").trim()

    /**
     * Presets whose headphone, kind, creator or details contain every word of [query] (any case).
     * Headphones whose name starts with the query come first; otherwise in the file's order (A–Z).
     */
    fun search(all: List<OpraPreset>, query: String, limit: Int = 200): List<OpraPreset> {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return all.take(limit)
        val q = words.joinToString(" ")
        val hits = all.filter { p ->
            val hay = "${p.vendor} ${p.product} ${p.kind} ${p.author} ${p.details}".lowercase(Locale.ROOT)
            words.all { it in hay }
        }
        val (first, rest) = hits.partition { it.headphone.lowercase(Locale.ROOT).startsWith(q) || it.product.lowercase(Locale.ROOT).startsWith(q) }
        return (first + rest).take(limit)
    }
}
