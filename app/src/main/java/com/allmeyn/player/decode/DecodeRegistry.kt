package com.allmeyn.player.decode

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/** What the decoders are doing, for the UI, the plugin tap and the playback service. */
object DecodeRegistry {
    /** DoP choice per file, fixed from the first (position 0) open until the item is reloaded. */
    private val dopByUri = ConcurrentHashMap<Uri, Boolean>()
    private val dsdUris = ConcurrentHashMap.newKeySet<Uri>()

    /** Description of the most recently opened decoded file, e.g. "DSF DSD64 → DoP 176.4 kHz". */
    @Volatile var lastDescription: String? = null
        private set

    /** True while the most recently opened decoded stream is DoP (not real PCM). */
    @Volatile var dopActive: Boolean = false
        private set

    fun dopFor(uri: Uri): Boolean? = dopByUri[uri]

    fun isDsd(uri: Uri) = uri in dsdUris

    internal fun onOpened(uri: Uri, isDsd: Boolean, isDop: Boolean, description: String, fromStart: Boolean) {
        if (fromStart) dopByUri[uri] = isDop
        if (isDsd) dsdUris += uri
        lastDescription = description
        dopActive = isDop
    }

    /** Forget DoP choices so the next load decides again (e.g. after the driver turns on/off). */
    fun resetModes() = dopByUri.clear()

    internal fun onPlainFile() {
        lastDescription = null
        dopActive = false
    }
}
