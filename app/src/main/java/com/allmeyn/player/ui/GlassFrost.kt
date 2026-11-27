package com.allmeyn.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Settings → Appearance → Glass frosting, 0 (clear) – 100 (frosted); 50 is the glass library's own
 * look. Snapshot state: every glass component drawn with [AppGlass] follows a change at once. Each
 * app loads and saves it (BackdropSettings) and sets [level] at start.
 */
object GlassFrost {
    const val DEFAULT = 50
    var level by mutableIntStateOf(DEFAULT)

    fun label(v: Int) = when (v) { 0 -> "Clear"; 100 -> "Frosted"; else -> "$v %" }
}

/**
 * The glass the app's components draw with: the library's Material defaults (GlassMaterialDefaults,
 * Liquid Glass 1.0.0) with the frost set by [GlassFrost]. At 50 they are the library's exactly:
 * - the material is GlassStyle.inApp at Tint Amount = frost (iOS's Clear … Tinted: how opaque the
 *   tint is and how much of the backdrop's detail it softens);
 * - panels (dialogs, menus, sheets) blur what's behind them by 2 – 13.4 dp (7.7 at 50, the library's);
 * - controls get a backdrop blur only above 50 (up to 6 dp), as frosted glass.
 * The tab bar and the top bar's buttons keep the library's fixed glass (it has no setting for them).
 */
object AppGlass {
    private class Kinds(val control: Glass, val panel: Glass)
    private val cache = HashMap<Int, Kinds>()

    private fun set(frost: Int): Kinds = synchronized(cache) {
        cache.getOrPut(frost) {
            val t = frost.toFloat()
            val controlBlur = if (frost > 50) (6f * (frost - 50) / 50f).dp else Dp.Unspecified
            val panelBlur = (2f + 5.7f * frost / 50f).dp
            fun style(dark: Boolean) = ios27Rim(GlassStyle.inApp(dark, tintAmount = t), dark)
            Kinds(
                control = Glass(style(false).copy(backdropSigma = controlBlur), dark = style(true).copy(backdropSigma = controlBlur), light = Overhead),
                panel = Glass(style(false).copy(backdropSigma = panelBlur), dark = style(true).copy(backdropSigma = panelBlur), light = Overhead),
            )
        }
    }

    /** The plain and panel glass at [frost] (for tests: at 50 they're the library's). */
    internal fun control(frost: Int): Glass = set(frost).control
    internal fun panel(frost: Int): Glass = set(frost).panel

    /** Plain glass: secondary buttons, chips (GlassMaterialDefaults.regular off a panel). */
    val regular: Glass @Composable get() = set(GlassFrost.level).control

    /** Large surfaces: dialogs, menus, sheets, the mini player (GlassMaterialDefaults.panel). */
    val panel: Glass @Composable get() = set(GlassFrost.level).panel

    /** The main action: glass tinted with [color] (GlassMaterialDefaults.prominent). [onPanel]: inside a dialog or sheet. */
    @Composable
    fun prominent(color: Color = MaterialTheme.colorScheme.primary, onPanel: Boolean = false): Glass =
        (if (onPanel) panel else regular).tint(color.copy(alpha = PROMINENT_ALPHA))

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

/** The Settings row: label, value and a 5 %-step slider (live while dragging). */
@Composable
fun GlassFrostSetting(value: Int, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Glass frosting", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(GlassFrost.label(value), color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "How frosted the glass buttons, menus, dialogs and sheets are: clear shows more of what's behind them, " +
                "frosted blurs it more. 50 % is the standard look.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.toFloat(), valueRange = 0f..100f, steps = 19,
            onValueChange = { v -> ((v / 5f).roundToInt() * 5).coerceIn(0, 100).let { if (it != value) onChange(it) } },
        )
    }
}
