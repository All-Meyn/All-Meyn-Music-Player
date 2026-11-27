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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.allmeyn.player.playback.PlaybackService
import com.allmeyn.player.playback.ReplayGain
import com.allmeyn.player.dsp.DspParams
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
        GlassFrost.level = BackdropSettings.frost(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as AllMeynApp
        // Launched by plugging in the DAC ("Always open All-Meyn"): permission is granted now.
        app.usbDriver.maybeAutoEnable(UsbAudioDriver.ATTACH_SETTLE_MS)
        setContent {
            // The animated intro (once per launch) sits above the glass layer: glass elements are
            // drawn over everything else inside LiquidGlassTheme, so inside it the glass buttons
            // and bars showed through the intro.
            AllMeynTheme(overlay = { StartupOverlay() }) {
                ProvideWindowSize { AppRoot(controller, app) }
            }
        }
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
private fun AllMeynTheme(overlay: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // The brand's copper on cool, near-neutral greys (the logo's tile), in place of wallpaper colours.
    val scheme = if (dark) AllMeynDarkColors else AllMeynLightColors
    // Liquid Glass: records the screen once per frame; every glass element below refracts it. The
    // dialogs and menus draw in this window too, so it wraps everything, the playlist dialog included.
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize()) {
            // renderScale 0.7: the glass records and refracts at 70 % resolution. The library measured
            // this halving janky frames when scrolling (docs/performance.md); edges stay sharp.
            LiquidGlassTheme(dark = dark, background = scheme.surface, renderScale = 0.7f) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
            overlay() // drawn after (above) the glass layer
        }
    }
}

private enum class Tab(val label: String) { Albums("Albums"), Artists("Artists"), Songs("Songs"), Playlists("Playlists"), Folders("Folders") }

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
    // Songs waiting for the "Add to playlist" dialog, as URIs (one per line) so it survives folding.
    var pendingAddUris by rememberSaveable { mutableStateOf("") }
    val pendingAdd = remember(pendingAddUris, byUri) {
        pendingAddUris.takeIf { it.isNotEmpty() }?.split('\n')?.mapNotNull { byUri[it] }?.takeIf { it.isNotEmpty() }
    }
    fun added(name: String, n: Int) = Toast.makeText(context, "Added ${countOf(n, "song")} to $name", Toast.LENGTH_SHORT).show()

    var tab by rememberSaveable { mutableStateOf(Tab.Albums) }
    // Glass that appears in a page draws over the bars (GlassStacking): the bars enter composition
    // again after it. Right after a tab or screen change that waits for the tab bar's lens to finish
    // sliding (rebuilding the bar would cut it short); while scrolling it's at once.
    var barsKey by remember { mutableIntStateOf(0) }
    val navChangedAt = remember { longArrayOf(0L) }
    remember(tab, stack.size) { navChangedAt[0] = System.currentTimeMillis() }
    val glassEpoch = GlassStacking.epoch
    LaunchedEffect(glassEpoch) {
        if (glassEpoch == 0) return@LaunchedEffect
        val sinceNav = System.currentTimeMillis() - navChangedAt[0]
        if (sinceNav < 450) delay(450 - sinceNav)
        barsKey++
    }
    // Saved, so folding, unfolding or rotating keeps the open screen; each screen (and each tab)
    // keeps its own scroll position and dialogs in the state holder while it is on the stack.
    val stack = rememberSaveable(saver = RouteStackSaver) { mutableStateListOf<Route>() }
    val top = stack.lastOrNull()
    val screenStates = rememberSaveableStateHolder()
    fun screenKey(depth: Int, r: Route?) = if (r == null) "tab:${tab.name}" else "$depth:${r.encode()}"
    fun pop() {
        if (stack.isEmpty()) return
        screenStates.removeState(screenKey(stack.size, stack.last()))
        stack.removeAt(stack.lastIndex)
    }
    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    val window = LocalWindowSize.current

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) app.library.addFolder(uri)
    }
    // "Add folder" first explains, in our style, the system picker that comes next.
    var choosingFolder by rememberSaveable { mutableStateOf(false) }
    val addFolder = { choosingFolder = true }

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
    // The EQ can also change without the DSP screen (it follows the output): keep the screen current.
    DisposableEffect(Unit) {
        val l: (DspParams, DspParams) -> Unit = { _, new -> dspParams = new }
        DspSettings.addListener(l)
        dspParams = DspSettings.live
        onDispose { DspSettings.removeListener(l) }
    }
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

    ArtBackdrop(player.artPath, backdropStrength, playing = player.isPlaying)

    if (choosingFolder) {
        ChooseFolderSheet(
            onContinue = { choosingFolder = false; pickFolder.launch(MusicFolderUri) },
            onDismiss = { choosingFolder = false },
        )
    }

    pendingAdd?.let { songs ->
        AddToPlaylistDialog(
            tracks = songs,
            playlists = playlists,
            onAdd = { p -> app.playlists.add(p.id, songs.map { it.uri }); pendingAddUris = ""; added(p.name, songs.size) },
            onCreate = { name -> app.playlists.create(name, songs.map { it.uri }); pendingAddUris = ""; added(name, songs.size) },
            onDismiss = { pendingAddUris = "" },
        )
    }

    CompositionLocalProvider(LocalAddToPlaylist provides { songs -> pendingAddUris = songs.joinToString("\n") { it.uri } }) {
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
                        IconButton(onClick = { pop() }) {
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
                // Sideways phones are short on height: a slimmer bar.
                expandedHeight = if (window.short) 56.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
            )
        },
        bottomBar = { key(barsKey) {
            val showMini = player.hasMedia && top != Route.NowPlayingRoute
            val showTabs = top == null
            val tabItems: @Composable RowScope.() -> Unit = {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Albums -> PlayerIcons.Album
                                    Tab.Artists -> Icons.Filled.Person
                                    Tab.Songs -> Icons.AutoMirrored.Filled.List
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
            if (showMini && showTabs && window.short && window.wide) {
                // Phone turned sideways: mini player and tabs share one row, saving ~76 dp of height.
                Row(Modifier.navigationBarsPadding().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniPlayer(player, Modifier.weight(1f)) { stack.add(Route.NowPlayingRoute) }
                    NavigationBar(Modifier.weight(1f), windowInsets = WindowInsets(0), content = tabItems)
                }
            } else {
                // The glass tab bar keeps clear of the system navigation bar itself; alone, the mini player must.
                // On wide screens both stay a comfortable width, centred.
                Column(
                    if (showTabs) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (showMini) MiniPlayer(player, Modifier.widthIn(max = MaxContentWidth)) { stack.add(Route.NowPlayingRoute) }
                    if (showTabs) NavigationBar(Modifier.widthIn(max = 560.dp), content = tabItems)
                }
            }
        } },
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
                // A restored screen can come back before the library has loaded, or point at
                // something a rescan removed.
                val missing: @Composable (String) -> Unit = { what ->
                    if (!library.loaded) Loading() else EmptyState(PlayerIcons.Note, "This $what isn't in your library any more")
                }
                screenStates.SaveableStateProvider(screenKey(stack.size, top)) {
                    when (top) {
                        is Route.AlbumRoute -> library.albums.firstOrNull { it.key == top.key }
                            ?.let { AlbumDetail(it, playing, player) } ?: missing("album")
                        is Route.ArtistRoute -> library.artists.firstOrNull { it.name == top.name }
                            ?.let { a -> ArtistDetail(a, { stack.add(Route.AlbumRoute(it.key)) }, player) } ?: missing("artist")
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
                                onDelete = { app.playlists.delete(p.id); pop() },
                            )
                        } ?: missing("playlist")
                        Route.SearchRoute -> SearchScreen(
                            library = library,
                            playingUri = playing,
                            player = player,
                            onOpenAlbum = { stack.add(Route.AlbumRoute(it.key)) },
                            onOpenArtist = { stack.add(Route.ArtistRoute(it.name)) },
                        )
                        Route.NowPlayingRoute -> NowPlaying(player, sourceLabel, peak, driver, route, gainLabel, dspParams.processes, app.usbDriver::setVolumeStep)
                        Route.DspRoute -> DspScreen(dspParams, app.outputs) { change ->
                            DspSettings.update(app, change)
                            dspParams = DspSettings.live
                        }
                        Route.SettingsRoute -> SettingsScreen(
                            library = library,
                            folderLabel = app.library::folderLabel,
                            onAddFolder = addFolder,
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
                            glassFrost = GlassFrost.level,
                            onGlassFrost = { GlassFrost.level = it; BackdropSettings.setFrost(app, it) },
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
                            EmptyLibrary(library.scanning || !library.loaded, library.progress, addFolder)
                        } else {
                            Column {
                                if (library.scanning) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                when (tab) {
                                    Tab.Albums -> AlbumsGrid(library.albums) { stack.add(Route.AlbumRoute(it.key)) }
                                    Tab.Artists -> ArtistsList(library.artists) { stack.add(Route.ArtistRoute(it.name)) }
                                    Tab.Songs -> TracksList(library.tracks, playing, player)
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
}

@Composable
private fun MiniPlayer(player: PlayerUi, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    // A floating glass panel; tapping it opens Now playing.
    GlassSurface(onClick = onOpen, modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(22.dp), glass = AppGlass.panel) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(player.artPath, Modifier.size(44.dp), corner = 6.dp, size = 44.dp)
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
