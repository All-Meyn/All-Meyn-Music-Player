package com.allmeyn.desktop.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Loads the real engine DLL (Windows, after CI built it into resources/windows-x64). */
class NativeEngineTest {
    private fun dllPresent(): Boolean {
        val dir = System.getProperty("allmeyn.engineDir") ?: return false
        return System.getProperty("os.name").startsWith("Windows") && File(dir, "allmeyn_desktop.dll").isFile
    }

    @Test fun loadsAndReportsOurFfmpegBuild() {
        assumeTrue(dllPresent(), "engine DLL not built / not on Windows")
        val s = NativeEngine.status
        assertTrue(s is NativeEngine.Status.Ready, "engine didn't load: $s")
        assertTrue(s.ffmpeg.startsWith("Lavc61."), s.ffmpeg)
        for (f in listOf("FLAC", "ALAC", "APE", "WavPack", "MP3", "AAC", "Ogg Vorbis", "Opus", "WAV / AIFF")) {
            assertTrue(f in s.formats, "missing $f in ${s.formats}")
        }
    }

    @Test fun listsOutputsWithoutCrashing() {
        assumeTrue(dllPresent(), "engine DLL not built / not on Windows")
        // CI machines may have no audio device at all; this only proves COM/WASAPI calls are safe.
        val devices = NativeEngine.devices()
        println("Outputs: " + devices.joinToString { "${it.name} (${it.mixLabel})" })
    }
}
