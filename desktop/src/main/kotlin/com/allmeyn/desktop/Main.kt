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
import com.allmeyn.desktop.ui.ShortcutTarget
import com.allmeyn.desktop.ui.StartupOverlay
import com.allmeyn.desktop.ui.shortcutFor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.allmeyn.desktop.ui.rememberWindowsDark
import com.wexpa.liquidglass.LiquidGlassTheme

/** The app icon for the window and taskbar (the same artwork as the Play Store icon). */
private val appIcon: ImageBitmap? by lazy {
    Thread.currentThread().contextClassLoader.getResourceAsStream("all-meyn-icon.png")
        ?.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
}

fun main() = application {
    remember { com.allmeyn.player.ui.GlassFrost.level = com.allmeyn.desktop.ui.BackdropSettings.frost() }  // before anything draws
    val library = remember { DesktopLibrary() }
    val appScope = rememberCoroutineScope()
    val player = remember { PlayerController(appScope) }
    val shortcuts = remember { ShortcutTarget() }
    Window(
        // Keys nothing focused used (a text field keeps Space and the arrows) become shortcuts.
        onKeyEvent = { e ->
            e.type == KeyEventType.KeyDown &&
                shortcutFor(e.key, e.isCtrlPressed, e.isShiftPressed, e.isAltPressed)?.let { shortcuts.handler?.invoke(it) } == true
        },
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
        val dark = rememberWindowsDark()
        // The window's title bar follows too (Windows draws it; light by default).
        androidx.compose.runtime.LaunchedEffect(dark) {
            if (NativeEngine.isReady) NativeEngine.setDarkTitleBar(window.windowHandle, dark)
        }
        AllMeynTheme(dark) {
            Box(Modifier.fillMaxSize()) {
                // Liquid Glass, as in the Android app: records the window once per frame and every glass
                // element refracts it; 70 % resolution (the library's measured jank halving; edges stay sharp).
                LiquidGlassTheme(dark = dark, background = MaterialTheme.colorScheme.background, renderScale = 0.7f) {
                    // Text without its own colour is light on the dark background (Android gets this
                    // from its root Surface).
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            App(library, player, pickFolder, shortcuts)
                        }
                    }
                }
                // The intro sits above the glass layer (glass draws over everything else inside the theme).
                StartupOverlay()
            }
        }
    }
}
