package com.allmeyn.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.RoundedCornerShape

private object ArtMemory {
    // ~32 MB of decoded covers.
    val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

/** Album cover from the library's art cache, with a placeholder while loading or when missing. */
@Composable
fun Artwork(path: String, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    fun cached(p: String): Bitmap? = p.takeIf { it.isNotEmpty() }?.let { ArtMemory.cache.get(it) }
    val bitmap by produceState(initialValue = cached(path), path) {
        value = cached(path) // reset when the path changes
        if (path.isEmpty() || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path)?.also { ArtMemory.cache.put(path, it) }
        }
    }
    val shape = RoundedCornerShape(corner)
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
    } else {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(
                PlayerIcons.Note, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize().padding(24.dp),
            )
        }
    }
}
