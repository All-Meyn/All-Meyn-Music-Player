package com.allmeyn.desktop.engine

import java.util.prefs.Preferences

/**
 * Where and how songs are played (Settings → Output), saved between runs. Exclusive (bit-perfect)
 * and DoP are on by default: the app is made for sending music to a DAC untouched.
 */
data class OutputSettings(
    val deviceId: String = "",      // "" = Windows' default output; "asio:<name>" = an ASIO driver
    val exclusive: Boolean = true,  // WASAPI only (ASIO always has the DAC to itself)
    val dop: Boolean = true,        // DSD as DoP (with exclusive or ASIO; otherwise DSD plays as PCM)
) {
    val isAsio: Boolean get() = deviceId.startsWith(ASIO_PREFIX)

    /** DoP can reach the DAC untouched: exclusive WASAPI or ASIO. */
    val dopPossible: Boolean get() = exclusive || isAsio

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
        const val ASIO_PREFIX = "asio:"

        fun asioId(driverName: String) = ASIO_PREFIX + driverName

        fun load() = OutputSettings(
            deviceId = prefs.get("outputDevice", ""),
            exclusive = prefs.getBoolean("outputExclusive", true),
            dop = prefs.getBoolean("outputDop", true),
        )
    }
}
