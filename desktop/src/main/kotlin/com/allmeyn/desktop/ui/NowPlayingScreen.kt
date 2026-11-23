package com.allmeyn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allmeyn.desktop.engine.ReplayGainMode

/**
 * The playing song in full (as Android's Now Playing): big cover, title, artist · album, and where
 * the sound comes from and goes. Play, seek and skip stay in the player bar below.
 * Opened by clicking the song in the player bar; clicking it again or Back returns.
 */
@Composable
fun NowPlayingScreen(player: PlayerController, replayGain: ReplayGainMode, onBack: () -> Unit) {
    val track = player.current
    Column(Modifier.fillMaxSize().padding(28.dp)) {
        ScreenTitle("Now playing", onBack = onBack)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val art = minOf(maxHeight - 16.dp, maxWidth * 0.48f).coerceAtLeast(120.dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Cover(track?.art.orEmpty(), art)
                Column(Modifier.weight(1f).widthIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        track?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    if (track != null) {
                        Text(
                            listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        )
                        if (track.year > 0) Text("${track.year}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(24.dp))
                    player.playing?.let { p ->
                        InfoLine("Source", p.formatLabel)
                        InfoLine("Output", p.output)
                        p.replayGainDb?.let { InfoLine("Gain", "ReplayGain " + ReplayGainMode.label(it, replayGain)) }
                    }
                    player.error?.let { InfoLine("Error", it) }
                    if (player.queue.size > 1) {
                        val next = player.queue.getOrNull(player.index + 1)
                        InfoLine("Queue", "${player.index + 1} of ${player.queue.size}" + (next?.let { " · next: ${it.title}" } ?: ""))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 72.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
