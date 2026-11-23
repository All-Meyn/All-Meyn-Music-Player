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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allmeyn.desktop.engine.DspStore
import com.allmeyn.desktop.engine.OutputSettings
import com.allmeyn.desktop.engine.ReplayGainMode
import com.allmeyn.desktop.engine.PlayState
import com.allmeyn.desktop.engine.TrackInfo
import com.allmeyn.desktop.library.DesktopLibrary
import com.wexpa.liquidglass.material3.Surface as GlassSurface
import com.allmeyn.desktop.library.QueueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.allmeyn.player.library.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SECTIONS = listOf(
    "Albums" to Route.Albums, "Artists" to Route.Artists, "Songs" to Route.Songs,
    "Folders" to Route.Folders(), "Playlists" to Route.Playlists, "Queue" to Route.Queue, "DSP" to Route.Dsp,
    "Settings" to Route.Settings,
)

/** The whole window: sidebar (search, sections), the current screen, and the player bar. */
@Composable
fun App(library: DesktopLibrary, player: PlayerController, pickFolder: () -> String?) {
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf(OutputSettings.load().also { it.apply() }) }
    var replayGain by remember { mutableStateOf(ReplayGainMode.load().also { it.apply(output) }) }
    var backdrop by remember { mutableStateOf(BackdropSettings.strength()) }
    var dsp by remember { mutableStateOf(DspStore.load().also(DspStore::apply)) }
    val stack = remember { mutableStateListOf<Route>(Route.Albums) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { library.start() }
    // The queue comes back as it was (paused), once the library is there; then it's saved as it
    // changes (the position at most every 10 s while playing) and when the app closes.
    LaunchedEffect(library.loaded) {
        if (!library.loaded) return@LaunchedEffect
        withContext(Dispatchers.IO) { QueueStore.read(library.queueFile) }?.let { player.restore(it, library.view.byUri) }
        var saved = player.snapshot()
        while (true) {
            delay(2000)
            val now = player.snapshot()
            val changed = now?.copy(positionMs = 0) != saved?.copy(positionMs = 0) ||
                kotlin.math.abs((now?.positionMs ?: 0) - (saved?.positionMs ?: 0)) >= 10_000
            if (changed) {
                saved = now
                withContext(Dispatchers.IO) { if (now == null) library.queueFile.delete() else QueueStore.write(library.queueFile, now) }
            }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            player.refresh()
            delay(100)
        }
    }
    // Each screen in the stack keeps its scroll position etc. while covered (as on Android), so
    // Back returns to exactly where you were; popped / left screens forget theirs.
    val screenStates = rememberSaveableStateHolder()
    fun screenKey(depth: Int, r: Route) = "$depth:$r"
    fun forget(from: Int) { for (d in from until stack.size) screenStates.removeState(screenKey(d + 1, stack[d])) }
    fun go(root: Route) { query = ""; forget(0); stack.clear(); stack.add(root) }
    fun push(r: Route) { query = ""; stack.add(r) }
    fun back() { if (stack.size > 1) { forget(stack.lastIndex); stack.removeAt(stack.lastIndex) } }
    val chooseFolder: () -> Unit = { pickFolder()?.let { scope.launch { library.addFolder(it) } } }
    var addingToPlaylist by remember { mutableStateOf<List<Track>?>(null) }

    CompositionLocalProvider(LocalAddToPlaylist provides { tracks -> if (tracks.isNotEmpty()) addingToPlaylist = tracks }) {
    // The playing song's cover, blurred and slowly turning, behind everything (the glass refracts it).
    ArtBackdrop(player.current?.art.orEmpty(), backdrop, playing = player.state == PlayState.PLAYING)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            // ---- sidebar ----
            Column(
                // Translucent (not glass): the background art shows through, nothing to re-record.
                Modifier.width(230.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f)).padding(16.dp),
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
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f) else Color.Transparent)
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
                val needsMusic = route !is Route.Settings && route !is Route.Queue && route !is Route.Dsp && route !is Route.NowPlaying &&
                    route !is Route.Playlists && route !is Route.PlaylistPage
                when {
                    query.isNotBlank() -> SearchResults(
                        view.search.find(query), player,
                        onAlbum = { push(Route.AlbumPage(it.key)) }, onArtist = { push(Route.ArtistPage(it.name)) },
                    )
                    needsMusic && view.isEmpty -> if (library.loaded) EmptyLibrary(library, chooseFolder) else Box(Modifier.fillMaxSize())
                    else -> screenStates.SaveableStateProvider(screenKey(stack.size, route)) { when (route) {
                        Route.Albums -> AlbumGrid(view.albums, "Albums", onOpen = { push(Route.AlbumPage(it.key)) })
                        is Route.AlbumPage -> view.albums.firstOrNull { it.key == route.key }
                            ?.let { AlbumPage(it, player, onBack = ::back) } ?: Missing(::back)
                        Route.Artists -> ArtistList(view.artists, onOpen = { push(Route.ArtistPage(it.name)) })
                        is Route.ArtistPage -> view.artists.firstOrNull { it.name == route.name }
                            ?.let { a -> AlbumGrid(a.albums, a.name, onOpen = { push(Route.AlbumPage(it.key)) }, onBack = ::back) } ?: Missing(::back)
                        Route.Songs -> SongList(view.songs, player)
                        is Route.Folders -> FolderBrowser(view.folders, route.path, player, onOpen = { push(Route.Folders(it)) }, onBack = if (stack.size > 1) ::back else null)
                        Route.Queue -> QueueScreen(player)
                        Route.Playlists -> PlaylistsScreen(library.playlists, view, onOpen = { push(Route.PlaylistPage(it.id)) })
                        is Route.PlaylistPage -> library.playlists.all.firstOrNull { it.id == route.id }
                            ?.let { PlaylistPage(it, library.playlists, view, player, onBack = ::back) } ?: Missing(::back)
                        Route.NowPlaying -> NowPlayingScreen(player, replayGain, output, onBack = { if (stack.size > 1) back() else go(Route.Albums) })
                        Route.Dsp -> DspScreen(dsp) { change ->
                            val old = dsp
                            val new = change(old).sanitized()
                            if (new != old) {
                                dsp = new
                                DspStore.save(new)
                                DspStore.apply(new)
                                // Processing on/off changes how the song reaches the DAC (DSD as PCM,
                                // no bit-perfect claim): reopen it, from the same spot.
                                if (new.processes != old.processes) player.reopen()
                            }
                        }
                        Route.Settings -> SettingsScreen(
                            library, output,
                            onOutputChange = { new ->
                                output = new
                                new.save()
                                new.apply()
                                replayGain.apply(new)  // on for shared, off for bit-perfect outputs
                                player.reopen()  // hear the change now, from the same spot
                            },
                            replayGain = replayGain,
                            backdrop = backdrop,
                            onBackdropChange = { backdrop = it; BackdropSettings.setStrength(it) },
                            onReplayGainChange = { mode ->
                                replayGain = mode
                                mode.save()
                                mode.apply(output)
                                player.reopen()
                            },
                            onAddFolder = chooseFolder,
                            onRemoveFolder = { scope.launch { library.removeFolder(it) } },
                            onRescan = { scope.launch { library.rescan() } },
                        )
                    } }
                }
            }
        }
        PlayerBar(
            player, output, onOpenQueue = { go(Route.Queue) },
            // The song in the bar opens it in full; again = back to where you were.
            onOpenNowPlaying = {
                if (query.isBlank() && stack.last() == Route.NowPlaying) back()
                else if (player.current != null) { query = ""; if (stack.last() != Route.NowPlaying) stack.add(Route.NowPlaying) }
            },
        )
    }
    addingToPlaylist?.let { tracks -> AddToPlaylistDialog(tracks, library.playlists) { addingToPlaylist = null } }
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
fun PlayerBar(player: PlayerController, output: OutputSettings, onOpenQueue: () -> Unit, onOpenNowPlaying: () -> Unit) {
    val track = player.current
    // A floating glass bar, as the Android mini player; its own controls are plain Material (they
    // draw on the glass, so no glass sits inside glass).
    GlassSurface(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp).height(92.dp), shape = RoundedCornerShape(26.dp)) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.width(300.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = track != null, onClick = onOpenNowPlaying).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                val on = MaterialTheme.colorScheme.primary
                val off = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(onClick = player::toggleShuffle) { ControlGlyph(ControlIcon.SHUFFLE, if (player.shuffle) on else off, 16.dp) }
                IconButton(onClick = player::previous, enabled = track != null) { ControlGlyph(ControlIcon.PREVIOUS, color, 16.dp) }
                FilledIconButton(onClick = player::togglePlay, enabled = track != null, modifier = Modifier.size(44.dp)) {
                    ControlGlyph(if (player.state == PlayState.PLAYING) ControlIcon.PAUSE else ControlIcon.PLAY, MaterialTheme.colorScheme.onPrimary, 16.dp)
                }
                IconButton(
                    onClick = player::next,
                    enabled = track != null && QueueOrder.manualNext(player.index, player.queue.size, player.repeat) != null,
                ) { ControlGlyph(ControlIcon.NEXT, color, 16.dp) }
                IconButton(onClick = player::cycleRepeat) {
                    ControlGlyph(
                        if (player.repeat == RepeatMode.ONE) ControlIcon.REPEAT_ONE else ControlIcon.REPEAT,
                        if (player.repeat == RepeatMode.OFF) off else on, 16.dp,
                    )
                }
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
        Row(Modifier.width(320.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                player.playing?.let { p ->
                    Text(p.formatLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Text(p.output, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                }
                VolumeControl(output, sliderWidth = 130.dp)
            }
            IconButton(onClick = onOpenQueue) { ControlGlyph(ControlIcon.QUEUE, MaterialTheme.colorScheme.onSurfaceVariant, 18.dp) }
        }
    }
    }
}
