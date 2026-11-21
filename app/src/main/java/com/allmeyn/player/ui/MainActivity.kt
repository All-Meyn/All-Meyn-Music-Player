package com.allmeyn.player.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.allmeyn.player.AllMeynApp
import com.allmeyn.player.decode.DecodeRegistry
import com.allmeyn.player.library.Track
import com.allmeyn.player.playback.PlaybackService
import com.allmeyn.player.playback.ReplayGain
import com.allmeyn.player.dsp.DspSettings
import com.allmeyn.player.usb.UsbAudioDriver
import com.google.common.util.concurrent.ListenableFuture
import com.wexpa.liquidglass.LiquidGlassTheme
import com.wexpa.liquidglass.material3.NavigationBar
import com.wexpa.liquidglass.material3.NavigationBarItem
import com.wexpa.liquidglass.material3.TopAppBar
import com.wexpa.liquidglass.material3.Surface as GlassSurface
import kotlinx.coroutines.delay

@UnstableApi
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as AllMeynApp
        // Launched by plugging in the DAC ("Always open All-Meyn"): permission is granted now.
        app.usbDriver.maybeAutoEnable(UsbAudioDriver.ATTACH_SETTLE_MS)
        setContent { AllMeynTheme { AppRoot(controller, app) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (application as AllMeynApp).usbDriver.maybeAutoEnable(UsbAudioDriver.ATTACH_SETTLE_MS)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            // Ignore a connection that onStop() already released.
            if (controllerFuture === future) controller = future.get()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }
}

@Composable
private fun AllMeynTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    // Liquid Glass: records the screen once per frame; every glass element below refracts it. The
    // dialogs and menus draw in this window too, so it wraps everything, the playlist dialog included.
    MaterialTheme(colorScheme = scheme) {
        LiquidGlassTheme(dark = dark, background = scheme.surface) {
            Surface(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** How much of the screen's bottom the glass mini player and tab bar cover: lists pad their end by it. */
val LocalBottomBarPadding = compositionLocalOf { 0.dp }

/** A list's content padding plus [LocalBottomBarPadding], so it scrolls under the bottom bars. */
@Composable
fun belowBars(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp) =
    PaddingValues(start, top, end, bottom + LocalBottomBarPadding.current)

private enum class Tab(val label: String) { Albums("Albums"), Artists("Artists"), Tracks("Tracks"), Playlists("Playlists"), Folders("Folders") }

/** Screens pushed on top of the tabs. */
private sealed interface Route {
    data class AlbumRoute(val key: String) : Route
    data class ArtistRoute(val name: String) : Route
    data class FolderRoute(val path: String) : Route
    data class PlaylistRoute(val id: String) : Route
    data object NowPlayingRoute : Route
    data object QueueRoute : Route
    data object SearchRoute : Route
    data object DspRoute : Route
    data object SettingsRoute : Route
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(controller: MediaController?, app: AllMeynApp) {
    val player = rememberPlayerUi(controller)
    val library by app.library.state.collectAsStateWithLifecycle()
    val driver by app.usbDriver.state.collectAsStateWithLifecycle()
    val androidDac by app.usbDac.status.collectAsStateWithLifecycle()
    val playlists by app.playlists.state.collectAsStateWithLifecycle()
    val byUri = remember(library.tracks) { library.tracks.associateBy { it.uri } }
    val context = LocalContext.current
    // Songs waiting for the "Add to playlist" dialog.
    var pendingAdd by remember { mutableStateOf<List<Track>?>(null) }
    fun added(name: String, n: Int) = Toast.makeText(context, "Added ${countOf(n, "song")} to $name", Toast.LENGTH_SHORT).show()

    var tab by rememberSaveable { mutableStateOf(Tab.Albums) }
    val stack = remember { mutableStateListOf<Route>() }
    val top = stack.lastOrNull()
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) app.library.addFolder(uri)
    }

    // Live readouts: plugin meter, decoder, driver status.
    var peak by remember { mutableFloatStateOf(0f) }
    var pluginFormat by remember { mutableStateOf("—") }
    var decoderLabel by remember { mutableStateOf<String?>(null) }
    var decoderError by remember { mutableStateOf<String?>(null) }
    var driverStatus by remember { mutableStateOf("") }
    var gainLabel by remember { mutableStateOf<String?>(null) }
    var replayGainMode by remember { mutableStateOf(ReplayGain.mode(app)) }
    var backdropStrength by remember { mutableStateOf(BackdropSettings.strength(app)) }
    var dspParams by remember { mutableStateOf(DspSettings.live) }
    LaunchedEffect(Unit) {
        while (true) {
            peak = app.levelMeter.peak
            pluginFormat = app.levelMeter.formatLabel
            decoderLabel = DecodeRegistry.lastDescription
            decoderError = DecodeRegistry.lastError
            gainLabel = ReplayGain.applied
            delay(100)
        }
    }
    LaunchedEffect(driver.active) {
        if (!driver.active) driverStatus = ""
        while (driver.active) {
            app.usbDriver.status { driverStatus = it }
            delay(500)
        }
    }
    fun setDriver(on: Boolean) {
        val wasPlaying = player.controllerIsPlaying
        player.pause() // PlaybackService rebuilds the audio output once the route has changed
        val done: (String) -> Unit = { player.resumeIf(wasPlaying) }
        if (on) app.usbDriver.enable(done) else app.usbDriver.disable(done)
    }
    val sourceLabel = decoderLabel ?: pluginFormat.takeIf { it != "—" }.orEmpty()
    val route = driverStatus.lineSequence().firstOrNull { it.startsWith("Player output:") }?.substringAfter(": ").orEmpty()

    ArtBackdrop(player.artPath, backdropStrength)

    pendingAdd?.let { songs ->
        AddToPlaylistDialog(
            tracks = songs,
            playlists = playlists,
            onAdd = { p -> app.playlists.add(p.id, songs.map { it.uri }); pendingAdd = null; added(p.name, songs.size) },
            onCreate = { name -> app.playlists.create(name, songs.map { it.uri }); pendingAdd = null; added(name, songs.size) },
            onDismiss = { pendingAdd = null },
        )
    }

    CompositionLocalProvider(LocalAddToPlaylist provides { pendingAdd = it }) {
    Scaffold(
        containerColor = Color.Transparent, // the blurred cover (or the plain surface) shows through
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (top) {
                            is Route.AlbumRoute -> library.albums.firstOrNull { it.key == top.key }?.title.orEmpty()
                            is Route.ArtistRoute -> top.name
                            is Route.FolderRoute -> top.path.substringAfterLast('/')
                            is Route.PlaylistRoute -> playlists.firstOrNull { it.id == top.id }?.name.orEmpty()
                            Route.NowPlayingRoute -> "Now playing"
                            Route.QueueRoute -> "Queue"
                            Route.SearchRoute -> "Search"
                            Route.DspRoute -> "DSP"
                            Route.SettingsRoute -> "Settings"
                            null -> "All-Meyn"
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                // Null, not empty: the glass bar draws a round button / capsule around whatever it is given.
                navigationIcon = if (top == null) null else {
                    {
                        IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = if (top == Route.SettingsRoute) null else {
                    {
                        if (top == null) {
                            IconButton(onClick = { stack.add(Route.SearchRoute) }) { Icon(Icons.Filled.Search, "Search") }
                        }
                        if (top == Route.NowPlayingRoute) {
                            IconButton(onClick = { stack.add(Route.DspRoute) }) {
                                Icon(PlayerIcons.Tune, "DSP", tint = if (dspParams.processes) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                            }
                            IconButton(onClick = { stack.add(Route.QueueRoute) }) { Icon(PlayerIcons.Queue, "Queue") }
                        }
                        IconButton(onClick = { stack.add(Route.SettingsRoute) }) { Icon(Icons.Filled.Settings, "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            // The glass tab bar keeps clear of the system navigation bar itself; alone, the mini player must.
            Column(if (top != null) Modifier.navigationBarsPadding() else Modifier) {
                if (player.hasMedia && top != Route.NowPlayingRoute) {
                    MiniPlayer(player) { stack.add(Route.NowPlayingRoute) }
                }
                if (top == null) {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = {
                                    Icon(
                                        when (t) {
                                            Tab.Albums -> PlayerIcons.Album
                                            Tab.Artists -> Icons.Filled.Person
                                            Tab.Tracks -> Icons.AutoMirrored.Filled.List
                                            Tab.Playlists -> PlayerIcons.Playlist
                                            Tab.Folders -> PlayerIcons.Folder
                                        },
                                        t.label,
                                    )
                                },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // The content runs under the bottom bars (the glass refracts it); lists pad their end instead.
        val dir = LocalLayoutDirection.current
        val above = PaddingValues(start = padding.calculateStartPadding(dir), top = padding.calculateTopPadding(), end = padding.calculateEndPadding(dir))
        CompositionLocalProvider(LocalBottomBarPadding provides padding.calculateBottomPadding()) {
        Column(Modifier.padding(above).fillMaxSize()) {
            val problem = listOfNotNull(player.error, decoderError?.let { "Decoder: $it" }).joinToString("\n")
            if (problem.isNotEmpty()) ErrorBanner(problem) {
                player.error = null
                DecodeRegistry.lastError = null
            }
            Box(Modifier.weight(1f)) {
                val playing = player.currentUri
                when (top) {
                    is Route.AlbumRoute -> library.albums.firstOrNull { it.key == top.key }
                        ?.let { AlbumDetail(it, playing, player) }
                    is Route.ArtistRoute -> library.artists.firstOrNull { it.name == top.name }
                        ?.let { a -> ArtistDetail(a, { stack.add(Route.AlbumRoute(it.key)) }, player) }
                    is Route.FolderRoute -> FolderBrowser(library.folderTree, top.path, { stack.add(Route.FolderRoute(it)) }, playing, player)
                    Route.QueueRoute -> QueueScreen(player) { name, uris ->
                        app.playlists.create(name, uris)
                        added(name, uris.size)
                    }
                    is Route.PlaylistRoute -> playlists.firstOrNull { it.id == top.id }?.let { p ->
                        PlaylistDetail(
                            playlist = p, byUri = byUri, playingUri = playing, player = player,
                            onRemove = { app.playlists.removeAt(p.id, it) },
                            onMove = { from, to -> app.playlists.move(p.id, from, to) },
                            onRename = { app.playlists.rename(p.id, it) },
                            onDelete = { app.playlists.delete(p.id); stack.removeAt(stack.lastIndex) },
                        )
                    }
                    Route.SearchRoute -> SearchScreen(
                        library = library,
                        playingUri = playing,
                        player = player,
                        onOpenAlbum = { stack.add(Route.AlbumRoute(it.key)) },
                        onOpenArtist = { stack.add(Route.ArtistRoute(it.name)) },
                    )
                    Route.NowPlayingRoute -> NowPlaying(player, sourceLabel, peak, driver, route, gainLabel, dspParams.processes, app.usbDriver::setVolumeStep)
                    Route.DspRoute -> DspScreen(dspParams) { change ->
                        DspSettings.update(app, change)
                        dspParams = DspSettings.live
                    }
                    Route.SettingsRoute -> SettingsScreen(
                        library = library,
                        folderLabel = app.library::folderLabel,
                        onAddFolder = { pickFolder.launch(null) },
                        onRemoveFolder = app.library::removeFolder,
                        onRescan = app.library::rescan,
                        driver = driver,
                        driverStatus = driverStatus,
                        androidDac = androidDac,
                        onDriver = ::setDriver,
                        onDacVolume = app.usbDriver::setVolumeStep,
                        onNativeDsd = { app.usbDriver.nativeDsdEnabled = it },
                        onAutoForDsd = { app.usbDriver.autoEnableForDsd = it },
                        replayGain = replayGainMode,
                        onReplayGain = { replayGainMode = it; ReplayGain.setMode(app, it) },
                        backdropStrength = backdropStrength,
                        onBackdropStrength = { backdropStrength = it; BackdropSettings.setStrength(app, it) },
                        dspOn = dspParams.processes,
                        onOpenDsp = { stack.add(Route.DspRoute) },
                        onProbe = { cb -> app.usbDriver.probe(cb) },
                        onTone = { rate, bits -> player.pause(); app.usbDriver.startTone(rate, bits) {} },
                        pluginName = app.levelMeter.name,
                        pluginFormat = pluginFormat,
                        decoderLabel = decoderLabel,
                        peak = peak,
                    )
                    null -> if (library.tracks.isEmpty()) {
                        EmptyLibrary(library.scanning || !library.loaded, library.progress) { pickFolder.launch(null) }
                    } else {
                        Column {
                            if (library.scanning) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            when (tab) {
                                Tab.Albums -> AlbumsGrid(library.albums) { stack.add(Route.AlbumRoute(it.key)) }
                                Tab.Artists -> ArtistsList(library.artists) { stack.add(Route.ArtistRoute(it.name)) }
                                Tab.Tracks -> TracksList(library.tracks, playing, player)
                                Tab.Playlists -> PlaylistsList(playlists, byUri, { stack.add(Route.PlaylistRoute(it.id)) }) { name ->
                                    stack.add(Route.PlaylistRoute(app.playlists.create(name)))
                                }
                                Tab.Folders -> FolderBrowser(library.folderTree, null, { stack.add(Route.FolderRoute(it)) }, playing, player)
                            }
                        }
                    }
                }
            }
        }
        }
    }
    }
}

@Composable
private fun MiniPlayer(player: PlayerUi, onOpen: () -> Unit) {
    // A floating glass panel; tapping it opens Now playing.
    GlassSurface(onClick = onOpen, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(22.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(player.artPath, Modifier.size(44.dp), corner = 6.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(player.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(player.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { player.togglePlay() }) {
                    Icon(if (player.isPlaying) PlayerIcons.Pause else PlayerIcons.Play, "Play/pause")
                }
                IconButton(onClick = { player.next() }) { Icon(PlayerIcons.Next, "Next") }
            }
            val d = player.durationMs.coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { (player.positionMs.toFloat() / d).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 6.dp).height(2.dp),
            )
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    "Playback error\n$text", color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss") }
        }
    }
}
