package com.allmeyn.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allmeyn.player.library.Album
import com.allmeyn.player.library.Artist
import com.allmeyn.player.library.FolderNode
import com.allmeyn.player.library.LibraryIndex
import com.allmeyn.player.library.Track

private val listPadding = PaddingValues(bottom = 8.dp)

@Composable
fun EmptyLibrary(scanning: Boolean, progress: String, onAddFolder: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(PlayerIcons.Note, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        if (scanning) {
            Text("Scanning your music…", style = MaterialTheme.typography.titleMedium)
            Text(progress, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Add your music folder", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick the folder that holds your music (e.g. Music). All-Meyn scans it, including DSF, DFF, APE and WavPack.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Button(onClick = onAddFolder) { Text("Choose folder") }
        }
    }
}

@Composable
fun AlbumsGrid(albums: List<Album>, onOpen: (Album) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.key }) { album ->
            Column(Modifier.clickable { onOpen(album) }) {
                Artwork(album.art, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun AlbumDetail(album: Album, playingUri: String, player: PlayerUi) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(album.art, Modifier.size(140.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(album.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(album.artist, style = MaterialTheme.typography.titleSmall)
                    val meta = listOfNotNull(
                        album.year.takeIf { it > 0 }?.toString(),
                        countOf(album.tracks.size, "track"),
                        formatTime(album.durationMs),
                        album.tracks.first().qualityLabel,
                    )
                    Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { player.playTracks(album.tracks, 0) }) {
                    Icon(PlayerIcons.Play, null); Text("Play", Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = { player.shuffleTracks(album.tracks) }) {
                    Icon(PlayerIcons.Shuffle, null); Text("Shuffle", Modifier.padding(start = 4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        itemsIndexed(album.tracks, key = { _, t -> t.uri }) { i, t ->
            TrackRow(
                t, playing = t.uri == playingUri,
                leading = { Text(if (t.trackNo > 0) "${t.trackNo}" else "${i + 1}", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall) },
                subtitle = if (t.artist != album.artist && t.artist.isNotBlank()) t.artist else t.qualityLabel,
            ) { player.playTracks(album.tracks, i) }
        }
    }
}

@Composable
fun ArtistsList(artists: List<Artist>, onOpen: (Artist) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        items(artists, key = { it.name.lowercase() }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(a.albums.firstOrNull { it.art.isNotEmpty() }?.art.orEmpty(), Modifier.size(52.dp), corner = 26.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${countOf(a.albums.size, "album")} · ${countOf(a.trackCount, "track")}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun ArtistDetail(artist: Artist, onOpen: (Album) -> Unit, player: PlayerUi) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(artist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { player.shuffleTracks(artist.albums.flatMap { it.tracks }) }) {
                Icon(PlayerIcons.Shuffle, null); Text("Shuffle", Modifier.padding(start = 4.dp))
            }
        }
        AlbumsGrid(artist.albums, onOpen)
    }
}

@Composable
fun TracksList(tracks: List<Track>, playingUri: String, player: PlayerUi) {
    val sorted = LibraryIndex.tracksByTitle(tracks)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        itemsIndexed(sorted, key = { _, t -> t.uri }) { i, t ->
            TrackRow(
                t, playing = t.uri == playingUri,
                leading = { Artwork(t.art, Modifier.size(44.dp), corner = 4.dp) },
                subtitle = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
            ) { player.playTracks(sorted, i) }
        }
    }
}

@Composable
fun FolderBrowser(roots: List<FolderNode>, path: String?, onOpen: (String) -> Unit, playingUri: String, player: PlayerUi) {
    val node = path?.let { findFolder(roots, it) }
    val folders = node?.children ?: roots
    val tracks = node?.tracks.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        if (node != null) {
            item {
                Text(node.path, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 12.dp))
            }
        }
        items(folders, key = { "d:" + it.path }) { f ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(f.path) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PlayerIcons.Folder, null, tint = MaterialTheme.colorScheme.primary)
                Text(f.name, Modifier.padding(start = 16.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${countTracks(f)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (folders.isNotEmpty() && tracks.isNotEmpty()) item { HorizontalDivider() }
        itemsIndexed(tracks, key = { _, t -> "t:" + t.uri }) { i, t ->
            TrackRow(
                t, playing = t.uri == playingUri,
                leading = { Icon(PlayerIcons.Note, null, Modifier.width(32.dp)) },
                subtitle = t.fileName + " · " + t.qualityLabel,
            ) { player.playTracks(tracks, i) }
        }
    }
}

private fun findFolder(nodes: List<FolderNode>, path: String): FolderNode? {
    for (n in nodes) {
        if (n.path == path) return n
        if (path.startsWith(n.path + "/")) return findFolder(n.children, path)
    }
    return null
}

private fun countTracks(n: FolderNode): Int = n.tracks.size + n.children.sumOf { countTracks(it) }

@Composable
fun TrackRow(track: Track, playing: Boolean, leading: @Composable () -> Unit, subtitle: String, onClick: () -> Unit) {
    val color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 12.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f)) {
            Text(track.title, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (track.durationMs > 0) {
            Text(formatTime(track.durationMs), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
