package com.allmeyn.desktop.engine

import java.util.Locale

/**
 * The output's volume as Windows has it (endpoint volume). [hardware]: the DAC does the volume
 * itself (USB feature unit), so the samples stay untouched; otherwise Windows scales them in its
 * mixer (shared mode only).
 */
data class DacVolume(
    val level: Float,     // 0..1, Windows' slider position
    val db: Float,
    val minDb: Float,
    val maxDb: Float,
    val stepDb: Float,
    val muted: Boolean,
    val hardware: Boolean,
) {
    val label: String get() = if (muted) "Muted" else String.format(Locale.ROOT, "%.1f dB", db)

    companion object {
        fun from(f: FloatArray?): DacVolume? =
            if (f == null || f.size != 7) null
            else DacVolume(f[0].coerceIn(0f, 1f), f[1], f[2], f[3], f[4], f[5] != 0f, f[6] != 0f)

        /** Reads the volume of [output]'s WASAPI device; null for ASIO (no standard volume) or none. */
        fun read(output: OutputSettings): DacVolume? =
            if (!NativeEngine.isReady || output.isAsio) null else from(NativeEngine.dacVolume(output.deviceId))

        fun set(output: OutputSettings, level: Float) {
            if (NativeEngine.isReady && !output.isAsio) NativeEngine.setDacVolume(output.deviceId, level)
        }

        fun setMuted(output: OutputSettings, muted: Boolean) {
            if (NativeEngine.isReady && !output.isAsio) NativeEngine.setDacMute(output.deviceId, muted)
        }
    }
}
