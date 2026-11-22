package com.allmeyn.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allmeyn.desktop.engine.NativeEngine
import com.allmeyn.desktop.engine.PlayState
import com.allmeyn.desktop.engine.TrackInfo
import com.allmeyn.player.library.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The play queue and the engine's player, for the UI. Use from the UI thread and [refresh] a few
 * times a second. The next song is always queued in the engine, so same-format songs join
 * gaplessly; a song in another format follows after the engine reports the end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerController(private val scope: CoroutineScope) {
    /** Engine calls that open files, one at a time and in order. */
    private val engine = Dispatchers.IO.limitedParallelism(1)

    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var index by mutableStateOf(-1)
        private set
    /** What the engine says about the song heard (format, output). */
    var playing by mutableStateOf<TrackInfo?>(null)
        private set
    var state by mutableStateOf(PlayState.IDLE)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var busy = 0           // engine opens in flight: don't poll (the engine holds its lock meanwhile)
    private var lastSerial = 0

    val current: Track? get() = queue.getOrNull(index)
    val durationMs: Long get() = playing?.durationMs?.takeIf { it > 0 } ?: current?.durationMs ?: 0

    /** Plays [tracks] from [start]. */
    fun play(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        if (shuffle) {
            queue = tracks.shuffled()
            index = 0
        } else {
            queue = tracks
            index = start.coerceIn(0, tracks.lastIndex)
        }
        openCurrent()
    }

    fun next() {
        if (index + 1 <= queue.lastIndex) jump(index + 1)
    }

    /** Back to the start of the song, or to the one before if it only just started. */
    fun previous() {
        if (positionMs > 3000 || index <= 0) seekTo(0) else jump(index - 1)
    }

    fun jump(i: Int) {
        if (i !in queue.indices) return
        index = i
        openCurrent()
    }

    fun playNext(track: Track) {
        if (current == null) return play(listOf(track))
        queue = queue.toMutableList().apply { add(index + 1, track) }
        queueNextInEngine()
    }

    fun addToQueue(track: Track) {
        if (current == null) return play(listOf(track))
        queue = queue + track
        if (index + 1 == queue.lastIndex) queueNextInEngine()
    }

    fun remove(i: Int) {
        if (i !in queue.indices) return
        val wasCurrent = i == index
        queue = queue.toMutableList().apply { removeAt(i) }
        when {
            wasCurrent && queue.isEmpty() -> stop()
            wasCurrent -> jump(i.coerceAtMost(queue.lastIndex))
            else -> {
                if (i < index) index--
                if (i <= index + 1) queueNextInEngine()
            }
        }
    }

    /** Keeps only the song playing. */
    fun clearOthers() {
        val c = current ?: return
        queue = listOf(c)
        index = 0
        queueNextInEngine()
    }

    fun togglePlay() {
        if (current == null || busy > 0) return
        if (state == PlayState.PLAYING) NativeEngine.playerPause() else NativeEngine.playerPlay()
        refresh()
    }

    fun seekTo(ms: Long) {
        val p = playing ?: return
        if (busy > 0) return
        NativeEngine.playerSeek(ms.coerceIn(0, p.durationMs) * p.sampleRate / 1000)
        positionMs = ms
    }

    fun refresh() {
        if (playing == null || busy > 0) return
        val serial = NativeEngine.playerTrackSerial()
        if (serial != lastSerial) {
            // The engine moved on gaplessly: follow it, and queue the song after.
            index = (index + (serial - lastSerial)).coerceAtMost(queue.lastIndex)
            lastSerial = serial
            playing = TrackInfo.from(NativeEngine.playerInfo())
            queueNextInEngine()
        }
        state = PlayState.of(NativeEngine.playerState())
        val rate = playing?.sampleRate?.coerceAtLeast(1) ?: 1
        positionMs = NativeEngine.playerPosition() * 1000 / rate
        when (state) {
            PlayState.ENDED -> if (index < queue.lastIndex) jump(index + 1)  // next song in another format (or none queued)
            PlayState.ERROR -> error = NativeEngine.playerError().ifBlank { "Playback failed" }
            else -> {}
        }
    }

    fun stop() {
        queue = emptyList()
        index = -1
        playing = null
        state = PlayState.IDLE
        if (NativeEngine.isReady) NativeEngine.playerClose()
    }

    fun close() {
        if (NativeEngine.isReady) NativeEngine.playerClose()
    }

    private fun openCurrent() {
        val track = current ?: return
        if (!NativeEngine.isReady) return
        busy++
        scope.launch {
            val err = withContext(engine) { NativeEngine.playerOpen(track.uri) }
            try {
                if (current != track) return@launch  // the user picked another song meanwhile
                if (err != null) {
                    error = "${track.fileName}: $err"
                    playing = null
                    state = PlayState.IDLE
                    return@launch
                }
                error = null
                positionMs = 0
                playing = TrackInfo.from(NativeEngine.playerInfo())
                lastSerial = NativeEngine.playerTrackSerial()
                NativeEngine.playerPlay()
            } finally {
                busy--
            }
            queueNextInEngine()
            refresh()
        }
    }

    private fun queueNextInEngine() {
        val next = queue.getOrNull(index + 1)
        scope.launch {
            withContext(engine) {
                if (next == null) NativeEngine.playerClearNext() else NativeEngine.playerSetNext(next.uri)
            }
        }
    }
}
