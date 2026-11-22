package com.allmeyn.desktop.engine

import java.util.prefs.Preferences

/**
 * Where and how songs are played (Settings → Output), saved between runs. Exclusive (bit-perfect)
 * and DoP are on by default: the app is made for sending music to a DAC untouched.
 */
data class OutputSettings(
    val deviceId: String = "",      // "" = Windows' default output
    val exclusive: Boolean = true,
    val dop: Boolean = true,        // DSD as DoP (only with exclusive; otherwise DSD plays as PCM)
) {
    /** Tells the engine; applies to songs opened from now on. */
    fun apply() {
        if (NativeEngine.isReady) NativeEngine.playerSetOutput(deviceId, exclusive, dop)
    }

    fun save() {
        prefs.put("outputDevice", deviceId)
        prefs.putBoolean("outputExclusive", exclusive)
        prefs.putBoolean("outputDop", dop)
    }

    companion object {
        private val prefs: Preferences = Preferences.userRoot().node("com/allmeyn/desktop")

        fun load() = OutputSettings(
            deviceId = prefs.get("outputDevice", ""),
            exclusive = prefs.getBoolean("outputExclusive", true),
            dop = prefs.getBoolean("outputDop", true),
        )
    }
}
