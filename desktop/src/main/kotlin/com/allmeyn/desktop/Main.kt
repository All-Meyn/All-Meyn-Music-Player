package com.allmeyn.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import com.allmeyn.desktop.ui.AllMeynTheme
import com.allmeyn.desktop.engine.TrackInfo
import com.allmeyn.desktop.ui.HomeScreen
import com.allmeyn.desktop.ui.PlayerController
import com.allmeyn.desktop.ui.StartupOverlay
import java.awt.FileDialog
import java.io.File
import java.util.prefs.Preferences
import kotlinx.coroutines.launch

/** The app icon for the window and taskbar (the same artwork as the Play Store icon). */
private val appIcon: ImageBitmap? by lazy {
    Thread.currentThread().contextClassLoader.getResourceAsStream("all-meyn-icon.png")
        ?.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
}

/** Where the open dialog starts: the folder of the last song opened. */
private val prefs: Preferences = Preferences.userRoot().node("com/allmeyn/desktop")

fun main() = application {
    val player = remember { PlayerController() }
    Window(
        onCloseRequest = {
            player.close()
            exitApplication()
        },
        title = "All-Meyn Player",
        icon = appIcon?.let { BitmapPainter(it) },
        state = rememberWindowState(size = DpSize(1100.dp, 720.dp)),
    ) {
        val scope = rememberCoroutineScope()
        val openSong: () -> Unit = {
            // The native Windows open dialog (AWT FileDialog), filtered to music files.
            val dialog = FileDialog(window, "Open a song", FileDialog.LOAD).apply {
                prefs.get("lastFolder", null)?.let { directory = it }
                file = TrackInfo.EXTENSIONS.joinToString(";") { "*.$it" }
                isVisible = true
            }
            val name = dialog.file
            val dir = dialog.directory
            if (name != null && dir != null) {
                prefs.put("lastFolder", dir)
                scope.launch { player.open(File(dir, name)) }
            }
        }
        AllMeynTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                HomeScreen(player, openSong)
                StartupOverlay()
            }
        }
    }
}
