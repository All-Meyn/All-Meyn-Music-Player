package com.allmeyn.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wexpa.liquidglass.Glass
import com.wexpa.liquidglass.GlassLight
import com.wexpa.liquidglass.GlassStyle
import com.wexpa.liquidglass.material3.Slider
import kotlin.math.roundToInt

// Shared by the Android and Windows apps (desktop/build.gradle.kts, sharedSources).

/**
 * Settings → Appearance → Glass frosting, 0 (the clearest glass that still reads well) – 100 (heavy
 * frost); [DEFAULT] (35) is the glass library's own look. Snapshot state: every glass component drawn with [AppGlass] follows a change at once. Each
 * app loads and saves it (BackdropSettings) and sets [level] at start.
 */
object GlassFrost {
    const val DEFAULT = 35
    var level by mutableIntStateOf(DEFAULT)

    fun label(v: Int) = when (v) { 0 -> "Clearest"; DEFAULT -> "Standard"; 100 -> "Frosted"; else -> "$v %" }

    /** [from] at 0, [standard] at [DEFAULT], [to] at 100, straight lines between. */
    internal fun scale(frost: Int, from: Float, standard: Float, to: Float): Float =
        if (frost <= DEFAULT) from + (standard - from) * frost / DEFAULT
        else standard + (to - standard) * (frost - DEFAULT) / (100 - DEFAULT)
}

/**
 * The glass the app's components draw with: the library's Material defaults (GlassMaterialDefaults,
 * Liquid Glass 1.0.0) with the frost set by [GlassFrost]. At the default (35) they are the library's
 * exactly:
 * - the material is GlassStyle.inApp at a Tint Amount of 25 – 100 (iOS's Clear … Tinted: how opaque
 *   the tint is and how much of the backdrop's detail it softens; the library's 50 at the default);
 * - panels (dialogs, menus, sheets) blur what's behind them by 5 – 13.4 dp (the library's 7.7 at the
 *   default); less than 5 let what's behind compete with their text;
 * - controls get a backdrop blur only above the default (up to 6 dp), as frosted glass.
 * The tab bar and the top bar's buttons keep the library's fixed glass (it has no setting for them).
 */
object AppGlass {
    private class Kinds(val control: Glass, val panel: Glass)
    private val cache = HashMap<Int, Kinds>()

    private fun set(frost: Int): Kinds = synchronized(cache) {
        cache.getOrPut(frost) {
            val t = GlassFrost.scale(frost, 25f, 50f, 100f)
            val controlBlur = if (frost > GlassFrost.DEFAULT) GlassFrost.scale(frost, 0f, 0f, 6f).dp else Dp.Unspecified
            val panelBlur = GlassFrost.scale(frost, 5f, 7.7f, 13.4f).dp
            fun style(dark: Boolean) = ios27Rim(GlassStyle.inApp(dark, tintAmount = t), dark)
            Kinds(
                control = Glass(style(false).copy(backdropSigma = controlBlur), dark = style(true).copy(backdropSigma = controlBlur), light = Overhead),
                panel = Glass(style(false).copy(backdropSigma = panelBlur), dark = style(true).copy(backdropSigma = panelBlur), light = Overhead),
            )
        }
    }

    /** The plain and panel glass at [frost] (for tests: at the default they're the library's). */
    internal fun control(frost: Int): Glass = set(frost).control
    internal fun panel(frost: Int): Glass = set(frost).panel

    /** Plain glass: secondary buttons, chips (GlassMaterialDefaults.regular off a panel). Page glass: see [GlassStacking]. */
    val regular: Glass @Composable get() {
        GlassStacking.Appeared()
        return set(GlassFrost.level).control
    }

    /** Large surfaces: dialogs, menus, sheets, the mini player (GlassMaterialDefaults.panel). */
    val panel: Glass @Composable get() = set(GlassFrost.level).panel

    /** The main action: glass tinted with [color] (GlassMaterialDefaults.prominent). [onPanel]: inside a dialog or sheet. */
    @Composable
    fun prominent(color: Color = MaterialTheme.colorScheme.primary, onPanel: Boolean = false): Glass =
        (if (onPanel) panel else regular).tint(color.copy(alpha = PROMINENT_ALPHA))  // regular: page glass

    private const val PROMINENT_ALPHA = 0.62f // GlassMaterialDefaults.ProminentAlpha

    // iOS 27's measured light and rim, as GlassMaterialDefaults applies them (its Overhead and
    // Ios27Rim are private; values copied from liquidglass-material3 1.0.0, Apache License 2.0).
    private val Overhead = GlassLight(0f, -1f)

    private fun ios27Rim(style: GlassStyle, dark: Boolean): GlassStyle = style.copy(
        bevel = 1.6.dp,
        bevelPeak = 0f,
        specular = 0.27f,
        specularPower = 2.6f,
        outerContour = if (dark) 0.61f else 0.31f,
        outerContourFloor = if (dark) 0f else 0.25f,
        dispersion = 0f,
    )
}

/**
 * The glass library draws glass in the order it enters composition, so a glass button that scrolls
 * into view after the bars (Android's mini player and tab bar, which the pages scroll under) would draw
 * over them. Page glass ([AppGlass.regular], [AppGlass.prominent] off a panel) counts here as it
 * appears; the bars are keyed on [epoch], so they enter again, after it, and are back on top.
 * (Windows has nothing under its player bar: nothing there reads it.)
 */
object GlassStacking {
    var epoch by mutableIntStateOf(0)
        private set

    @Composable
    internal fun Appeared() {
        DisposableEffect(Unit) {
            epoch++
            onDispose { }
        }
    }
}

/** The Settings row: label, value and a 5 %-step slider (live while dragging). */
@Composable
fun GlassFrostSetting(value: Int, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Glass frosting", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(GlassFrost.label(value), color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "How frosted the glass buttons, menus, dialogs and sheets are: lower shows more of what's behind them " +
                "(still readable), higher blurs it more. Standard is 35 %.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.toFloat(), valueRange = 0f..100f, steps = 19,
            onValueChange = { v -> ((v / 5f).roundToInt() * 5).coerceIn(0, 100).let { if (it != value) onChange(it) } },
        )
    }
}
