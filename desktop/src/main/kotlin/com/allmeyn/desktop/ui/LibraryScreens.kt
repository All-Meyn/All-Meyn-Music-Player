package com.allmeyn.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allmeyn.desktop.engine.TrackInfo
import com.allmeyn.desktop.library.DesktopLibrary
import com.allmeyn.player.library.Album
import com.allmeyn.player.library.Artist
import com.allmeyn.player.library.FolderNode
import com.allmeyn.player.library.LibraryIndex
import com.allmeyn.player.library.Track

/** Where the main area is. Library sections are roots; detail screens are pushed on top. */
sealed interface Route {
    data object Albums : Route
    data class AlbumPage(val key: String) : Route
    data object Artists : Route
    data class ArtistPage(val name: String) : Route
    data object Songs : Route
    data class Folders(val path: String = "") : Route
    data object Queue : Route
    data object Dsp : Route
    data object NowPlaying : Route
    data object Settings : Route
}

private val CONTENT_PADDING = PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 24.dp)

@Composable
fun ScreenTitle(text: String, onBack: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

// ---- empty states --------------------------------------------------------------------------------------

/** No music yet: what to do about it. */
@Composable
fun EmptyLibrary(library: DesktopLibrary, onChooseFolder: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(BrandMark.vector, contentDescription = null, tint = BrandCopper, modifier = Modifier.size(width = 114.dp, height = 60.dp))
            Text("Your library. Your hardware. All yours.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val progress = library.scanProgress
            when {
                progress != null -> Text("Reading your music… ${progress.first} / ${progress.second}")
                library.folders.isEmpty() -> {
                    Text("Choose the folder your music is in to get started.")
                    Button(onClick = onChooseFolder) { Text("Choose music folder…") }
                }
                else -> {
                    Text("No music found in your folders.")
                    OutlinedButton(onClick = onChooseFolder) { Text("Add another folder…") }
                }
            }
        }
    }
}

// ---- albums ------------------------------------------------------------------------------------------------

@Composable
fun AlbumGrid(albums: List<Album>, title: String, onOpen: (Album) -> Unit, onBack: (() -> Unit)? = null) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(176.dp),
        contentPadding = CONTENT_PADDING,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { ScreenTitle(title, onBack) }
        items(albums, key = { it.key }) { album ->
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onOpen(album) }.padding(4.dp)) {
                Cover(album.art, 168.dp)
                Spacer(Modifier.height(8.dp))
                Text(album.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(album.artist, album.year.takeIf { it > 0 }?.toString()).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun AlbumPage(album: Album, player: PlayerController, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = CONTENT_PADDING) {
        item {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                Cover(album.art, 220.dp)
                Column(Modifier.align(Alignment.Bottom), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(album.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(album.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        listOfNotNull(
                            album.year.takeIf { it > 0 }?.toString(),
                            "${album.tracks.size} song" + if (album.tracks.size == 1) "" else "s",
                            TrackInfo.formatTime(album.durationMs),
                            album.tracks.first().qualityLabel,
                        ).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PlayShuffle(album.tracks, player)
                }
            }
        }
        trackList(album.tracks, player, showAlbum = false, numbered = true)
    }
}

@Composable
fun PlayShuffle(tracks: List<Track>, player: PlayerController) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = { player.play(tracks) }) { Text("Play") }
        OutlinedButton(onClick = { player.play(tracks, shuffle = true) }) { Text("Shuffle") }
    }
}

// ---- artists -----------------------------------------------------------------------------------------------

@Composable
fun ArtistList(artists: List<Artist>, onOpen: (Artist) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = CONTENT_PADDING) {
        item { ScreenTitle("Artists") }
        itemsIndexed(artists, key = { _, a -> a.name }) { i, artist ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(artist) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Cover(artist.albums.firstOrNull { it.art.isNotEmpty() }?.art.orEmpty(), 44.dp)
                Column {
                    Text(artist.name, fontWeight = FontWeight.Medium)
                    Text(
                        "${artist.albums.size} album" + (if (artist.albums.size == 1) "" else "s") + " · ${artist.trackCount} songs",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---- songs / folders / queue ------------------------------------------------------------------------------

@Composable
fun SongList(songs: List<Track>, player: PlayerController) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = CONTENT_PADDING) {
        item {
            ScreenTitle("Songs")
            PlayShuffle(songs, player)
            Spacer(Modifier.height(12.dp))
        }
        trackList(songs, player, showAlbum = true, numbered = false)
    }
}

@Composable
fun FolderBrowser(roots: List<FolderNode>, path: String, player: PlayerController, onOpen: (String) -> Unit, onBack: (() -> Unit)?) {
    val node = findFolder(roots, path)
    val children = node?.children ?: roots
    val tracks = node?.tracks.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = CONTENT_PADDING) {
        item {
            ScreenTitle(node?.name ?: "Folders", onBack)
            if (node != null) Text(node.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tracks.isNotEmpty()) PlayShuffle(tracks, player)
            Spacer(Modifier.height(8.dp))
        }
        itemsIndexed(children, key = { _, f -> "dir:" + f.path }) { _, folder ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(folder.path) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▸", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(20.dp))
                Text(folder.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(countLabel(folder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trackList(tracks, player, showAlbum = false, numbered = false)
    }
}

private fun findFolder(nodes: List<FolderNode>, path: String): FolderNode? {
    if (path.isEmpty()) return null
    for (n in nodes) {
        if (n.path == path) return n
        if (path.startsWith(n.path + "/")) return findFolder(n.children, path)
    }
    return null
}

private fun countLabel(f: FolderNode): String {
    fun count(n: FolderNode): Int = n.tracks.size + n.children.sumOf { count(it) }
    val n = count(f)
    return "$n song" + if (n == 1) "" else "s"
}

@Composable
fun QueueScreen(player: PlayerController) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = CONTENT_PADDING) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenTitle("Queue") }
                if (player.queue.size > 1) OutlinedButton(onClick = player::clearOthers) { Text("Clear others") }
            }
            if (player.queue.isEmpty()) Text("Nothing queued. Play an album or a song from your library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(player.queue) { i, track ->
            TrackRow(
                track, number = null, showAlbum = true, current = i == player.index,
                onClick = { player.jump(i) },
                trailing = { TextButton(onClick = { player.remove(i) }) { Text("Remove") } },
                menu = { emptyList() },
            )
        }
    }
}

// ---- rows --------------------------------------------------------------------------------------------------

/** Rows for [tracks]; a click plays them all from that song; right-click: play next / add to queue. */
fun LazyListScope.trackList(tracks: List<Track>, player: PlayerController, showAlbum: Boolean, numbered: Boolean) {
    itemsIndexed(tracks, key = { i, t -> "$i:${t.uri}" }) { i, track ->
        TrackRow(
            track, number = if (numbered) track.trackNo.takeIf { it > 0 } ?: (i + 1) else null, showAlbum = showAlbum,
            current = player.current?.uri == track.uri,
            onClick = { player.play(tracks, i) },
            menu = {
                listOf(
                    ContextMenuItem("Play next") { player.playNext(track) },
                    ContextMenuItem("Add to queue") { player.addToQueue(track) },
                )
            },
        )
    }
}

@Composable
fun TrackRow(
    track: Track,
    number: Int?,
    showAlbum: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    menu: () -> List<ContextMenuItem>,
    trailing: @Composable () -> Unit = {},
) {
    ContextMenuArea(items = menu) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val accent = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            if (number != null) {
                Text("$number", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
            } else if (showAlbum) {
                Cover(track.art, 40.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, color = accent, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (showAlbum) listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" — ")
                          else track.artist.takeIf { it.isNotBlank() && it != track.albumArtistOrArtist }.orEmpty()
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(track.qualityLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(TrackInfo.formatTime(track.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            trailing()
        }
    }
}

// ---- search ------------------------------------------------------------------------------------------------

@Composable
fun SearchResults(results: LibraryIndex.SearchResults, player: PlayerController, onAlbum: (Album) -> Unit, onArtist: (Artist) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = CONTENT_PADDING) {
        item { ScreenTitle("Search") }
        if (results.isEmpty) item { Text("Nothing matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (results.artists.isNotEmpty()) {
            item { SectionLabel("Artists") }
            itemsIndexed(results.artists.take(20), key = { _, a -> "artist:" + a.name }) { _, a ->
                Text(a.name, modifier = Modifier.fillMaxWidth().clickable { onArtist(a) }.padding(vertical = 8.dp), fontWeight = FontWeight.Medium)
            }
        }
        if (results.albums.isNotEmpty()) {
            item { SectionLabel("Albums") }
            itemsIndexed(results.albums.take(30), key = { _, a -> "album:" + a.key }) { _, a ->
                Row(
                    Modifier.fillMaxWidth().clickable { onAlbum(a) }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(a.art, 44.dp)
                    Column {
                        Text(a.title, fontWeight = FontWeight.Medium)
                        Text(a.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (results.tracks.isNotEmpty()) {
            item { SectionLabel("Songs") }
            trackList(results.tracks.take(200), player, showAlbum = true, numbered = false)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}
