package com.allmeyn.desktop.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.wexpa.liquidglass.material3.AlertDialog
import com.wexpa.liquidglass.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.wexpa.liquidglass.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.allmeyn.desktop.engine.TrackInfo
import com.allmeyn.desktop.library.DesktopPlaylists
import com.allmeyn.desktop.library.LibraryView
import com.allmeyn.player.library.Playlist
import com.allmeyn.player.library.PlaylistOps
import com.allmeyn.player.library.Track

/** "Add to playlist…" from anywhere (song menus, Save queue): opens the picker for these songs. */
val LocalAddToPlaylist = staticCompositionLocalOf<(List<Track>) -> Unit> { {} }

@Composable
fun PlaylistsScreen(playlists: DesktopPlaylists, view: LibraryView, onOpen: (Playlist) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = CONTENT_PADDING) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenTitle("Playlists") }
                Button(onClick = { naming = true }) { Text("New playlist") }
            }
            if (playlists.all.isEmpty()) {
                Text(
                    "No playlists yet. Make one here, or right-click any song → Add to playlist.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(playlists.all, key = { _, p -> p.id }) { i, p ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val (songs, missing) = PlaylistOps.resolve(p, view.byUri)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Cover(songs.firstOrNull { it.art.isNotEmpty() }?.art.orEmpty(), 44.dp)
                Column {
                    Text(p.name, fontWeight = FontWeight.Medium)
                    Text(songCount(songs.size, missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (naming) NameDialog("New playlist", "", "Create", onDismiss = { naming = false }) { name ->
        naming = false
        playlists.create(name)
    }
}

@Composable
fun PlaylistPage(playlist: Playlist, playlists: DesktopPlaylists, view: LibraryView, player: PlayerController, onBack: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val addTo = LocalAddToPlaylist.current
    // Rows by position in the playlist (songs missing from the library are skipped, not removed).
    val rows = playlist.uris.mapIndexedNotNull { i, uri -> view.byUri[uri]?.let { i to it } }
    val songs = rows.map { it.second }
    val missing = playlist.uris.size - rows.size
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(rows, listState) { (at, t) -> "p$at:${t.uri}" }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = CONTENT_PADDING) {
        item {
            ScreenTitle(playlist.name, onBack)
            Text(
                songCount(songs.size, missing) + if (songs.isNotEmpty()) " · " + TrackInfo.formatTime(songs.sumOf { it.durationMs }) else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                Button(onClick = { player.play(songs) }, enabled = songs.isNotEmpty()) { Text("Play") }
                OutlinedButton(onClick = { player.play(songs, shuffle = true) }, enabled = songs.isNotEmpty()) { Text("Shuffle") }
                TextButton(onClick = { renaming = true }) { Text("Rename") }
                TextButton(onClick = { deleting = true }) { Text("Delete") }
            }
            if (playlist.uris.isEmpty()) Text("Empty. Right-click any song → Add to playlist.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(reorder.rows, key = { (at, t) -> "p$at:${t.uri}" }) { row ->
            val (at, track) = row
            val k = rows.indexOf(row)
            Box(reorderRow(reorder, row)) { TrackRow(
                track, number = null, showAlbum = true, current = player.current?.uri == track.uri,
                onClick = { player.play(songs, k) },
                menu = {
                    listOf(
                        ContextMenuItem("Play next") { player.playNext(track) },
                        ContextMenuItem("Add to queue") { player.addToQueue(track) },
                        ContextMenuItem("Add to playlist…") { addTo(listOf(track)) },
                        ContextMenuItem("Remove from playlist") { playlists.removeAt(playlist.id, at) },
                    )
                },
                trailing = {
                    TextButton(onClick = { playlists.removeAt(playlist.id, at) }) { Text("Remove") }
                    // Drag among the shown songs; the move lands at the target's place in the saved list
                    // (songs missing from the library keep theirs).
                    DragHandle(reorder, row) { from, to -> playlists.move(playlist.id, rows[from].first, rows[to].first) }
                },
            ) }
        }
    }
    if (renaming) NameDialog("Rename playlist", playlist.name, "Rename", onDismiss = { renaming = false }) { name ->
        renaming = false
        playlists.rename(playlist.id, name)
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete “${playlist.name}”?") },
        text = { Text("The playlist goes; the songs stay in your library.") },
        confirmButton = { TextButton(onClick = { deleting = false; playlists.delete(playlist.id); onBack() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
}

/** Picks a playlist for [tracks], or makes a new one with them. */
@Composable
fun AddToPlaylistDialog(tracks: List<Track>, playlists: DesktopPlaylists, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    val what = if (tracks.size == 1) "“${tracks[0].title}”" else "${tracks.size} songs"
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Add $what to a playlist") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("New playlist") }, modifier = Modifier.weight(1f))
                    // (Plain buttons inside the glass dialog: it is itself the glass.)
                    TextButton(onClick = {
                        playlists.create(name, tracks.map { it.uri })
                        onDone(name.trim().ifEmpty { "Playlist" })
                    }) { Text("Create") }
                }
                Spacer(Modifier.height(8.dp))
                playlists.all.forEach { p ->
                    Text(
                        p.name,
                        Modifier.fillMaxWidth().clickable { playlists.add(p.id, tracks.map { it.uri }); onDone(p.name) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun songCount(n: Int, missing: Int) =
    "$n song" + (if (n == 1) "" else "s") + if (missing > 0) " · $missing not in your library" else ""
