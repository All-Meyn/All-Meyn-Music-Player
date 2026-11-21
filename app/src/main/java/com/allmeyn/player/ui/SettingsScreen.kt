package com.allmeyn.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import com.wexpa.liquidglass.material3.Button
import com.wexpa.liquidglass.material3.Card
import com.wexpa.liquidglass.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.wexpa.liquidglass.material3.OutlinedButton
import com.wexpa.liquidglass.material3.Slider
import com.wexpa.liquidglass.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.allmeyn.player.BuildConfig
import com.allmeyn.player.library.MusicLibrary
import com.allmeyn.player.usb.DacStatus
import com.allmeyn.player.playback.ReplayGain
import com.allmeyn.player.usb.UsbAudioDriver
import kotlin.math.roundToInt

/** Music folders, the USB driver and its diagnostics, and the plugin tap. */
@UnstableApi
@Composable
fun SettingsScreen(
    library: MusicLibrary.State,
    folderLabel: (Uri) -> String,
    onAddFolder: () -> Unit,
    onRemoveFolder: (Uri) -> Unit,
    onRescan: () -> Unit,
    driver: UsbAudioDriver.State,
    driverStatus: String,
    androidDac: DacStatus,
    onDriver: (Boolean) -> Unit,
    onDacVolume: (Int) -> Unit,
    onNativeDsd: (Boolean) -> Unit,
    onAutoForDsd: (Boolean) -> Unit,
    replayGain: ReplayGain.Mode,
    onReplayGain: (ReplayGain.Mode) -> Unit,
    backdropStrength: Int,
    onBackdropStrength: (Int) -> Unit,
    dspOn: Boolean,
    onOpenDsp: () -> Unit,
    onProbe: ((String) -> Unit) -> Unit,
    onTone: (Int, Int) -> Unit,
    pluginName: String,
    pluginFormat: String,
    decoderLabel: String?,
    peak: Float,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomBarPadding.current).padding(horizontal = sideInset()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Library
        Section("Music folders") {
            if (library.folders.isEmpty()) Text("No folders yet.", style = MaterialTheme.typography.bodySmall)
            library.folders.forEach { uri ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(folderLabel(uri), Modifier.weight(1f))
                    IconButton(onClick = { onRemoveFolder(uri) }) { Icon(Icons.Filled.Delete, "Remove folder") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddFolder) { Text("Add folder") }
                OutlinedButton(onClick = onRescan, enabled = !library.scanning && library.folders.isNotEmpty()) { Text("Rescan") }
            }
            Text(
                if (library.scanning) library.progress else "${countOf(library.tracks.size, "song")} · ${countOf(library.albums.size, "album")}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- Output
        Section("Playback") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DSP", fontWeight = FontWeight.Medium)
                    Text(
                        if (dspOn) "On: compressor/expander, bass and impact restoration" else "Off (bit-perfect)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onOpenDsp) { Text("Open") }
            }
            Text("ReplayGain", fontWeight = FontWeight.Medium)
            Text(
                "Evens out loudness between songs using their ReplayGain tags (only turns down). " +
                    "Applies to Android output only: the bit-perfect USB driver never changes the audio.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplayGain.Mode.entries.forEach { m ->
                    FilterChip(
                        selected = replayGain == m,
                        onClick = { onReplayGain(m) },
                        label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }
        Section("Appearance") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Background art", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text(if (backdropStrength == 0) "Off" else "$backdropStrength %", color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "How strongly the playing song's blurred cover shows behind the app. 0 % = plain background.",
                style = MaterialTheme.typography.bodySmall,
            )
            // Live while dragging; saved only when the 5 % step changes.
            Slider(
                value = backdropStrength.toFloat(),
                onValueChange = { v -> (v / 5f).roundToInt().times(5).let { if (it != backdropStrength) onBackdropStrength(it) } },
                valueRange = 0f..100f,
                steps = 19,
            )
        }
        Section("Output") {
            if (driver.active) {
                Text("${driver.deviceName ?: "USB DAC"} · All-Meyn USB driver", fontWeight = FontWeight.Medium)
                Text(driverStatus.substringBefore('\n'), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(androidDac.deviceName ?: "Phone output", fontWeight = FontWeight.Medium)
                Text(androidDac.detail, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bit-perfect USB driver", fontWeight = FontWeight.Medium)
                    Text(
                        "Bypasses Android's mixer. Stays on and comes back when the DAC is plugged in.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = driver.active, enabled = !driver.busy, onCheckedChange = onDriver)
            }
            if (driver.message.isNotEmpty()) Text(driver.message, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Native DSD", fontWeight = FontWeight.Medium)
                    Text(
                        "Sends DSD in the DAC's own DSD mode (up to DSD512). Off: DoP (up to DSD256) for DACs that misbehave.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = driver.nativeDsd, onCheckedChange = onNativeDsd)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Turn on for DSD", fontWeight = FontWeight.Medium)
                    Text(
                        "Switches the USB driver on by itself when a DSD song starts, so it plays as DSD instead of PCM. Other apps can't use the DAC while the driver is on.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = driver.autoForDsd, onCheckedChange = onAutoForDsd)
            }
            driver.volume?.let { v ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (v.muted || v.step == 0) "DAC volume · muted" else "DAC volume · %.1f dB".format(v.db),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(140.dp),
                    )
                    // Commit on release: each change is a USB control request.
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = dragging ?: v.step.toFloat(),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = { dragging?.let { onDacVolume(it.roundToInt()) }; dragging = null },
                        valueRange = 0f..v.maxStep.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- Diagnostics
        Section("USB diagnostics") {
            var probe by rememberSaveable { mutableStateOf("") }
            if (driverStatus.isNotEmpty()) {
                Text(driverStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { probe = "Probing…"; onProbe { probe = it } }) { Text("Probe DAC") }
                if (driver.active) {
                    OutlinedButton(onClick = { onTone(44100, 16) }) { Text("Tone 44.1/16") }
                    OutlinedButton(onClick = { onTone(96000, 32) }) { Text("96/32") }
                }
            }
            if (probe.isNotEmpty()) {
                SelectionContainer(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(probe, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ---- Plugin
        Section("Plugin capture · $pluginName") {
            Text(pluginFormat, style = MaterialTheme.typography.bodySmall)
            decoderLabel?.let { Text("Decoder: $it", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { peak }, modifier = Modifier.fillMaxWidth())
        }

        Text(
            "All-Meyn Player ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
