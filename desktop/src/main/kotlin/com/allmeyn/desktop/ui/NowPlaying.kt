package com.allmeyn.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allmeyn.desktop.engine.NativeEngine
import com.allmeyn.desktop.engine.PlayState
import com.allmeyn.desktop.engine.TrackInfo
import kotlinx.coroutines.delay

/** Stage W2: open one song and play it; the library and queue come in W3. */
@Composable
fun NowPlayingCard(player: PlayerController, onOpen: () -> Unit) {
    LaunchedEffect(player) {
        while (true) {
            player.refresh()
            delay(100)
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Now playing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onOpen, enabled = NativeEngine.isReady && !player.opening) {
                    Text(if (player.opening) "Opening…" else "Open a song…")
                }
            }
            val track = player.track
            if (track == null) {
                Text(
                    if (NativeEngine.isReady) "Pick a music file to play it. (Your library comes in the next stage.)"
                    else "Playback needs the audio engine (see below).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TrackDetails(player, track)
            }
            player.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TrackDetails(player: PlayerController, track: TrackInfo) {
    Text(track.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (track.subtitle.isNotEmpty()) {
        Text(track.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text("${track.formatLabel}  →  ${track.output}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

    // While dragging, the bar follows the finger; the seek happens on release.
    var dragMs by remember(track) { mutableStateOf<Float?>(null) }
    val duration = track.durationMs.coerceAtLeast(1)
    val shown = dragMs ?: player.positionMs.toFloat()
    Slider(
        value = shown.coerceIn(0f, duration.toFloat()),
        onValueChange = { dragMs = it },
        onValueChangeFinished = {
            dragMs?.let { player.seekTo(it.toLong()) }
            dragMs = null
        },
        valueRange = 0f..duration.toFloat(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(TrackInfo.formatTime(shown.toLong()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        FilledIconButton(onClick = player::togglePlay, modifier = Modifier.size(48.dp)) {
            PlayPauseIcon(playing = player.state == PlayState.PLAYING, color = MaterialTheme.colorScheme.onPrimary)
        }
        Text(
            TrackInfo.formatTime(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** ▶ / ❚❚ drawn directly (no icon font needed). */
@Composable
private fun PlayPauseIcon(playing: Boolean, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        if (playing) {
            val bar = w * 0.3f
            drawRect(color, Offset(w * 0.12f, 0f), Size(bar, h))
            drawRect(color, Offset(w * 0.58f, 0f), Size(bar, h))
        } else {
            drawPath(Path().apply { moveTo(w * 0.18f, 0f); lineTo(w, h / 2); lineTo(w * 0.18f, h); close() }, color)
        }
    }
}
