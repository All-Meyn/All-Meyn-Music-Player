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
    @JvmStatic private external fun asioDrivers(): Array<String>

    /** Installed ASIO drivers, by name (reads the registry: call off the UI thread). */
    fun asioDriverNames(): List<String> = if (isReady) asioDrivers().toList() else emptyList()

    // ---- player (only call when isReady) ----
    /**
     * Output for songs opened from now on: device ("" = default, "asio:<name>" = an ASIO driver),
     * exclusive (bit-perfect) or shared, DSD as DoP. ASIO tries native DSD first.
     */
    @JvmStatic external fun playerSetOutput(deviceId: String, exclusive: Boolean, dop: Boolean)
    /** Dark (or light) title bar for the window [hwnd] (Windows 10 2004+ / 11; ignored elsewhere). */
    @JvmStatic external fun setDarkTitleBar(hwnd: Long, dark: Boolean)
    /** Opens the ASIO driver's own control panel; null if it opened, else why not. */
    @JvmStatic external fun asioControlPanel(driverName: String): String?
    /** The output's volume (DacVolume layout), "" = Windows' default; null if none. Off the UI thread. */
    @JvmStatic external fun dacVolume(deviceId: String): FloatArray?
    @JvmStatic external fun setDacVolume(deviceId: String, level: Float): Boolean
    @JvmStatic external fun setDacMute(deviceId: String, muted: Boolean): Boolean
    /** ReplayGain for songs opened from now on: 0 off, 1 track, 2 album. */
    @JvmStatic external fun playerSetReplayGain(mode: Int)
    /** The DSP chain: master switch, values and order as DspParams.toNativeValues / toNativeOrder. Applies at once. */
    @JvmStatic external fun playerSetDsp(enabled: Boolean, values: FloatArray, order: IntArray)
    /** The DSP meters (DspMeters layout) while the chain processes the song; null otherwise. */
    @JvmStatic external fun playerDspMeters(): FloatArray?
    /** Opens a song paused at the start: null, or why it can't be played. Blocks briefly (file + device). */
    @JvmStatic external fun playerOpen(path: String): String?
    /** The song to play after the current one (gapless if same rate/channels): null, or why not. */
    @JvmStatic external fun playerSetNext(path: String): String?
    @JvmStatic external fun playerClearNext()
    /** Counts gapless moves to the next song. */
    @JvmStatic external fun playerTrackSerial(): Int
    /** [codec, sampleRate, channels, bits, totalFrames, title, artist, album, output, path] of the song heard. */
    @JvmStatic external fun playerInfo(): Array<String>
    @JvmStatic external fun playerPlay()
    @JvmStatic external fun playerPause()
    @JvmStatic external fun playerSeek(frame: Long)
    @JvmStatic external fun playerClose()
    @JvmStatic external fun playerPosition(): Long
    /** 0 idle, 1 playing, 2 paused, 3 ended, 4 error. */
    @JvmStatic external fun playerState(): Int
    @JvmStatic external fun playerError(): String

    // ---- library ----
    /**
     * [ok, title, artist, album, albumArtist, track, disc, year, durationMs, format, sampleRate, bits,
     * hasCover, error]. Thread-safe (the scan reads several files at once).
     */
    @JvmStatic external fun readTags(path: String): Array<String>
    /** The embedded cover's bytes, or null. */
    @JvmStatic external fun readCover(path: String): ByteArray?
    /** The Windows folder picker over the window [owner] (HWND); the folder, or null if cancelled. */
    @JvmStatic external fun pickFolder(owner: Long, title: String): String?
}
