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
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import com.allmeyn.desktop.engine.OutputSettings
import com.allmeyn.desktop.library.DesktopLibrary

/** Music folders, the audio engine and the Windows outputs. */
@Composable
fun SettingsScreen(
    library: DesktopLibrary,
    output: OutputSettings,
    onOutputChange: (OutputSettings) -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRescan: () -> Unit,
) {
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
            OutputCard(output, onOutputChange)
            EngineCard()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(BrandMark.vector, contentDescription = null, tint = BrandCopper, modifier = Modifier.size(width = 46.dp, height = 24.dp))
                Text("All-Meyn Player · Your library. Your hardware. All yours.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("ASIO is a trademark and software of Steinberg Media Technologies GmbH.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Line("DSD", "Native with ASIO (when the driver does DSD), else DoP (exclusive or ASIO), else PCM")
            }
            is NativeEngine.Status.Unavailable -> Text(
                "The audio engine didn't load: ${s.reason}",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Pick the output and how songs reach it. */
@Composable
private fun OutputCard(settings: OutputSettings, onChange: (OutputSettings) -> Unit) {
    var devices by remember { mutableStateOf<List<OutputDevice>?>(null) }
    var asio by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            devices = NativeEngine.devices()
            asio = NativeEngine.asioDriverNames()
        }
    }
    Panel("Output") {
        val list = devices
        val choices = listOf<OutputDevice?>(null) + list.orEmpty()
        choices.forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val id = d?.id.orEmpty()
            Row(
                Modifier.fillMaxWidth().clickable { onChange(settings.copy(deviceId = id)) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.deviceId == id, onClick = { onChange(settings.copy(deviceId = id)) })
                Column(Modifier.padding(start = 8.dp)) {
                    if (d == null) {
                        Text("Windows default", fontWeight = FontWeight.Medium)
                        Text(list?.firstOrNull { it.isDefault }?.name ?: "Follows Windows' sound settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(d.name, fontWeight = FontWeight.Medium)
                        Text("Windows mixer: ${d.mixLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (list == null) Text("Looking for outputs…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (asio.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("ASIO drivers", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            asio.forEachIndexed { i, name ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val id = OutputSettings.asioId(name)
                Row(
                    Modifier.fillMaxWidth().clickable { onChange(settings.copy(deviceId = id)) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.deviceId == id, onClick = { onChange(settings.copy(deviceId = id)) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(name, fontWeight = FontWeight.Medium)
                        Text("ASIO · bit-perfect · native DSD when the driver supports it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // ASIO always has the DAC to itself; exclusive is a WASAPI choice.
        if (!settings.isAsio) SwitchLine(
            "Exclusive mode (bit-perfect)",
            "Each song goes to the DAC at its own rate and bit depth, untouched, bypassing the Windows mixer. " +
                "Other apps can't play on this output meanwhile; use the DAC's volume.",
            settings.exclusive,
        ) { onChange(settings.copy(exclusive = it)) }
        SwitchLine(
            "DSD as DoP",
            when {
                settings.isAsio -> "Native DSD comes first; DoP is used when the driver doesn't take DSD. Off: PCM instead."
                settings.exclusive -> "DSF/DFF reach the DAC as DSD (packed in PCM frames). Off: DSD plays as PCM."
                else -> "Needs exclusive mode; DSD plays as PCM meanwhile."
            },
            settings.dop, enabled = settings.dopPossible,
        ) { onChange(settings.copy(dop = it)) }
    }
}

@Composable
private fun SwitchLine(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
