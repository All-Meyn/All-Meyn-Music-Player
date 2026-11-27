package com.allmeyn.player.ui

import android.content.Context
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.hypot
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.RoundedCornerShape

private object ArtMemory {
    // ~32 MB of decoded covers.
    val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

/**
 * Album cover from the library's art cache, with a placeholder while loading or when missing.
 * [size] is how big it is shown: the cover is decoded at about that size (a 44 dp row needs a
 * quarter of the 600 px cache image), so scrolling lists decode, keep and upload far less.
 */
@Composable
fun Artwork(path: String, modifier: Modifier = Modifier, corner: Dp = 8.dp, size: Dp = 600.dp) {
    val targetPx = with(LocalDensity.current) { size.roundToPx() }
    val key = "$path#$targetPx"
    fun cached(): Bitmap? = if (path.isEmpty()) null else ArtMemory.cache.get(key)
    val bitmap by produceState(initialValue = cached(), key) {
        value = cached() // reset when the cover or size changes
        if (path.isEmpty() || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            decodeAtLeast(path, targetPx)?.also { ArtMemory.cache.put(key, it) }
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

/** Decodes [path] with the largest power-of-two subsampling that keeps it at least [px] wide and tall. */
private fun decodeAtLeast(path: String, px: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * The playing song's cover, filling the screen and blurred, behind everything (the glass refracts
 * it). Blurred once in software at 48 px and stretched with smooth filtering, so it looks the same
 * on every Android version and costs nothing per frame. A scrim in the surface colour keeps text
 * readable; it fades between songs. Nothing is drawn while nothing plays or a song has no cover.
 * [strength] 0–100 (Settings → Appearance): how much of the cover shows through the scrim; 0 = none.
 */
@Composable
fun ArtBackdrop(path: String, strength: Int, playing: Boolean, modifier: Modifier = Modifier) {
    val blurred by produceState(initialValue = path.takeIf { it.isNotEmpty() }?.let { BackdropMemory.cache.get(it) }, path) {
        if (path.isEmpty()) { value = null; return@produceState }
        value = BackdropMemory.cache.get(path) ?: withContext(Dispatchers.IO) {
            blurredThumbnail(path)?.also { BackdropMemory.cache.put(path, it) }
        }
    }
    if (strength <= 0) return
    val scrim = MaterialTheme.colorScheme.surface.copy(alpha = 1f - strength.coerceAtMost(100) / 100f)
    val spin = rememberBackdropSpin(playing)
    Crossfade(targetState = blurred, animationSpec = tween(600), modifier = modifier.fillMaxSize(), label = "backdrop") { b ->
        if (b != null) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .coverWhenRotated()
                        // Read in the draw phase only: turning never recomposes anything.
                        .graphicsLayer { rotationZ = spin.floatValue },
                )
                Box(Modifier.fillMaxSize().background(scrim))
            }
        }
    }
}

/** One clockwise turn of the background takes this long. */
const val BACKDROP_SPIN_PERIOD_MS = 60_000L

/** The background's angle (degrees, clockwise, 0..360) after turning [elapsedMs] more from [degrees]. */
fun advanceBackdropSpin(degrees: Float, elapsedMs: Long): Float =
    ((degrees + elapsedMs.coerceAtLeast(0) * 360f / BACKDROP_SPIN_PERIOD_MS) % 360f)

/**
 * The slowly turning angle: it turns only while music [playing], and holds where it is when
 * playback pauses or stops (resuming carries on from there). Updated about 30 times a second, not
 * at the display's rate: on a blur this soft the steps can't be seen, and every update makes the
 * glass record the screen again (the library's advice is to quantise slow animations). Driven by
 * frames, so it also stops while the app isn't visible; stays still with "Remove animations".
 */
@Composable
private fun rememberBackdropSpin(playing: Boolean): MutableFloatState {
    val context = LocalContext.current
    val angle = remember { mutableFloatStateOf(0f) }
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    if (animate && playing) {
        LaunchedEffect(Unit) {
            var last = withFrameMillis { it }
            while (true) {
                withFrameMillis { now ->
                    angle.floatValue = advanceBackdropSpin(angle.floatValue, now - last)
                    last = now
                }
                delay(33)
            }
        }
    }
    return angle
}

/**
 * Lays the image out as a square as wide as the screen's diagonal, centred, so that turned to any
 * angle it still covers every corner.
 */
private fun Modifier.coverWhenRotated() = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val side = ceil(hypot(w.toDouble(), h.toDouble())).toInt()
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(w, h) { placeable.place((w - side) / 2, (h - side) / 2) }
}

/** Settings → Appearance → Background art, 0–100 %. Its own prefs file; nothing else reads it. */
object BackdropSettings {
    const val DEFAULT = 40 // = the original 60 % scrim
    private fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun strength(context: Context): Int = prefs(context).getInt("backdrop_strength", DEFAULT).coerceIn(0, 100)
    fun setStrength(context: Context, value: Int) {
        prefs(context).edit().putInt("backdrop_strength", value.coerceIn(0, 100)).apply()
    }

    /** Settings → Appearance → Glass frosting, 0–100 (GlassFrost). */
    fun frost(context: Context): Int = prefs(context).getInt("glass_frost", GlassFrost.DEFAULT).coerceIn(0, 100)
    fun setFrost(context: Context, value: Int) {
        prefs(context).edit().putInt("glass_frost", value.coerceIn(0, 100)).apply()
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
