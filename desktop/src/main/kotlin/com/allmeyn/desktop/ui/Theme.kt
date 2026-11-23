package com.allmeyn.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Copper on the logo's dark greys: the Android app's AllMeynDarkColors (ui/BrandTheme.kt). */
val AllMeynDarkColors = darkColorScheme(
    primary = Color(0xFFF0A56E), onPrimary = Color(0xFF4A2106),
    primaryContainer = Color(0xFF6A3815), onPrimaryContainer = Color(0xFFFFDBC6),
    inversePrimary = Color(0xFF8A4E25),
    secondary = Color(0xFFE2BFA9), onSecondary = Color(0xFF422B1C),
    secondaryContainer = Color(0xFF5B4130), onSecondaryContainer = Color(0xFFFFDBC6),
    tertiary = Color(0xFFD7C68D), onTertiary = Color(0xFF3A3005),
    tertiaryContainer = Color(0xFF524613), onTertiaryContainer = Color(0xFFF4E2A6),
    background = Color(0xFF17181C), onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF17181C), onSurface = Color(0xFFE4E2E6),
    surfaceVariant = Color(0xFF2C2D33), onSurfaceVariant = Color(0xFFC5C6CF),
    surfaceTint = Color(0xFFF0A56E),
    inverseSurface = Color(0xFFE4E2E6), inverseOnSurface = Color(0xFF2F3034),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44464E),
    surfaceBright = Color(0xFF3A3B40), surfaceDim = Color(0xFF121316),
    surfaceContainerLowest = Color(0xFF0F1013), surfaceContainerLow = Color(0xFF1A1B1F),
    surfaceContainer = Color(0xFF1E1F24), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/** Copper on cool near-neutral light greys: the Android app's AllMeynLightColors. */
val AllMeynLightColors = lightColorScheme(
    primary = Color(0xFF8A4E25), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC6), onPrimaryContainer = Color(0xFF331200),
    inversePrimary = Color(0xFFF0A56E),
    secondary = Color(0xFF755846), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBC6), onSecondaryContainer = Color(0xFF2B160A),
    tertiary = Color(0xFF675E2C), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEFE2A5), onTertiaryContainer = Color(0xFF201C00),
    background = Color(0xFFF9F9FB), onBackground = Color(0xFF1B1C1F),
    surface = Color(0xFFF9F9FB), onSurface = Color(0xFF1B1C1F),
    surfaceVariant = Color(0xFFE1E2EA), onSurfaceVariant = Color(0xFF44464E),
    surfaceTint = Color(0xFF8A4E25),
    inverseSurface = Color(0xFF303034), inverseOnSurface = Color(0xFFF2F0F4),
    outline = Color(0xFF75777F), outlineVariant = Color(0xFFC5C6CF),
    surfaceBright = Color(0xFFF9F9FB), surfaceDim = Color(0xFFD9D9DD),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3F6),
    surfaceContainer = Color(0xFFEDEDF0), surfaceContainerHigh = Color(0xFFE7E7EB),
    surfaceContainerHighest = Color(0xFFE2E2E5),
)

/** The logo's copper, for tinting the mark. */
val BrandCopper = Color(0xFFD98B52)

@Composable
fun AllMeynTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) AllMeynDarkColors else AllMeynLightColors, content = content)
}

/**
 * Whether Windows is in dark mode for apps (Settings → Personalisation → Colours), checked every
 * 2 s so a change applies while the app runs (as Android follows the system setting).
 */
@Composable
fun rememberWindowsDark(): Boolean {
    fun read() = org.jetbrains.skiko.currentSystemTheme != org.jetbrains.skiko.SystemTheme.LIGHT
    val dark = androidx.compose.runtime.produceState(initialValue = read()) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            value = read()
        }
    }
    return dark.value
}
