package com.allmeyn.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allmeyn.desktop.engine.DacVolume
import com.allmeyn.desktop.engine.NativeEngine
import com.allmeyn.desktop.engine.OutputSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The output's volume: a slider for the WASAPI device (the DAC's own hardware volume when it has
 * one: the samples stay untouched), or, for ASIO (no standard volume), the driver's control panel.
 * Follows changes made elsewhere (Windows' volume, the DAC's knob) by checking twice a second.
 */
@Composable
fun VolumeControl(output: OutputSettings, sliderWidth: Dp) {
    val scope = rememberCoroutineScope()
    if (output.isAsio) {
        var error by remember(output.deviceId) { mutableStateOf<String?>(null) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1) }
            TextButton(onClick = {
                val name = output.deviceId.removePrefix(OutputSettings.ASIO_PREFIX)
                scope.launch { error = withContext(Dispatchers.IO) { NativeEngine.asioControlPanel(name) } }
            }) { Text("ASIO control panel") }
        }
        return
    }
    var volume by remember(output.deviceId) { mutableStateOf<DacVolume?>(null) }
    var dragging by remember(output.deviceId) { mutableStateOf<Float?>(null) }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(output.deviceId) {
        while (true) {
            if (dragging == null) volume = withContext(Dispatchers.IO) { DacVolume.read(output) }
            delay(500)
        }
    }
    val v = volume ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (v.muted) "Muted" else "Vol",
            style = MaterialTheme.typography.labelSmall,
            color = if (v.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { scope.launch { withContext(Dispatchers.IO) { DacVolume.setMuted(output, !v.muted) }; volume = v.copy(muted = !v.muted) } },
        )
        Slider(
            value = dragging ?: v.level,
            onValueChange = { level ->
                dragging = level
                // One change in flight at a time (dragging sends many); the last one lands on release.
                if (!sending) {
                    sending = true
                    scope.launch { withContext(Dispatchers.IO) { DacVolume.set(output, level) }; sending = false }
                }
            },
            onValueChangeFinished = {
                val level = dragging
                dragging = null
                if (level != null) scope.launch {
                    volume = withContext(Dispatchers.IO) { DacVolume.set(output, level); DacVolume.read(output) }
                }
            },
            modifier = Modifier.width(sliderWidth).height(24.dp),
        )
        Text(
            if (dragging != null) "" else v.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}
