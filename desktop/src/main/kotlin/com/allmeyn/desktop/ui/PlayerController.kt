package com.allmeyn.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allmeyn.desktop.engine.NativeEngine
import com.allmeyn.desktop.engine.PlayState
import com.allmeyn.desktop.engine.TrackInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The UI's view of the engine's player. Use from the UI thread; [refresh] it a few times a second. */
class PlayerController {
    var track by mutableStateOf<TrackInfo?>(null)
        private set
    var state by mutableStateOf(PlayState.IDLE)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var opening by mutableStateOf(false)
        private set

    /** Opens [file] and starts playing it. */
    suspend fun open(file: File) {
        if (!NativeEngine.isReady || opening) return
        opening = true
        try {
            val err = withContext(Dispatchers.IO) { NativeEngine.playerOpen(file.absolutePath) }
            if (err != null) {
                error = "${file.name}: $err"
                track = null
                state = PlayState.IDLE
                return
            }
            track = TrackInfo.from(file, NativeEngine.playerInfo())
            error = null
            positionMs = 0
            NativeEngine.playerPlay()
        } finally {
            opening = false
        }
        refresh()
    }

    fun togglePlay() {
        if (track == null || opening) return
        if (state == PlayState.PLAYING) NativeEngine.playerPause() else NativeEngine.playerPlay()
        refresh()
    }

    fun seekTo(ms: Long) {
        val t = track ?: return
        if (opening) return
        NativeEngine.playerSeek(ms.coerceIn(0, t.durationMs) * t.sampleRate / 1000)
        positionMs = ms
    }

    fun refresh() {
        val t = track ?: return
        if (opening) return  // the engine is busy opening; don't block the UI waiting for it
        state = PlayState.of(NativeEngine.playerState())
        positionMs = NativeEngine.playerPosition() * 1000 / t.sampleRate.coerceAtLeast(1)
        if (state == PlayState.ERROR) error = NativeEngine.playerError().ifBlank { "Playback failed" }
    }

    fun close() {
        if (NativeEngine.isReady) NativeEngine.playerClose()
    }
}
