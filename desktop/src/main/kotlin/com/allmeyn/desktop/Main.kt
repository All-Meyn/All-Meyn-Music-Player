package com.allmeyn.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.allmeyn.desktop.engine.NativeEngine
import com.allmeyn.desktop.library.DesktopLibrary
import com.allmeyn.desktop.ui.AllMeynTheme
import com.allmeyn.desktop.ui.App
import com.allmeyn.desktop.ui.PlayerController
import com.allmeyn.desktop.ui.StartupOverlay
import com.wexpa.liquidglass.LiquidGlassTheme

/** The app icon for the window and taskbar (the same artwork as the Play Store icon). */
private val appIcon: ImageBitmap? by lazy {
    Thread.currentThread().contextClassLoader.getResourceAsStream("all-meyn-icon.png")
        ?.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
}

fun main() = application {
    val library = remember { DesktopLibrary() }
    val appScope = rememberCoroutineScope()
    val player = remember { PlayerController(appScope) }
    Window(
        onCloseRequest = {
            // Keep the queue exactly as it is now (the periodic save may be up to 10 s behind).
            // (Nothing queued: leave the file; emptying the queue is saved by App's loop.)
            player.snapshot()?.let { com.allmeyn.desktop.library.QueueStore.write(library.queueFile, it) }
            player.close()
            exitApplication()
        },
        title = "All-Meyn Player",
        icon = appIcon?.let { BitmapPainter(it) },
        state = rememberWindowState(size = DpSize(1240.dp, 800.dp)),
    ) {
        window.minimumSize = java.awt.Dimension(960, 600)
        // The Windows folder picker, over this window.
        val pickFolder: () -> String? = {
            if (NativeEngine.isReady) NativeEngine.pickFolder(window.windowHandle, "Choose your music folder") else null
        }
        AllMeynTheme {
            Box(Modifier.fillMaxSize()) {
                // Liquid Glass, as in the Android app: records the window once per frame and every glass
                // element refracts it; 70 % resolution (the library's measured jank halving; edges stay sharp).
                LiquidGlassTheme(dark = true, background = MaterialTheme.colorScheme.background, renderScale = 0.7f) {
                    // Text without its own colour is light on the dark background (Android gets this
                    // from its root Surface).
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            App(library, player, pickFolder)
                        }
                    }
                }
                // The intro sits above the glass layer (glass draws over everything else inside the theme).
                StartupOverlay()
            }
        }
    }
}
