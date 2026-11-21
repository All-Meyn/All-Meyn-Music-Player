package com.allmeyn.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.FilterQuality
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

/**
 * The playing song's cover, filling the screen and blurred, behind everything (the glass refracts
 * it). Blurred once in software at 48 px and stretched with smooth filtering, so it looks the same
 * on every Android version and costs nothing per frame. A scrim in the surface colour keeps text
 * readable; it fades between songs. Nothing is drawn while nothing plays or a song has no cover.
 * [strength] 0–100 (Settings → Appearance): how much of the cover shows through the scrim; 0 = none.
 */
@Composable
fun ArtBackdrop(path: String, strength: Int, modifier: Modifier = Modifier) {
    val blurred by produceState(initialValue = path.takeIf { it.isNotEmpty() }?.let { BackdropMemory.cache.get(it) }, path) {
        if (path.isEmpty()) { value = null; return@produceState }
        value = BackdropMemory.cache.get(path) ?: withContext(Dispatchers.IO) {
            blurredThumbnail(path)?.also { BackdropMemory.cache.put(path, it) }
        }
    }
    if (strength <= 0) return
    val scrim = MaterialTheme.colorScheme.surface.copy(alpha = 1f - strength.coerceAtMost(100) / 100f)
    Crossfade(targetState = blurred, animationSpec = tween(600), modifier = modifier.fillMaxSize(), label = "backdrop") { b ->
        if (b != null) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(scrim))
            }
        }
    }
}

/** Settings → Appearance → Background art, 0–100 %. Its own prefs file; nothing else reads it. */
object BackdropSettings {
    const val DEFAULT = 40 // = the original 60 % scrim
    private fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun strength(context: Context): Int = prefs(context).getInt("backdrop_strength", DEFAULT).coerceIn(0, 100)
    fun setStrength(context: Context, value: Int) {
        prefs(context).edit().putInt("backdrop_strength", value.coerceIn(0, 100)).apply()
    }
}

private object BackdropMemory {
    val cache = LruCache<String, Bitmap>(16) // ~9 KB each
}

private const val BACKDROP_PX = 48

/** The cover decoded small (never the full image), scaled to 48 px wide and box-blurred. */
private fun blurredThumbnail(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= BACKDROP_PX * 2 && bounds.outHeight / (sample * 2) >= BACKDROP_PX * 2) sample *= 2
    val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val w = BACKDROP_PX
    val h = (BACKDROP_PX * decoded.height / decoded.width).coerceIn(1, BACKDROP_PX * 4)
    val small = Bitmap.createScaledBitmap(decoded, w, h, true)
    if (small !== decoded) decoded.recycle()
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    repeat(3) { boxBlur(px, w, h, radius = 4) } // three box passes ≈ a Gaussian
    val out = small.copy(Bitmap.Config.ARGB_8888, true).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    if (out !== small) small.recycle()
    return out
}

/** One horizontal then one vertical box blur of opaque ARGB pixels, edges clamped. */
internal fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int) {
    val tmp = IntArray(px.size)
    fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, at: (line: Int, i: Int) -> Int) {
        val n = 2 * radius + 1
        for (line in 0 until lines) {
            var r = 0; var g = 0; var b = 0
            for (k in -radius..radius) {
                val c = src[at(line, k.coerceIn(0, len - 1))]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until len) {
                dst[at(line, i)] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                val add = src[at(line, (i + radius + 1).coerceAtMost(len - 1))]
                val sub = src[at(line, (i - radius).coerceAtLeast(0))]
                r += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
    pass(px, tmp, w, h) { y, x -> y * w + x }
    pass(tmp, px, h, w) { x, y -> y * w + x }
}
