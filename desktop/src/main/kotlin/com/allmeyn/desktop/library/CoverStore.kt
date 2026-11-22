package com.allmeyn.desktop.library

import java.io.File
import java.security.MessageDigest
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Album covers, downscaled to [maxSize] px JPEGs in the app's data folder, one per album key (as on Android). */
open class CoverStore(private val dir: File, private val maxSize: Int = 600) {
    fun fileFor(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.jpg")
    }

    /** The stored cover's path, or "". */
    fun existing(key: String): String = fileFor(key).takeIf { it.isFile }?.absolutePath.orEmpty()

    /** Stores [encoded] (JPEG/PNG/WebP) as the album's cover; its path, or "" if it isn't an image. */
    open fun store(key: String, encoded: ByteArray): String = try {
        val img = Image.makeFromEncoded(encoded)
        val scale = minOf(1f, maxSize.toFloat() / maxOf(img.width, img.height))
        val w = maxOf(1, (img.width * scale).toInt())
        val h = maxOf(1, (img.height * scale).toInt())
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(
            img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true,
        )
        val jpeg = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90) ?: error("encode failed")
        dir.mkdirs()
        val f = fileFor(key)
        f.writeBytes(jpeg.bytes)
        f.absolutePath
    } catch (e: Throwable) {
        ""
    }
}
