package com.allmeyn.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.util.prefs.Preferences
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * The Android app's background (ui/Artwork.kt ArtBackdrop): the playing song's cover filling the
 * window, blurred, behind everything (the glass refracts it). Blurred once in software at 48 px and
 * stretched with smooth filtering, so it costs nothing per frame. A scrim in the surface colour keeps
 * text readable; it fades between songs and turns slowly (one turn a minute) while music plays.
 * Nothing is drawn while nothing plays or a song has no cover. [strength] 0–100 (Settings →
 * Appearance): how much of the cover shows through the scrim; 0 = none.
 */
@Composable
fun ArtBackdrop(path: String, strength: Int, playing: Boolean, modifier: Modifier = Modifier) {
    val blurred by produceState(initialValue = path.takeIf { it.isNotEmpty() }?.let(BackdropMemory::get), path) {
        if (path.isEmpty()) { value = null; return@produceState }
        value = BackdropMemory.get(path) ?: withContext(Dispatchers.IO) { blurredThumbnail(path)?.also { BackdropMemory.put(path, it) } }
    }
    if (strength <= 0) return
    val scrim = MaterialTheme.colorScheme.surface.copy(alpha = 1f - strength.coerceAtMost(100) / 100f)
    val spin = rememberBackdropSpin(playing)
    Crossfade(targetState = blurred, animationSpec = tween(600), modifier = modifier.fillMaxSize(), label = "backdrop") { b ->
        if (b != null) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    b, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                    // Read in the draw phase only: turning never recomposes anything.
                    modifier = Modifier.coverWhenRotated().graphicsLayer { rotationZ = spin.floatValue },
                )
                Box(Modifier.fillMaxSize().background(scrim))
            }
        }
    }
}

/** One clockwise turn of the background takes this long (as on Android). */
const val BACKDROP_SPIN_PERIOD_MS = 60_000L

/** The background's angle (degrees, clockwise, 0..360) after turning [elapsedMs] more from [degrees]. */
fun advanceBackdropSpin(degrees: Float, elapsedMs: Long): Float =
    ((degrees + elapsedMs.coerceAtLeast(0) * 360f / BACKDROP_SPIN_PERIOD_MS) % 360f)

/**
 * Turns only while music [playing], holding its angle when paused. Updated about 30 times a second,
 * not at the display's rate: on a blur this soft the steps can't be seen, and every update makes the
 * glass record the window again.
 */
@Composable
private fun rememberBackdropSpin(playing: Boolean): MutableFloatState {
    val angle = remember { mutableFloatStateOf(0f) }
    if (playing) {
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

/** A square as wide as the window's diagonal, centred, so that turned to any angle it covers every corner. */
private fun Modifier.coverWhenRotated() = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val side = ceil(hypot(w.toDouble(), h.toDouble())).toInt()
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(w, h) { placeable.place((w - side) / 2, (h - side) / 2) }
}

/** Settings → Appearance → Background art, 0–100 % (default 40, as on Android). */
object BackdropSettings {
    const val DEFAULT = 40
    private val prefs: Preferences = Preferences.userRoot().node("com/allmeyn/desktop")
    fun strength(): Int = prefs.getInt("backdropStrength", DEFAULT).coerceIn(0, 100)
    fun setStrength(value: Int) = prefs.putInt("backdropStrength", value.coerceIn(0, 100))
}

private object BackdropMemory {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 16
    }
    fun get(path: String): ImageBitmap? = synchronized(cache) { cache[path] }
    fun put(path: String, b: ImageBitmap) = synchronized(cache) { cache[path] = b }
}

private const val BACKDROP_PX = 48

/** The cover (our ≤ 600 px cached JPEG) scaled to 48 px wide and box-blurred three times (≈ Gaussian). */
private fun blurredThumbnail(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.isFile) return null
    val image = runCatching { Image.makeFromEncoded(file.readBytes()) }.getOrNull() ?: return null
    if (image.width <= 0 || image.height <= 0) return null
    val w = BACKDROP_PX
    val h = (BACKDROP_PX * image.height / image.width).coerceIn(1, BACKDROP_PX * 4)
    val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    val surface = Surface.makeRaster(info)
    surface.canvas.drawImageRect(
        image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
        FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true,
    )
    val bitmap = Bitmap().apply { allocPixels(info) }
    if (!surface.readPixels(bitmap, 0, 0)) return null
    val bytes = bitmap.readPixels() ?: return null
    val px = IntArray(w * h) { i ->
        // BGRA bytes -> ARGB ints (covers are opaque).
        val b = bytes[i * 4].toInt() and 0xFF; val g = bytes[i * 4 + 1].toInt() and 0xFF; val r = bytes[i * 4 + 2].toInt() and 0xFF
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    repeat(3) { boxBlur(px, w, h, radius = 4) }
    for (i in px.indices) {
        val c = px[i]
        bytes[i * 4] = (c and 0xFF).toByte(); bytes[i * 4 + 1] = ((c shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((c shr 16) and 0xFF).toByte(); bytes[i * 4 + 3] = 0xFF.toByte()
    }
    bitmap.installPixels(info, bytes, w * 4)
    return bitmap.toComposeImageBitmap()
}

/** One horizontal then one vertical box blur of opaque ARGB pixels, edges clamped (the Android app's boxBlur). */
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
