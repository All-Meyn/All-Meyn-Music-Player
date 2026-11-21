package com.allmeyn.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.wexpa.liquidglass.material3.Button
import com.wexpa.liquidglass.material3.DropdownMenu
import com.wexpa.liquidglass.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.wexpa.liquidglass.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allmeyn.player.library.Album
import com.allmeyn.player.library.Artist
import com.allmeyn.player.library.FolderNode
import com.allmeyn.player.library.LibraryIndex
import com.allmeyn.player.library.Track

@Composable
private fun listPadding() = belowBars(bottom = 8.dp)

@Composable
fun EmptyLibrary(scanning: Boolean, progress: String, onAddFolder: () -> Unit) {
    if (scanning) {
        EmptyState(PlayerIcons.Note, "Scanning your music…", progress)
    } else {
        EmptyState(
            PlayerIcons.Note, "Add your music folder",
            "Pick the folder that holds your music (e.g. Music). All-Meyn scans it, including DSF, DFF, APE and WavPack.",
            action = "Choose folder", onAction = onAddFolder,
        )
    }
}

/**
 * The header of an album, artist or playlist: cover, title, a subtitle and a metadata line.
 * The cover grows on wide screens and shrinks on sideways phones.
 */
@Composable
fun DetailHeader(
    art: String,
    title: String,
    subtitle: String,
    meta: String,
    round: Boolean = false,
    sidePadding: Dp = 16.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    val window = LocalWindowSize.current
    val size = when {
        window.short -> 112.dp
        window.wide -> 200.dp
        else -> 140.dp
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = sidePadding, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(art, Modifier.size(size), corner = if (round) size / 2 else 8.dp)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing?.invoke()
    }
}

/** Play and Shuffle for a list of songs, the same on every screen; [extra] adds buttons after them. */
@Composable
fun PlayShuffleRow(tracks: List<Track>, player: PlayerUi, sidePadding: Dp = 16.dp, extra: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.padding(start = sidePadding, end = sidePadding, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { player.playTracks(tracks, 0) }, enabled = tracks.isNotEmpty()) {
            Icon(PlayerIcons.Play, null); Text("Play", Modifier.padding(start = 4.dp))
        }
        OutlinedButton(onClick = { player.shuffleTracks(tracks) }, enabled = tracks.isNotEmpty()) {
            Icon(PlayerIcons.Shuffle, null); Text("Shuffle", Modifier.padding(start = 4.dp))
        }
        extra()
    }
}

/** Album covers in a grid that adds columns as the screen widens; [header] scrolls with it. */
@Composable
fun AlbumsGrid(albums: List<Album>, header: (@Composable () -> Unit)? = null, onOpen: (Album) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = belowBars(12.dp, 12.dp, 12.dp, 12.dp, fullWidth = true),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        items(albums, key = { it.key }) { album ->
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpen(album) }) {
                Artwork(album.art, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun AlbumDetail(album: Album, playingUri: String, player: PlayerUi) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            DetailHeader(
                album.art, album.title, album.artist,
                listOfNotNull(
                    album.year.takeIf { it > 0 }?.toString(),
                    countOf(album.tracks.size, "song"),
                    formatTime(album.durationMs),
                    album.tracks.firstOrNull()?.qualityLabel,
                ).joinToString(" · "),
            )
            val addToPlaylist = LocalAddToPlaylist.current
            PlayShuffleRow(album.tracks, player) {
                IconButton(onClick = { addToPlaylist(album.tracks) }) { Icon(PlayerIcons.PlaylistAdd, "Add album to playlist") }
            }
        }
        itemsIndexed(album.tracks, key = { _, t -> t.uri }) { i, t ->
            TrackRow(
                t, playing = t.uri == playingUri,
                leading = { Text(if (t.trackNo > 0) "${t.trackNo}" else "${i + 1}", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall) },
                subtitle = if (t.artist != album.artist && t.artist.isNotBlank()) t.artist else t.qualityLabel,
                player = player,
            ) { player.playTracks(album.tracks, i) }
        }
    }
}

@Composable
fun ArtistsList(artists: List<Artist>, onOpen: (Artist) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        items(artists, key = { it.name.lowercase() }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(a.albums.firstOrNull { it.art.isNotEmpty() }?.art.orEmpty(), Modifier.size(52.dp), corner = 26.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${countOf(a.albums.size, "album")} · ${countOf(a.trackCount, "song")}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun ArtistDetail(artist: Artist, onOpen: (Album) -> Unit, player: PlayerUi) {
    val all = remember(artist) { artist.albums.flatMap { it.tracks } }
    AlbumsGrid(
        artist.albums,
        header = {
            // The grid already pads its sides: the header lines up with the covers.
            Column {
                DetailHeader(
                    artist.albums.firstOrNull { it.art.isNotEmpty() }?.art.orEmpty(), artist.name, "",
                    "${countOf(artist.albums.size, "album")} · ${countOf(artist.trackCount, "song")}",
                    round = true, sidePadding = 0.dp,
                )
                PlayShuffleRow(all, player, sidePadding = 0.dp)
            }
        },
        onOpen = onOpen,
    )
}

@Composable
fun TracksList(tracks: List<Track>, playingUri: String, player: PlayerUi) {
    val sorted = LibraryIndex.tracksByTitle(tracks)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        itemsIndexed(sorted, key = { _, t -> t.uri }) { i, t ->
            TrackRow(
                t, playing = t.uri == playingUri,
                leading = { Artwork(t.art, Modifier.size(44.dp), corner = 4.dp) },
                subtitle = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                player = player,
            ) { player.playTracks(sorted, i) }
        }
    }
}

@Composable
fun FolderBrowser(roots: List<FolderNode>, path: String?, onOpen: (String) -> Unit, playingUri: String, player: PlayerUi) {
    val node = path?.let { findFolder(roots, it) }
    val folders = node?.children ?: roots
    val tracks = node?.tracks.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
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
                player = player,
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

/** A song in a list. Tap plays; long-press offers "Play next" and "Add to queue". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    playing: Boolean,
    leading: @Composable () -> Unit,
    subtitle: String,
    player: PlayerUi,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val addToPlaylist = LocalAddToPlaylist.current
    val color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; player.playNext(track) })
            DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; player.addToQueue(track) })
            DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { menu = false; addToPlaylist(listOf(track)) })
        }
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
        trailing?.invoke()
    }
}
