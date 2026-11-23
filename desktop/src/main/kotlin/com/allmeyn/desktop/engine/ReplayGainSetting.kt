package com.allmeyn.desktop.engine

import java.util.prefs.Preferences

/**
 * ReplayGain (Settings → Playback), as on Android: Off / Track / Album, default Off. Applied by the
 * engine to the samples from each song's first sample, only lowering volume. Only with the shared
 * (Windows mixer) output: exclusive mode and ASIO play files untouched, as the Android app does with
 * its bit-perfect USB driver.
 */
enum class ReplayGainMode(val label: String) {
    OFF("Off"), TRACK("Track"), ALBUM("Album");

    fun save() = prefs.put(KEY, name)

    /** Tells the engine, for songs opened from now on. */
    fun apply(output: OutputSettings) {
        if (NativeEngine.isReady) NativeEngine.playerSetReplayGain(if (appliesTo(output)) ordinal else 0)
    }

    companion object {
        private val prefs: Preferences = Preferences.userRoot().node("com/allmeyn/desktop")
        private const val KEY = "replayGain"

        fun load(): ReplayGainMode = entries.firstOrNull { it.name == prefs.get(KEY, null) } ?: OFF

        /** Bit-perfect outputs (exclusive WASAPI, ASIO) never get ReplayGain. */
        fun appliesTo(output: OutputSettings) = !output.isAsio && !output.exclusive

        /** "−6.2 dB (album)" for Now Playing; positive gains aren't applied (never boosts). */
        fun label(db: Float, mode: ReplayGainMode): String {
            val shown = String.format(java.util.Locale.ROOT, "%+.1f dB", db)
            return "$shown (${mode.label.lowercase()})" + if (db > 0f) ", not boosted" else ""
        }
    }
}
