package com.allmeyn.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The queue in play order. Tap a song to jump to it, X to remove it. With shuffle off the arrows
 * move a song up or down; with shuffle on the play order is the shuffle, so moving is hidden.
 */
@Composable
fun QueueScreen(player: PlayerUi) {
    val entries = remember(player.queueVersion) { player.queue() }
    val current = remember(player.queueVersion) { player.currentIndex }
    val currentPos = entries.indexOfFirst { it.index == current }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (currentPos > 0) listState.scrollToItem(currentPos) }

    if (entries.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("The queue is empty", style = MaterialTheme.typography.titleMedium)
            Text("Long-press a song and pick \"Add to queue\".", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val upcoming = if (currentPos >= 0) entries.size - currentPos - 1 else entries.size
            Text(
                countOf(upcoming, "song") + " up next" + if (player.shuffle) " · shuffled" else "",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
            )
            if (entries.size > 1) TextButton(onClick = { player.clearUpcoming() }) { Text("Clear others") }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(entries, key = { _, e -> e.index.toString() + e.uri }) { pos, e ->
                val isCurrent = e.index == current
                val played = currentPos >= 0 && pos < currentPos
                val color = when {
                    isCurrent -> MaterialTheme.colorScheme.primary
                    played -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(
                    Modifier.fillMaxWidth().clickable { player.jumpTo(e.index) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(e.artPath, Modifier.size(44.dp), corner = 4.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            e.title.ifBlank { e.uri.substringAfterLast("%2F").substringAfterLast('/') }, color = color,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            if (isCurrent) "Now playing" else e.artist,
                            style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!player.shuffle) {
                        IconButton(onClick = { player.move(e.index, e.index - 1) }, enabled = e.index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                        }
                        IconButton(onClick = { player.move(e.index, e.index + 1) }, enabled = e.index < entries.size - 1) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                        }
                    }
                    if (!isCurrent) {
                        IconButton(onClick = { player.removeAt(e.index) }) { Icon(Icons.Filled.Close, "Remove") }
                    } else {
                        // Keep rows aligned: the current song can't be removed from here.
                        IconButton(onClick = {}, enabled = false) {}
                    }
                }
            }
        }
    }
}
