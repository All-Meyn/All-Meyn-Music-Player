package com.allmeyn.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.allmeyn.player.usb.UsbAudioDriver
import kotlin.math.roundToInt

/** Full-screen player: cover, seek bar, transport, what's playing and where it's going. */
@Composable
fun NowPlaying(
    player: PlayerUi,
    sourceLabel: String,
    peak: Float,
    driver: UsbAudioDriver.State,
    driverRoute: String,
    onDacVolume: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(player.artPath, Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f), corner = 16.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            player.title.ifBlank { "Nothing playing" }, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(player.artist, player.album).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2,
        )

        Spacer(Modifier.height(16.dp))
        // Seek: preview while dragging, seek on release.
        var dragging by remember { mutableStateOf<Float?>(null) }
        val duration = player.durationMs.coerceAtLeast(1)
        Slider(
            value = dragging ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = player.hasMedia && player.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            val shown = dragging?.let { (it * duration).toLong() } ?: player.positionMs
            Text(formatTime(shown), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(formatTime(player.durationMs), style = MaterialTheme.typography.bodySmall)
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val on = MaterialTheme.colorScheme.primary
            val off = MaterialTheme.colorScheme.onSurfaceVariant
            IconButton(onClick = { player.toggleShuffle() }) {
                Icon(PlayerIcons.Shuffle, "Shuffle", tint = if (player.shuffle) on else off)
            }
            IconButton(onClick = { player.previous() }) { Icon(PlayerIcons.Previous, "Previous", Modifier.size(36.dp)) }
            FilledIconButton(onClick = { player.togglePlay() }, modifier = Modifier.size(72.dp)) {
                Icon(if (player.isPlaying) PlayerIcons.Pause else PlayerIcons.Play, "Play/pause", Modifier.size(40.dp))
            }
            IconButton(onClick = { player.next() }) { Icon(PlayerIcons.Next, "Next", Modifier.size(36.dp)) }
            IconButton(onClick = { player.cycleRepeat() }) {
                Icon(
                    if (player.repeatMode == Player.REPEAT_MODE_ONE) PlayerIcons.RepeatOne else PlayerIcons.Repeat, "Repeat",
                    tint = if (player.repeatMode == Player.REPEAT_MODE_OFF) off else on,
                )
            }
        }

        // Where the sound comes from and where it goes.
        Spacer(Modifier.height(8.dp))
        InfoLine("Source", sourceLabel)
        InfoLine(
            "Output",
            if (driver.active) "${driver.deviceName ?: "USB DAC"} · All-Meyn USB driver · bit-perfect · $driverRoute"
            else "Android audio",
        )
        if (!sourceLabel.contains("DoP")) {
            LinearProgressIndicator(progress = { peak }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }

        driver.volume?.let { v ->
            Spacer(Modifier.height(12.dp))
            var vol by remember { mutableStateOf<Float?>(null) }
            Text(
                if (v.muted || v.step == 0) "DAC volume · muted" else "DAC volume · %.1f dB".format(v.db),
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = vol ?: v.step.toFloat(),
                onValueChange = { vol = it },
                onValueChangeFinished = { vol?.let { onDacVolume(it.roundToInt()) }; vol = null },
                valueRange = 0f..v.maxStep.toFloat(),
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 64.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
