package com.allmeyn.desktop.engine

import java.io.File

/**
 * The native audio engine (allmeyn_desktop.dll, desktop/native). Stage W1: what it was built with
 * and the Windows output devices; playback comes in W2.
 */
object NativeEngine {
    sealed interface Status {
        /** Loaded: libavcodec ident ("Lavc61.19.101") and the formats it can play. */
        data class Ready(val ffmpeg: String, val formats: List<String>) : Status
        data class Unavailable(val reason: String) : Status
    }

    /** Loads the DLL on first use. Never throws: a missing or broken DLL is reported, not fatal. */
    val status: Status by lazy { load() }

    /** Active output devices (WASAPI). Talks to Windows: call off the UI thread. */
    fun devices(): List<OutputDevice> =
        if (status is Status.Ready) outputDevices().mapNotNull(OutputDevice::parse) else emptyList()

    private fun load(): Status = try {
        // Installed app: Compose copies resources/windows-x64 next to it and names the folder here.
        // Tests: Gradle passes the source folder. Otherwise fall back to java.library.path.
        val dir = System.getProperty("compose.application.resources.dir") ?: System.getProperty("allmeyn.engineDir")
        val dll = dir?.let { File(it, "$LIBRARY.dll") }
        if (dll != null && dll.isFile) System.load(dll.absolutePath) else System.loadLibrary(LIBRARY)
        Status.Ready(ffmpegVersion(), Formats.summarize(ffmpegComponents().toList()))
    } catch (e: Throwable) {
        Status.Unavailable(e.message ?: e.javaClass.simpleName)
    }

    private const val LIBRARY = "allmeyn_desktop"

    @JvmStatic private external fun ffmpegVersion(): String
    @JvmStatic private external fun ffmpegComponents(): Array<String>
    @JvmStatic private external fun outputDevices(): Array<String>
}
