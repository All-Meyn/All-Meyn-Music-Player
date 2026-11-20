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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import com.allmeyn.player.usb.UsbAudioProbe
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(controller, app, ::titleFor, ::enqueue)
            }
        }
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

    val usbProbe = remember { UsbAudioProbe(context.applicationContext) }
    var probeText by remember { mutableStateOf("") }

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
                    Text(dac.deviceName ?: "Phone output", fontWeight = FontWeight.SemiBold)
                    Text(dac.detail, style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("USB driver · probe", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            probeText = "Probing…"
                            usbProbe.probe { probeText = it }
                        }) { Text("Probe DAC") }
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
                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
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
