package com.allmeyn.player.ui

import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.allmeyn.player.R
import com.wexpa.liquidglass.material3.Button
import com.wexpa.liquidglass.material3.ModalBottomSheet
import com.wexpa.liquidglass.material3.rememberModalBottomSheetState

/**
 * The brand colours: copper accents (from the logo) on cool near-neutral greys (the logo's dark
 * tile). Used instead of the wallpaper's dynamic colours so the app always looks like All-Meyn.
 */
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

/** Where the system folder picker opens: the phone's Music folder (it falls back if there is none). */
val MusicFolderUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music")

/**
 * Shown before Android's own folder picker (a system screen apps can't restyle): what it is and
 * what to do there, in our style, so the switch is expected.
 */
@OptIn(ExperimentalMaterial3Api::class) // the glass sheet mirrors Material 3's experimental sheet API
@Composable
fun ChooseFolderSheet(onContinue: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), glass = AppGlass.panel) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(ImageVector.vectorResource(R.drawable.ic_brand_mark), null, Modifier.size(72.dp), tint = BrandCopper)
            Text(
                "Choose your music folder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Next is Android's folder picker. It opens in Music: go to the folder that holds your " +
                    "music and tap \"Use this folder\". All-Meyn reads only that folder, including DSF, " +
                    "DFF, APE and WavPack, and never changes your files.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                Button(onClick = onContinue, glass = AppGlass.prominent(onPanel = true)) { Text("Continue") }
            }
            Text(
                stringResource(R.string.tagline), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
