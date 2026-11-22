package com.allmeyn.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.wexpa.liquidglass.material3.Button

/** The window's size: open Folds and tablets are [wide]; phones turned sideways are [short]. */
data class WindowSize(val width: Dp, val height: Dp) {
    val wide get() = width >= 600.dp
    val short get() = height < 480.dp
    /** Wider than tall with room for two columns (phone landscape, an open Fold). */
    val sideBySide get() = width > height && width >= 560.dp
}

val LocalWindowSize = compositionLocalOf { WindowSize(400.dp, 800.dp) }

/** Text and lists never grow wider than this; on wider screens they sit centred. */
val MaxContentWidth = 720.dp

/** Measures the window once for everything below it. */
@Composable
fun ProvideWindowSize(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalWindowSize provides WindowSize(maxWidth, maxHeight)) { content() }
    }
}

/** Side padding that centres [MaxContentWidth] of content on wide screens (0 on phones). */
@Composable
fun sideInset(): Dp = ((LocalWindowSize.current.width - MaxContentWidth) / 2).coerceAtLeast(0.dp)

/** How much of the screen's bottom the glass mini player and tab bar cover: lists pad their end by it. */
val LocalBottomBarPadding = compositionLocalOf { 0.dp }

/**
 * A list's content padding plus [LocalBottomBarPadding] (so it scrolls under the bottom bars) and,
 * unless [fullWidth], the [sideInset] that keeps it centred on wide screens.
 */
@Composable
fun belowBars(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp, fullWidth: Boolean = false): PaddingValues {
    val side = if (fullWidth) 0.dp else sideInset()
    return PaddingValues(start + side, top, end + side, bottom + LocalBottomBarPadding.current)
}

/** The logo's copper, for the brand mark. */
val BrandCopper = Color(0xFFD98B52)

/** The one look for "nothing here": icon, title, a line of help and an optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String = "",
    action: String? = null,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = 56.dp,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(bottom = LocalBottomBarPadding.current).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = iconTint)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message.isNotEmpty()) {
            Text(
                message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 420.dp).padding(top = 8.dp),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/** Shown while the library loads, e.g. right after the app was restored on an album. */
@Composable
fun Loading(label: String = "") {
    Column(
        Modifier.fillMaxSize().padding(bottom = LocalBottomBarPadding.current),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}
