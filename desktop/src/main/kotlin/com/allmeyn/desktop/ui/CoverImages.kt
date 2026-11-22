package com.allmeyn.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded covers, most recently used kept (the files are ≤600 px JPEGs, ~1.4 MB each decoded). */
object CoverImages {
    private const val MAX = 96
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX
    }

    fun cached(path: String): ImageBitmap? = synchronized(cache) { cache[path] }

    suspend fun load(path: String): ImageBitmap? {
        if (path.isEmpty()) return null
        cached(path)?.let { return it }
        val img = withContext(Dispatchers.IO) {
            try {
                org.jetbrains.skia.Image.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap()
            } catch (e: Throwable) {
                null
            }
        } ?: return null
        synchronized(cache) { cache[path] = img }
        return img
    }
}

/** An album cover (square, rounded), or the AM mark on a plain tile when there's none. */
@Composable
fun Cover(path: String, size: Dp, modifier: Modifier = Modifier) {
    val image by produceState(CoverImages.cached(path), path) { value = CoverImages.load(path) }
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 16)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(BrandMark.vector, contentDescription = null, tint = BrandCopper.copy(alpha = 0.35f), modifier = Modifier.size(size * 0.45f))
        }
    }
}
