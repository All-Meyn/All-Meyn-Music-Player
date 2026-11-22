package com.allmeyn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.allmeyn.desktop.engine.NativeEngine
import com.allmeyn.desktop.engine.OutputDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import com.allmeyn.desktop.library.DesktopLibrary

/** Music folders, the audio engine and the Windows outputs. */
@Composable
fun SettingsScreen(library: DesktopLibrary, onAddFolder: () -> Unit, onRemoveFolder: (String) -> Unit, onRescan: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ScreenTitle("Settings")
            Panel("Music folders") {
                if (library.folders.isEmpty()) Text("No folders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                library.folders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRemoveFolder(folder) }, enabled = library.scanProgress == null) { Text("Remove") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onAddFolder, enabled = library.scanProgress == null) { Text("Add folder…") }
                    OutlinedButton(onClick = onRescan, enabled = library.scanProgress == null && library.folders.isNotEmpty()) { Text("Rescan") }
                    val p = library.scanProgress
                    Text(
                        if (p != null) "Reading ${p.first} / ${p.second}…" else "${library.view.tracks.size} songs, ${library.view.albums.size} albums",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            EngineCard()
            OutputsCard()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(BrandMark.vector, contentDescription = null, tint = BrandCopper, modifier = Modifier.size(width = 46.dp, height = 24.dp))
                Text("All-Meyn Player · Your library. Your hardware. All yours.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun EngineCard() {
    Panel("Audio engine") {
        when (val s = NativeEngine.status) {
            is NativeEngine.Status.Ready -> {
                Line("Decoder", "FFmpeg ${s.ffmpeg}")
                Line("Formats", s.formats.joinToString(", "))
                Line("Output", "Shared mode for now; bit-perfect (exclusive / ASIO) comes in a later stage")
            }
            is NativeEngine.Status.Unavailable -> Text(
                "The audio engine didn't load: ${s.reason}",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun OutputsCard() {
    var devices by remember { mutableStateOf<List<OutputDevice>?>(null) }
    LaunchedEffect(Unit) { devices = withContext(Dispatchers.IO) { NativeEngine.devices() } }
    Panel("Audio outputs") {
        val list = devices
        when {
            list == null -> Text("Looking for outputs…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text("No outputs found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> list.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(d.name, fontWeight = FontWeight.Medium)
                        if (d.isDefault) Text("Default", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("Windows mixer: ${d.mixLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
