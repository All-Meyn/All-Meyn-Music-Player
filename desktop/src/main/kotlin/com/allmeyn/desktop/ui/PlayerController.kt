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
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(RepeatMode.OFF)
        private set
    /** While shuffled: the list in its own order (to go back to). The queue is always in play order. */
    private var original: List<Track>? = null

    private var busy = 0           // engine opens in flight: don't poll (the engine holds its lock meanwhile)
    private var lastSerial = 0

    val current: Track? get() = queue.getOrNull(index)
    val durationMs: Long get() = playing?.durationMs?.takeIf { it > 0 } ?: current?.durationMs ?: 0

    /**
     * Plays [tracks] from [start]. [shuffle] (a Shuffle button) turns shuffle on and starts at a
     * random song; with shuffle already on, [start] plays first and the rest follow shuffled.
     */
    fun play(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        if (shuffle) this.shuffle = true
        if (this.shuffle) {
            original = tracks
            queue = QueueOrder.shuffled(tracks, if (shuffle) -1 else start.coerceIn(0, tracks.lastIndex))
            index = 0
        } else {
            original = null
            queue = tracks
            index = start.coerceIn(0, tracks.lastIndex)
        }
        openCurrent()
    }

    /** Shuffle on (the playing song stays, the rest are shuffled) / off (back to the list's order). */
    fun toggleShuffle() {
        if (!shuffle) {
            original = queue
            if (queue.isNotEmpty()) {
                queue = QueueOrder.shuffled(queue, index)
                if (index >= 0) index = 0
            }
            shuffle = true
        } else {
            val (list, at) = QueueOrder.unshuffled(queue, index, original ?: queue)
            queue = list
            index = at
            original = null
            shuffle = false
        }
        queueNextInEngine()
    }

    /** Repeat off → all → one. */
    fun cycleRepeat() {
        repeat = repeat.next()
        queueNextInEngine()  // the song after this one may have changed
    }

    fun next() {
        QueueOrder.manualNext(index, queue.size, repeat)?.let(::jump)
    }

    /** Back to the start of the song, or to the one before if it only just started. */
    fun previous() {
        val before = if (positionMs > 3000) null else QueueOrder.manualPrevious(index, queue.size, repeat)
        if (before == null) seekTo(0) else jump(before)
    }

    fun jump(i: Int) {
        if (i !in queue.indices) return
        index = i
        openCurrent()
    }

    /** Opens the current song again where it was (after the output settings changed). */
    fun reopen() {
        if (current == null || busy > 0) return
        openCurrent(startMs = positionMs, autoplay = state == PlayState.PLAYING)
    }

    fun playNext(track: Track) {
        val c = current ?: return play(listOf(track))
        queue = queue.toMutableList().apply { add(index + 1, track) }
        // In the list's own order it follows the playing song too (where it lands when unshuffled).
        original = original?.let { o -> o.toMutableList().apply { val at = o.indexOf(c); add(if (at >= 0) at + 1 else size, track) } }
        queueNextInEngine()
    }

    fun addToQueue(track: Track) {
        if (current == null) return play(listOf(track))
        queue = queue + track
        original = original?.plus(track)
        queueNextInEngine()  // it may be next (end of the queue, or repeat all wrapping)
    }

    fun remove(i: Int) {
        if (i !in queue.indices) return
        val wasCurrent = i == index
        val removed = queue[i]
        queue = queue.toMutableList().apply { removeAt(i) }
        original = original?.toMutableList()?.apply { remove(removed) }
        when {
            wasCurrent && queue.isEmpty() -> stop()
            wasCurrent -> jump(i.coerceAtMost(queue.lastIndex))
            else -> {
                if (i < index) index--
                queueNextInEngine()
            }
        }
    }

    /** Keeps only the song playing. */
    fun clearOthers() {
        val c = current ?: return
        queue = listOf(c)
        index = 0
        original = original?.let { listOf(c) }
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
            // The engine moved on gaplessly (repeat one: the same song again): follow it, and queue the song after.
            for (step in 0 until serial - lastSerial) QueueOrder.autoNext(index, queue.size, repeat)?.let { index = it }
            lastSerial = serial
            playing = TrackInfo.from(NativeEngine.playerInfo())
            queueNextInEngine()
        }
        state = PlayState.of(NativeEngine.playerState())
        val rate = playing?.sampleRate?.coerceAtLeast(1) ?: 1
        positionMs = NativeEngine.playerPosition() * 1000 / rate
        when (state) {
            // The next song in another format (no gapless join), or repeat; else the queue is done.
            PlayState.ENDED -> QueueOrder.autoNext(index, queue.size, repeat)?.let(::jump)
            PlayState.ERROR -> error = NativeEngine.playerError().ifBlank { "Playback failed" }
            else -> {}
        }
    }

    fun stop() {
        queue = emptyList()
        index = -1
        original = null
        playing = null
        state = PlayState.IDLE
        if (NativeEngine.isReady) NativeEngine.playerClose()
    }

    fun close() {
        if (NativeEngine.isReady) NativeEngine.playerClose()
    }

    private fun openCurrent(startMs: Long = 0, autoplay: Boolean = true) {
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
                playing = TrackInfo.from(NativeEngine.playerInfo())
                lastSerial = NativeEngine.playerTrackSerial()
                val rate = playing?.sampleRate ?: 0
                positionMs = if (startMs > 0 && rate > 0) startMs else 0
                if (positionMs > 0) NativeEngine.playerSeek(positionMs * rate / 1000)
                if (autoplay) NativeEngine.playerPlay() else state = PlayState.PAUSED
            } finally {
                busy--
            }
            queueNextInEngine()
            refresh()
        }
    }

    private fun queueNextInEngine() {
        val next = QueueOrder.autoNext(index, queue.size, repeat)?.let(queue::getOrNull)
        scope.launch {
            withContext(engine) {
                if (next == null) NativeEngine.playerClearNext() else NativeEngine.playerSetNext(next.uri)
            }
        }
    }
}
