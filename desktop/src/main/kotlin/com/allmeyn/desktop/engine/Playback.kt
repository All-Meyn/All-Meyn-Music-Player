package com.allmeyn.desktop.engine

import java.io.File
import java.util.Locale

enum class PlayState { IDLE, PLAYING, PAUSED, ENDED, ERROR;
    companion object { fun of(code: Int) = entries.getOrElse(code) { IDLE } }
}

/** What's playing, as the engine describes it. */
data class TrackInfo(
    val file: File,
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bits: Int,
    val totalFrames: Long,
    val title: String,
    val artist: String,
    val album: String,
    val output: String,
) {
    val durationMs: Long get() = if (sampleRate > 0) totalFrames * 1000 / sampleRate else 0

    /** The title, or the file name without its extension. */
    val displayTitle: String get() = title.ifBlank { file.nameWithoutExtension }

    /** "Artist — Album", whichever are known. */
    val subtitle: String get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" — ")

    /** "FLAC · 96 kHz · 24-bit"; DSD names its own conversion ("DSF DSD64 → PCM 176.4 kHz"). */
    val formatLabel: String get() {
        if (codec.contains("DSD")) return codec
        val parts = mutableListOf(codec, OutputDevice.formatRate(sampleRate))
        if (bits > 0) parts += "$bits-bit"
        if (channels != 2) parts += if (channels == 1) "mono" else "$channels ch"
        return parts.joinToString(" · ")
    }

    companion object {
        /** From the engine's playerInfo(); the file is its last field. */
        fun from(f: Array<String>): TrackInfo? {
            if (f.size != 10) return null
            val file = File(f[9])
            return TrackInfo(
                file = file, codec = f[0], sampleRate = f[1].toIntOrNull() ?: 0, channels = f[2].toIntOrNull() ?: 0,
                bits = f[3].toIntOrNull() ?: 0, totalFrames = f[4].toLongOrNull() ?: 0,
                title = f[5], artist = f[6], album = f[7], output = f[8],
            )
        }

        /** 187000 -> "3:07", 3723000 -> "1:02:03". */
        fun formatTime(ms: Long): String {
            val s = (ms.coerceAtLeast(0) / 1000)
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
            else String.format(Locale.ROOT, "%d:%02d", m, sec)
        }

        /** Files the open dialog offers. */
        val EXTENSIONS = listOf("flac", "alac", "m4a", "mp4", "ape", "wv", "mp3", "aac", "ogg", "oga", "opus", "wav", "w64", "aif", "aiff", "dsf", "dff")
    }
}
