package com.allmeyn.desktop.library

import com.allmeyn.player.dsp.EqLibrary
import com.allmeyn.player.dsp.EqProfile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Saved headphone EQ profiles: %LOCALAPPDATA%\All-Meyn\eq_profiles.txt (EqLibrary text, the same as
 * the Android app's). Small, so read and written whole, replaced in one step.
 */
class DesktopEqProfiles(dataDir: File = DesktopLibrary.defaultDataDir()) {
    private val file = File(dataDir, "eq_profiles.txt")

    fun load(): List<EqProfile> = try {
        if (file.isFile) EqLibrary.decode(file.readText()) else emptyList()
    } catch (e: IOException) {
        emptyList()
    }

    fun save(profile: EqProfile): List<EqProfile> = write(load().filter { it.name != profile.name } + profile)

    fun delete(name: String): List<EqProfile> = write(load().filter { it.name != name })

    private fun write(list: List<EqProfile>): List<EqProfile> {
        val sorted = list.sortedBy { it.name.lowercase() }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(EqLibrary.encode(sorted))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            System.err.println("Couldn't save EQ profiles: $e")
        }
        return sorted
    }
}
