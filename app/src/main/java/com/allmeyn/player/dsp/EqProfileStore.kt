package com.allmeyn.player.dsp

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Saved headphone EQ profiles (EqLibrary text in `eq_profiles.txt`, its own file: the DSP
 * settings keep only the active profile). Small, so read and written whole; replaced in one step.
 */
class EqProfileStore(context: Context) {
    private val file = File(context.filesDir, "eq_profiles.txt")

    fun load(): List<EqProfile> = try {
        if (file.isFile) EqLibrary.decode(file.readText()) else emptyList()
    } catch (e: IOException) {
        Log.w("AllMeyn", "couldn't read EQ profiles", e)
        emptyList()
    }

    /** Adds [profile] (replacing one with the same name) and saves; returns the new list. */
    fun save(profile: EqProfile): List<EqProfile> = write(load().filter { it.name != profile.name } + profile)

    fun delete(name: String): List<EqProfile> = write(load().filter { it.name != name })

    private fun write(list: List<EqProfile>): List<EqProfile> {
        val sorted = list.sortedBy { it.name.lowercase() }
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(EqLibrary.encode(sorted))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: IOException) {
            Log.w("AllMeyn", "couldn't save EQ profiles", e)
        }
        return sorted
    }
}
