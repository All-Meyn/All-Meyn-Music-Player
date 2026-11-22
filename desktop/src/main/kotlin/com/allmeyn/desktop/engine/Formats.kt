package com.allmeyn.desktop.engine

/** Turns FFmpeg's component list into the formats a person recognises. */
object Formats {
    private val DECODER_NAMES = linkedMapOf(
        "flac" to "FLAC",
        "alac" to "ALAC",
        "ape" to "APE",
        "wavpack" to "WavPack",
        "mp3" to "MP3",
        "mp3float" to "MP3",
        "aac" to "AAC",
        "vorbis" to "Ogg Vorbis",
        "opus" to "Opus",
    )

    /** ["decoder:flac", "demuxer:wav", ...] -> ["FLAC", ..., "WAV / AIFF"], in a fixed order, no repeats. */
    fun summarize(components: List<String>): List<String> {
        val decoders = components.filter { it.startsWith("decoder:") }.map { it.removePrefix("decoder:") }.toSet()
        val out = DECODER_NAMES.filterKeys { it in decoders }.values.distinct().toMutableList()
        if (decoders.any { it.startsWith("pcm_") }) out += "WAV / AIFF"
        if ("allmeyn_dsd" in decoders) out += "DSD (DSF/DFF)"  // our own decoder (the Android app's dsd.c)
        return out
    }
}
