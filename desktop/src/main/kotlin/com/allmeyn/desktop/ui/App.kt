package com.allmeyn.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allmeyn.desktop.engine.OutputSettings
import com.allmeyn.desktop.engine.PlayState
import com.allmeyn.desktop.engine.TrackInfo
import com.allmeyn.desktop.library.DesktopLibrary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SECTIONS = listOf(
    "Albums" to Route.Albums, "Artists" to Route.Artists, "Songs" to Route.Songs,
    "Folders" to Route.Folders(), "Queue" to Route.Queue, "Settings" to Route.Settings,
)

/** The whole window: sidebar (search, sections), the current screen, and the player bar. */
@Composable
fun App(library: DesktopLibrary, player: PlayerController, pickFolder: () -> String?) {
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf(OutputSettings.load().also { it.apply() }) }
    val stack = remember { mutableStateListOf<Route>(Route.Albums) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { library.start() }
    LaunchedEffect(player) {
        while (true) {
            player.refresh()
            delay(100)
        }
    }
    fun go(root: Route) { query = ""; stack.clear(); stack.add(root) }
    fun push(r: Route) { query = ""; stack.add(r) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val chooseFolder: () -> Unit = { pickFolder()?.let { scope.launch { library.addFolder(it) } } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            // ---- sidebar ----
            Column(
                Modifier.width(230.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    androidx.compose.material3.Icon(BrandMark.vector, null, tint = BrandCopper, modifier = Modifier.size(width = 46.dp, height = 24.dp))
                    Text("All-Meyn", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                val root = stack.first()
                SECTIONS.forEach { (label, route) ->
                    val selected = query.isBlank() && root::class == route::class
                    Text(
                        label,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow)
                            .clickable { go(route) }.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                library.scanProgress?.let { (done, total) ->
                    Text("Reading library… $done / $total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // ---- screen ----
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val view = library.view
                val route = stack.last()
                val needsMusic = route !is Route.Settings && route !is Route.Queue
                when {
                    query.isNotBlank() -> SearchResults(
                        view.search.find(query), player,
                        onAlbum = { push(Route.AlbumPage(it.key)) }, onArtist = { push(Route.ArtistPage(it.name)) },
                    )
                    needsMusic && view.isEmpty -> if (library.loaded) EmptyLibrary(library, chooseFolder) else Box(Modifier.fillMaxSize())
                    else -> when (route) {
                        Route.Albums -> AlbumGrid(view.albums, "Albums", onOpen = { push(Route.AlbumPage(it.key)) })
                        is Route.AlbumPage -> view.albums.firstOrNull { it.key == route.key }
                            ?.let { AlbumPage(it, player, onBack = ::back) } ?: Missing(::back)
                        Route.Artists -> ArtistList(view.artists, onOpen = { push(Route.ArtistPage(it.name)) })
                        is Route.ArtistPage -> view.artists.firstOrNull { it.name == route.name }
                            ?.let { a -> AlbumGrid(a.albums, a.name, onOpen = { push(Route.AlbumPage(it.key)) }, onBack = ::back) } ?: Missing(::back)
                        Route.Songs -> SongList(view.songs, player)
                        is Route.Folders -> FolderBrowser(view.folders, route.path, player, onOpen = { push(Route.Folders(it)) }, onBack = if (stack.size > 1) ::back else null)
                        Route.Queue -> QueueScreen(player)
                        Route.Settings -> SettingsScreen(
                            library, output,
                            onOutputChange = { new ->
                                output = new
                                new.save()
                                new.apply()
                                player.reopen()  // hear the change now, from the same spot
                            },
                            onAddFolder = chooseFolder,
                            onRemoveFolder = { scope.launch { library.removeFolder(it) } },
                            onRescan = { scope.launch { library.rescan() } },
                        )
                    }
                }
            }
        }
        PlayerBar(player, onOpenQueue = { go(Route.Queue) })
    }
}

@Composable
private fun Missing(onBack: () -> Unit) {
    Column(Modifier.padding(28.dp)) {
        ScreenTitle("Not in your library any more", onBack)
    }
}

/** Now playing, the transport controls and the seek bar. */
@Composable
fun PlayerBar(player: PlayerController, onOpenQueue: () -> Unit) {
    val track = player.current
    Row(
        Modifier.fillMaxWidth().height(92.dp).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(track?.art.orEmpty(), 60.dp)
            Column {
                Text(track?.title ?: "Nothing playing", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track != null) Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                player.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val color = MaterialTheme.colorScheme.onSurface
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(onClick = player::previous, enabled = track != null) { ControlGlyph(ControlIcon.PREVIOUS, color, 16.dp) }
                FilledIconButton(onClick = player::togglePlay, enabled = track != null, modifier = Modifier.size(44.dp)) {
                    ControlGlyph(if (player.state == PlayState.PLAYING) ControlIcon.PAUSE else ControlIcon.PLAY, MaterialTheme.colorScheme.onPrimary, 16.dp)
                }
                IconButton(onClick = player::next, enabled = track != null && player.index < player.queue.lastIndex) { ControlGlyph(ControlIcon.NEXT, color, 16.dp) }
            }
            // While dragging, the bar follows the pointer; the seek happens on release.
            var dragMs by remember(track) { mutableStateOf<Float?>(null) }
            val duration = player.durationMs.coerceAtLeast(1)
            val shown = dragMs ?: player.positionMs.toFloat()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TrackInfo.formatTime(shown.toLong()), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
                Slider(
                    value = shown.coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragMs = it },
                    onValueChangeFinished = { dragMs?.let { player.seekTo(it.toLong()) }; dragMs = null },
                    valueRange = 0f..duration.toFloat(),
                    enabled = track != null,
                    modifier = Modifier.weight(1f).height(24.dp).padding(horizontal = 8.dp),
                )
                Text(TrackInfo.formatTime(player.durationMs), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
            }
        }
        Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                player.playing?.let { p ->
                    Text(p.formatLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Text(p.output, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.End)
                }
            }
            IconButton(onClick = onOpenQueue) { ControlGlyph(ControlIcon.QUEUE, MaterialTheme.colorScheme.onSurfaceVariant, 18.dp) }
        }
    }
}
