package com.allmeyn.player.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.allmeyn.player.library.Track
import kotlinx.coroutines.delay
import java.io.File

/** What the screens show about playback, kept in sync with the MediaController. */
@Stable
class PlayerUi(private val controllerProvider: () -> MediaController?) {
    var connected by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var album by mutableStateOf("")
    var artPath by mutableStateOf("")
    var currentUri by mutableStateOf("")
    var positionMs by mutableLongStateOf(0)
    var durationMs by mutableLongStateOf(0)
    var shuffle by mutableStateOf(false)
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var hasMedia by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private val c get() = controllerProvider()

    internal fun sync(p: Player) {
        connected = true
        isPlaying = p.isPlaying
        hasMedia = p.mediaItemCount > 0
        val item = p.currentMediaItem
        val md = p.mediaMetadata
        title = md.title?.toString().orEmpty()
        artist = md.artist?.toString().orEmpty()
        album = md.albumTitle?.toString().orEmpty()
        artPath = md.artworkUri?.takeIf { it.scheme == "file" }?.path.orEmpty()
        currentUri = item?.localConfiguration?.uri?.toString() ?: item?.mediaId.orEmpty()
        positionMs = p.currentPosition
        durationMs = p.duration.coerceAtLeast(0)
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        if (p.playerError == null && p.playbackState != Player.STATE_IDLE) error = null
    }

    internal fun onError(e: PlaybackException) {
        // Media3 nests the real reason; show the chain so failures aren't silent.
        val chain = generateSequence(e as Throwable) { it.cause }.take(4)
            .joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
        error = "${e.errorCodeName}\n$chain"
    }

    fun playTracks(tracks: List<Track>, startIndex: Int) {
        val ctrl = c ?: return
        ctrl.setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(0, tracks.lastIndex), 0)
        ctrl.prepare()
        ctrl.play()
    }

    fun shuffleTracks(tracks: List<Track>) {
        val ctrl = c ?: return
        ctrl.shuffleModeEnabled = true
        playTracks(tracks, tracks.indices.random())
    }

    fun togglePlay() {
        val ctrl = c ?: return
        if (ctrl.isPlaying) ctrl.pause() else {
            if (ctrl.playbackState == Player.STATE_IDLE) ctrl.prepare()
            if (ctrl.playbackState == Player.STATE_ENDED) ctrl.seekTo(ctrl.currentMediaItemIndex, 0)
            ctrl.play()
        }
    }

    fun next() = c?.seekToNext()
    fun previous() = c?.seekToPrevious()
    fun seekTo(ms: Long) = c?.seekTo(ms)
    fun toggleShuffle() = c?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = c?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun pause() = c?.pause()
    fun resumeIf(wasPlaying: Boolean) = if (wasPlaying) c?.play() else Unit
    val controllerIsPlaying get() = c?.isPlaying == true
}

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(uri)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist.ifBlank { null })
            .setAlbumTitle(album.ifBlank { null })
            .setAlbumArtist(albumArtist.ifBlank { null })
            .setTrackNumber(trackNo.takeIf { it > 0 })
            .setDiscNumber(discNo.takeIf { it > 0 })
            .setArtworkUri(art.takeIf { it.isNotEmpty() }?.let { Uri.fromFile(File(it)) })
            .build(),
    )
    .build()

/** Keeps a [PlayerUi] in sync with [controller]; polls the position while playing. */
@Composable
fun rememberPlayerUi(controller: MediaController?): PlayerUi {
    val ui = remember { PlayerUi { null } }
    val bound = remember(controller) { PlayerUi { controller } }
    DisposableEffect(controller) {
        val c = controller
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = bound.sync(player)
            override fun onPlayerError(error: PlaybackException) = bound.onError(error)
        }
        c?.addListener(listener)
        c?.let(bound::sync)
        onDispose { c?.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                bound.positionMs = it.currentPosition
                bound.durationMs = it.duration.coerceAtLeast(0)
            }
            delay(if (bound.isPlaying) 250 else 1000)
        }
    }
    return if (controller == null) ui else bound
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** "1 album", "3 albums". */
fun countOf(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"
