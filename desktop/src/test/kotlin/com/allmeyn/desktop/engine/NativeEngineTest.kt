package com.allmeyn.desktop.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Loads the real engine DLL (Windows, after CI built it into resources/windows-x64). */
class NativeEngineTest {
    private val ASSETS = File(System.getProperty("allmeyn.assetsDir") ?: "../app/src/androidTest/assets")

    private fun dllPresent(): Boolean {
        val dir = System.getProperty("allmeyn.engineDir") ?: return false
        return System.getProperty("os.name").startsWith("Windows") && File(dir, "allmeyn_desktop.dll").isFile
    }

    @Test fun loadsAndReportsOurFfmpegBuild() {
        assumeTrue(dllPresent(), "engine DLL not built / not on Windows")
        val s = NativeEngine.status
        assertTrue(s is NativeEngine.Status.Ready, "engine didn't load: $s")
        assertTrue(s.ffmpeg.startsWith("Lavc61."), s.ffmpeg)
        for (f in listOf("FLAC", "ALAC", "APE", "WavPack", "MP3", "AAC", "Ogg Vorbis", "Opus", "WAV / AIFF", "DSD (DSF/DFF)")) {
            assertTrue(f in s.formats, "missing $f in ${s.formats}")
        }
    }

    /**
     * Opens real files through the DLL from paths with non-ASCII names. The source is opened before
     * the output, so on a machine without a sound device (CI) the only acceptable failure is the
     * output: that proves the Windows file access (UTF-16 paths, pread for DSD) and the decoders.
     */
    @Test fun opensFilesWithUnicodePaths() {
        assumeTrue(dllPresent(), "engine DLL not built / not on Windows")
        val dir = kotlin.io.path.createTempDirectory("allmeyn Ünïcødé ").toFile()
        val flac = File(dir, "Björk – Jóga.flac")
        File(ASSETS, "clip16.flac").copyTo(flac)
        val dsf = File(dir, "Sigur Rós – Hoppípolla.dsf").also { writeDsf(it) }
        for (f in listOf(flac, dsf)) {
            val err = NativeEngine.playerOpen(f.absolutePath)
            println("${f.name}: ${err ?: "opened"}")
            if (err == null) {
                val info = NativeEngine.playerInfo()
                assertTrue(info[0] == "FLAC" || info[0].contains("DSD64"), info.joinToString())
                NativeEngine.playerClose()
            } else {
                assertTrue(listOf("output", "device").any { err.contains(it, ignoreCase = true) }, "${f.name}: source failed: $err")
            }
        }
        // Library tags through the DLL (and the field layout the scanner expects).
        val flacTags = com.allmeyn.desktop.library.FileTags.from(NativeEngine.readTags(flac.absolutePath))
        assertTrue(flacTags != null && flacTags.format == "FLAC" && flacTags.bits == 16 && flacTags.durationMs in 1950..2050, "$flacTags")
        val dsfTags = com.allmeyn.desktop.library.FileTags.from(NativeEngine.readTags(dsf.absolutePath))
        assertTrue(dsfTags != null && dsfTags.format == "DSF DSD64" && dsfTags.sampleRate == 2822400, "$dsfTags")
        assertTrue(NativeEngine.readCover(flac.absolutePath) == null)
        val missing = NativeEngine.playerOpen(File(dir, "nope.flac").absolutePath)
        assertTrue(missing != null && !missing.contains("output", ignoreCase = true), "missing file: $missing")
        dir.deleteRecursively()
    }

    /** 0.5 s of DSD64 stereo silence (0x69) as a DSF file. */
    private fun writeDsf(f: File) {
        val block = 4096
        val bytesPerCh = 2822400L / 8 / 2
        val blocks = (bytesPerCh + block - 1) / block
        val data = blocks * block * 2
        val b = java.nio.ByteBuffer.allocate((28 + 52 + 12 + data).toInt()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("DSD ".toByteArray()).putLong(28).putLong(28 + 52 + 12 + data).putLong(0)
        b.put("fmt ".toByteArray()).putLong(52).putInt(1).putInt(0).putInt(2).putInt(2).putInt(2822400).putInt(1)
            .putLong(bytesPerCh * 8).putInt(block).putInt(0)
        b.put("data".toByteArray()).putLong(12 + data)
        while (b.hasRemaining()) b.put(0x69)
        f.writeBytes(b.array())
    }

    @Test fun listsOutputsWithoutCrashing() {
        assumeTrue(dllPresent(), "engine DLL not built / not on Windows")
        // CI machines may have no audio device at all; this only proves COM/WASAPI calls are safe.
        val devices = NativeEngine.devices()
        println("Outputs: " + devices.joinToString { "${it.name} (${it.mixLabel})" })
        // Usually none on CI; proves the JNI link and the registry read are safe.
        println("ASIO drivers: " + NativeEngine.asioDriverNames())
    }
}
