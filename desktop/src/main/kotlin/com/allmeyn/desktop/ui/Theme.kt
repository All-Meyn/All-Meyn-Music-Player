package com.allmeyn.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

/** The logo's copper, for tinting the mark. */
val BrandCopper = Color(0xFFD98B52)

@Composable
fun AllMeynTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AllMeynDarkColors, content = content)
}
