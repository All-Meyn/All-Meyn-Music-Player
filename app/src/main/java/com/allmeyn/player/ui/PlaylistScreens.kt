package com.allmeyn.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allmeyn.player.library.Playlist
import com.allmeyn.player.library.PlaylistOps
import com.allmeyn.player.library.Track

/** Opens the "Add to playlist" dialog for some songs. Provided by AppRoot; used by song menus. */
val LocalAddToPlaylist = staticCompositionLocalOf<(List<Track>) -> Unit> { {} }

@Composable
fun PlaylistsList(playlists: List<Playlist>, byUri: Map<String, Track>, onOpen: (Playlist) -> Unit, onCreate: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        item {
            Button(onClick = { naming = true }, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.Add, null); Text("New playlist", Modifier.padding(start = 4.dp))
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "No playlists yet. Long-press a song and pick \"Add to playlist\", or save the queue as one.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(playlists, key = { it.id }) { p ->
            val art = p.uris.firstNotNullOfOrNull { byUri[it]?.art?.takeIf { a -> a.isNotEmpty() } }.orEmpty()
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(art, Modifier.size(52.dp), corner = 6.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(countOf(p.uris.size, "song"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (naming) NameDialog("New playlist", "", onDismiss = { naming = false }) { naming = false; onCreate(it) }
}

@Composable
fun PlaylistDetail(
    playlist: Playlist,
    byUri: Map<String, Track>,
    playingUri: String,
    player: PlayerUi,
    onRemove: (index: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    // (index in the playlist, track) for the songs found in the library
    val rows = remember(playlist, byUri) { playlist.uris.withIndex().mapNotNull { (i, u) -> byUri[u]?.let { i to it } } }
    val tracks = rows.map { it.second }
    val missing = PlaylistOps.resolve(playlist, byUri).second
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Drag a song by its handle: positions map back to indices in the playlist.
    val reorder = rememberReorderState(rows, listState) { r -> "${r.first}:${r.second.uri}" }
    val moveInPlaylist: (Int, Int) -> Unit = { from, to -> onMove(rows[from].first, rows[to].first) }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
        item {
            Row(Modifier.padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val dur = tracks.sumOf { it.durationMs }
                    Text(
                        listOfNotNull(countOf(tracks.size, "song"), formatTime(dur).takeIf { dur > 0 },
                            if (missing > 0) "$missing not in library" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Playlist options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { player.playTracks(tracks, 0) }, enabled = tracks.isNotEmpty()) {
                    Icon(PlayerIcons.Play, null); Text("Play", Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = { player.shuffleTracks(tracks) }, enabled = tracks.isNotEmpty()) {
                    Icon(PlayerIcons.Shuffle, null); Text("Shuffle", Modifier.padding(start = 4.dp))
                }
            }
            if (tracks.isEmpty()) {
                Text("Empty. Long-press any song and pick \"Add to playlist\".", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
        itemsIndexed(reorder.rows, key = { _, r -> "${r.first}:${r.second.uri}" }) { pos, row ->
            val (index, t) = row
            Box(
                reorderRow(reorder, row).background(
                    if (reorder.isDragging(row)) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                ),
            ) {
                TrackRow(
                    t, playing = t.uri == playingUri,
                    leading = { Artwork(t.art, Modifier.size(44.dp), corner = 4.dp) },
                    subtitle = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                    player = player,
                    trailing = {
                        if (rows.size > 1) DragHandle(reorder, row, moveInPlaylist)
                        IconButton(onClick = { onRemove(index) }) { Icon(Icons.Filled.Close, "Remove from playlist") }
                    },
                ) { player.playTracks(tracks, pos) }
            }
        }
    }
    if (renaming) NameDialog("Rename playlist", playlist.name, onDismiss = { renaming = false }) { renaming = false; onRename(it) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("The songs stay in your library.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Pick a playlist for [tracks], or make a new one with them. */
@Composable
fun AddToPlaylistDialog(
    tracks: List<Track>,
    playlists: List<Playlist>,
    onAdd: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog("New playlist", "", onDismiss = onDismiss) { onCreate(it) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tracks.size == 1) "Add \"${tracks[0].title}\" to" else "Add ${countOf(tracks.size, "song")} to") },
        text = {
            LazyColumn {
                item {
                    Row(Modifier.fillMaxWidth().clickable { naming = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                        Text("New playlist…", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { onAdd(p) }.padding(vertical = 12.dp)) {
                        Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(countOf(p.uris.size, "song"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("Name") }, modifier = Modifier.focusRequester(focus),
                )
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
