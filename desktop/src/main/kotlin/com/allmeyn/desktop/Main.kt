package com.allmeyn.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.allmeyn.desktop.ui.AllMeynTheme
import com.allmeyn.desktop.ui.HomeScreen
import com.allmeyn.desktop.ui.StartupOverlay

/** The app icon for the window and taskbar (the same artwork as the Play Store icon). */
private val appIcon: ImageBitmap? by lazy {
    Thread.currentThread().contextClassLoader.getResourceAsStream("all-meyn-icon.png")
        ?.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "All-Meyn Player",
        icon = appIcon?.let { BitmapPainter(it) },
        state = rememberWindowState(size = DpSize(1100.dp, 720.dp)),
    ) {
        AllMeynTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                HomeScreen()
                StartupOverlay()
            }
        }
    }
}
