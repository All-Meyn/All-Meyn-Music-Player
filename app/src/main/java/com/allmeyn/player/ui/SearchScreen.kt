package com.allmeyn.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allmeyn.player.library.Album
import com.allmeyn.player.library.Artist
import com.allmeyn.player.library.LibraryIndex
import com.allmeyn.player.library.MusicLibrary

private const val MAX_TRACKS = 100

/** Search songs, albums and artists. Every word typed must match (title, artist, album, year). */
@Composable
fun SearchScreen(
    library: MusicLibrary.State,
    playingUri: String,
    player: PlayerUi,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val index = remember(library.tracks, library.albums, library.artists) {
        LibraryIndex.SearchIndex(library.artists, library.albums, library.tracks)
    }
    val results = remember(query, index) { index.find(query) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, albums, artists") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
        )
        if (query.isNotBlank() && results.isEmpty) {
            Text("Nothing found", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        val tracks = results.tracks.take(MAX_TRACKS)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (results.artists.isNotEmpty()) item { Heading("Artists") }
            items(results.artists, key = { "a:" + it.name.lowercase() }) { a ->
                ResultRow(
                    art = a.albums.firstOrNull { it.art.isNotEmpty() }?.art.orEmpty(), round = true,
                    title = a.name, subtitle = "${countOf(a.albums.size, "album")} · ${countOf(a.trackCount, "track")}",
                ) { keyboard?.hide(); onOpenArtist(a) }
            }
            if (results.albums.isNotEmpty()) item { Heading("Albums") }
            items(results.albums, key = { "l:" + it.key }) { al ->
                ResultRow(
                    art = al.art, round = false, title = al.title,
                    subtitle = listOfNotNull(al.artist, al.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
                ) { keyboard?.hide(); onOpenAlbum(al) }
            }
            if (tracks.isNotEmpty()) {
                item {
                    Heading(if (results.tracks.size > MAX_TRACKS) "Songs (first $MAX_TRACKS of ${results.tracks.size})" else "Songs")
                }
            }
            itemsIndexed(tracks, key = { _, t -> "t:" + t.uri }) { i, t ->
                TrackRow(
                    t, playing = t.uri == playingUri,
                    leading = { Artwork(t.art, Modifier.size(44.dp), corner = 4.dp) },
                    subtitle = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                    player = player,
                ) { keyboard?.hide(); player.playTracks(tracks, i) }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ResultRow(art: String, round: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, Modifier.size(44.dp), corner = if (round) 22.dp else 4.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
