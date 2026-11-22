package com.allmeyn.desktop.engine

/** A Windows audio output (WASAPI render endpoint) and its shared-mode mix format. */
data class OutputDevice(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    val mixRate: Int,
    val mixBits: Int,
    val mixChannels: Int,
) {
    /** "48 kHz · 32-bit · 2 ch" (Windows' shared mixer format; exclusive mode bypasses it). */
    val mixLabel: String get() =
        if (mixRate <= 0) "format unknown"
        else "${formatRate(mixRate)} · $mixBits-bit · $mixChannels ch"

    companion object {
        /** One row from the engine: "id\tname\tdefault\trate\tbits\tchannels"; null if malformed. */
        fun parse(row: String): OutputDevice? {
            val f = row.split('\t')
            if (f.size != 6 || f[0].isEmpty()) return null
            return OutputDevice(
                id = f[0],
                name = f[1].ifEmpty { "Unnamed output" },
                isDefault = f[2] == "1",
                mixRate = f[3].toIntOrNull() ?: 0,
                mixBits = f[4].toIntOrNull() ?: 0,
                mixChannels = f[5].toIntOrNull() ?: 0,
            )
        }

        /** 44100 -> "44.1 kHz", 48000 -> "48 kHz". */
        fun formatRate(hz: Int): String {
            val khz = hz / 1000.0
            return if (hz % 1000 == 0) "${hz / 1000} kHz" else "${"%.1f".format(java.util.Locale.ROOT, khz)} kHz"
        }
    }
}
