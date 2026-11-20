package com.allmeyn.player.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.allmeyn.player.AllMeynApp
import com.allmeyn.player.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@UnstableApi
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private val pendingItems = mutableListOf<MediaItem>()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as AllMeynApp
        // Launched by plugging in the DAC ("Always open All-Meyn"): permission is granted now.
        app.usbDriver.maybeAutoEnable()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(controller, app, ::titleFor, ::enqueue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (application as AllMeynApp).usbDriver.maybeAutoEnable()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            // Ignore a connection that onStop() already released.
            if (controllerFuture !== future) return@addListener
            val c = future.get()
            controller = c
            if (pendingItems.isNotEmpty()) {
                addAndPlay(c, pendingItems.toList())
                pendingItems.clear()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * The file picker sends this activity to the background, which releases the controller, and
     * its result arrives before the controller reconnects. Queue items until it's back.
     */
    private fun enqueue(items: List<MediaItem>) {
        val c = controller
        if (c == null) pendingItems += items else addAndPlay(c, items)
    }

    private fun addAndPlay(c: MediaController, items: List<MediaItem>) {
        val wasEmpty = c.mediaItemCount == 0
        c.addMediaItems(items)
        if (wasEmpty) {
            c.prepare()
            c.play()
        }
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun titleFor(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "Unknown"
}

@UnstableApi
@Composable
private fun PlayerScreen(
    controller: MediaController?,
    app: AllMeynApp,
    titleFor: (Uri) -> String,
    onAddItems: (List<MediaItem>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dac by app.usbDac.status.collectAsStateWithLifecycle()

    var isPlaying by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var titles by remember { mutableStateOf(emptyList<String>()) }

    DisposableEffect(controller) {
        val c = controller
        fun sync(p: Player) {
            isPlaying = p.isPlaying
            currentIndex = p.currentMediaItemIndex
            titles = (0 until p.mediaItemCount).map {
                p.getMediaItemAt(it).mediaMetadata.title?.toString() ?: "Track ${it + 1}"
            }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync(player)
        }
        c?.addListener(listener)
        c?.let(::sync)
        onDispose { c?.removeListener(listener) }
    }

    val usbDriver = app.usbDriver
    val driver by usbDriver.state.collectAsStateWithLifecycle()
    var probeText by remember { mutableStateOf("") }
    var driverStatus by remember { mutableStateOf("") }
    LaunchedEffect(driver.active) {
        if (!driver.active) driverStatus = ""
        while (driver.active) {
            usbDriver.status { driverStatus = it }
            delay(500)
        }
    }
    fun setDriver(on: Boolean) {
        val c = controller
        val wasPlaying = c?.isPlaying == true
        c?.pause() // PlaybackService rebuilds the audio output once the route has changed
        val done: (String) -> Unit = { if (wasPlaying) c?.play() }
        if (on) usbDriver.enable(done) else usbDriver.disable(done)
    }
    var toneActive by remember { mutableStateOf(false) }
    fun startTone(rate: Int, bits: Int) {
        controller?.pause()
        toneActive = true
        usbDriver.startTone(rate, bits) { probeText = it }
    }

    var peak by remember { mutableFloatStateOf(0f) }
    var formatLabel by remember { mutableStateOf("—") }
    LaunchedEffect(Unit) {
        while (true) {
            peak = app.levelMeter.peak
            formatLabel = app.levelMeter.formatLabel
            delay(100)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val items = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(titleFor(uri)).build())
                .build()
        }
        onAddItems(items)
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("All-Meyn Player", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    if (driver.active) {
                        Text(driver.deviceName ?: "USB DAC", fontWeight = FontWeight.SemiBold)
                        Text(
                            "All-Meyn USB driver · bit-perfect · " + driverStatus.substringBefore('\n'),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(dac.deviceName ?: "Phone output", fontWeight = FontWeight.SemiBold)
                        Text(dac.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("USB driver", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            probeText = "Probing…"
                            usbDriver.probe { probeText = it }
                        }) { Text("Probe DAC") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Bit-perfect USB driver", fontWeight = FontWeight.Medium)
                            Text(
                                "Bypasses Android's mixer. Stays on and comes back when the DAC is plugged in.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = driver.active, enabled = !driver.busy, onCheckedChange = { setDriver(it) })
                    }
                    if (driver.message.isNotEmpty()) Text(driver.message, style = MaterialTheme.typography.bodySmall)
                    driver.volume?.let { v ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (v.muted || v.step == 0) "DAC volume · muted" else "DAC volume · %.1f dB".format(v.db),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(140.dp),
                            )
                            // Commit on release: each change is a USB control request.
                            var dragging by remember { mutableStateOf<Float?>(null) }
                            Slider(
                                value = dragging ?: v.step.toFloat(),
                                onValueChange = { dragging = it },
                                onValueChangeFinished = {
                                    dragging?.let { usbDriver.setVolumeStep(it.roundToInt()) }
                                    dragging = null
                                },
                                valueRange = 0f..v.maxStep.toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (driverStatus.isNotEmpty()) {
                        Text(driverStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (driver.active) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Test tone", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { startTone(44100, 16) }) { Text("44.1k/16") }
                            OutlinedButton(onClick = { startTone(96000, 32) }) { Text("96k/32") }
                        }
                    }
                    if (probeText.isNotEmpty()) {
                        // Own scroll area so a long report doesn't push the playlist off screen.
                        SelectionContainer(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                            Text(probeText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Plugin capture · ${app.levelMeter.name}", fontWeight = FontWeight.SemiBold)
                    Text(formatLabel, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { peak }, modifier = Modifier.fillMaxWidth())
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { controller?.seekToPreviousMediaItem() }) { Text("Prev") }
                Button(onClick = {
                    controller?.let {
                        if (it.isPlaying) {
                            it.pause()
                        } else {
                            // The tone took over the driver's stream: rebuild the music output first.
                            if (toneActive) it.seekTo(it.currentPosition)
                            toneActive = false
                            it.play()
                        }
                    }
                }) { Text(if (isPlaying) "Pause" else "Play") }
                OutlinedButton(onClick = { controller?.seekToNextMediaItem() }) { Text("Next") }
                OutlinedButton(onClick = {
                    picker.launch(arrayOf("audio/*", "application/octet-stream", "application/ogg"))
                }) { Text("Add") }
            }

            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(titles) { index, title ->
                    Text(
                        text = title,
                        fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { controller?.run { seekTo(index, 0); play() } }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}
