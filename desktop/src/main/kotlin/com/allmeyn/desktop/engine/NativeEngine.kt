package com.allmeyn.desktop.engine

import java.io.File

/**
 * The native audio engine (allmeyn_desktop.dll, desktop/native): what it was built with, the
 * Windows outputs, and the player (one song at a time; queue and gapless come with the library).
 */
object NativeEngine {
    sealed interface Status {
        /** Loaded: libavcodec ident ("Lavc61.19.101") and the formats it can play. */
        data class Ready(val ffmpeg: String, val formats: List<String>) : Status
        data class Unavailable(val reason: String) : Status
    }

    /** Loads the DLL on first use. Never throws: a missing or broken DLL is reported, not fatal. */
    val status: Status by lazy { load() }

    val isReady: Boolean get() = status is Status.Ready

    /** Active output devices (WASAPI). Talks to Windows: call off the UI thread. */
    fun devices(): List<OutputDevice> =
        if (isReady) outputDevices().mapNotNull(OutputDevice::parse) else emptyList()

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

    // ---- player (only call when isReady) ----
    /** Opens a song paused at the start: null, or why it can't be played. Blocks briefly (file + device). */
    @JvmStatic external fun playerOpen(path: String): String?
    /** [codec, sampleRate, channels, bits, totalFrames, title, artist, album, output]. */
    @JvmStatic external fun playerInfo(): Array<String>
    @JvmStatic external fun playerPlay()
    @JvmStatic external fun playerPause()
    @JvmStatic external fun playerSeek(frame: Long)
    @JvmStatic external fun playerClose()
    @JvmStatic external fun playerPosition(): Long
    /** 0 idle, 1 playing, 2 paused, 3 ended, 4 error. */
    @JvmStatic external fun playerState(): Int
    @JvmStatic external fun playerError(): String
}
