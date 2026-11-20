package com.allmeyn.player.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Album covers, downscaled to [MAX_SIZE] px JPEGs in app storage, one file per album key. */
class ArtCache(context: Context) {
    private val dir = File(context.filesDir, "art").apply { mkdirs() }

    fun fileFor(albumKey: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(albumKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.jpg")
    }

    /** Stores [bytes] (any image format Android decodes) for [albumKey]. Returns the path or "". */
    fun store(albumKey: String, bytes: ByteArray): String {
        val out = fileFor(albumKey)
        if (out.exists()) return out.path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return ""
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIZE && bounds.outHeight / (sample * 2) >= MAX_SIZE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return ""
        val scaled = if (maxOf(bmp.width, bmp.height) > MAX_SIZE) {
            val f = MAX_SIZE.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        } else {
            bmp
        }
        val tmp = File(dir, out.name + ".tmp")
        FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(out)
        return out.path
    }

    fun existing(albumKey: String): String = fileFor(albumKey).takeIf { it.exists() }?.path ?: ""

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val MAX_SIZE = 600
    }
}
