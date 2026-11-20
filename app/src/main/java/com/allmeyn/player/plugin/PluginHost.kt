package com.allmeyn.player.plugin

import android.util.Log
import java.nio.ByteBuffer

/**
 * Holds the registered capture plugins and fans audio out to them.
 *
 * Register/unregister may be called from any thread. Dispatch methods are called from the
 * playback thread and do not allocate.
 */
object PluginHost {
    private const val TAG = "PluginHost"

    @Volatile
    private var plugins: Array<AudioCapturePlugin> = emptyArray()

    @Volatile
    var currentFormat: PcmFormat? = null
        private set

    fun register(plugin: AudioCapturePlugin) = synchronized(this) {
        if (plugins.contains(plugin)) return@synchronized
        plugins = plugins + plugin
        // A plugin added mid-playback still needs to know the active format.
        currentFormat?.let { safeCall(plugin) { it.onConfigure(currentFormat!!) } }
    }

    fun unregister(plugin: AudioCapturePlugin) = synchronized(this) {
        plugins = plugins.filter { it !== plugin }.toTypedArray()
    }

    fun registered(): List<AudioCapturePlugin> = plugins.toList()

    internal fun dispatchConfigure(format: PcmFormat) {
        currentFormat = format
        val snapshot = plugins
        for (p in snapshot) safeCall(p) { it.onConfigure(format) }
    }

    internal fun dispatchBuffer(buffer: ByteBuffer, presentationTimeUs: Long) {
        val snapshot = plugins
        if (snapshot.isEmpty()) return
        val position = buffer.position()
        val limit = buffer.limit()
        val order = buffer.order()
        for (p in snapshot) {
            safeCall(p) { it.onBuffer(buffer, presentationTimeUs) }
            // Restore so the next plugin and the real output see the untouched buffer.
            buffer.limit(limit)
            buffer.position(position)
            buffer.order(order)
        }
    }

    internal fun dispatchFlush() {
        val snapshot = plugins
        for (p in snapshot) safeCall(p) { it.onFlush() }
    }

    internal fun dispatchRelease() {
        currentFormat = null
        val snapshot = plugins
        for (p in snapshot) safeCall(p) { it.onRelease() }
    }

    /** A crashing plugin is removed rather than taking playback down with it. */
    private inline fun safeCall(plugin: AudioCapturePlugin, block: (AudioCapturePlugin) -> Unit) {
        try {
            block(plugin)
        } catch (e: Exception) {
            Log.e(TAG, "Plugin '${plugin.name}' threw; unregistering it", e)
            unregister(plugin)
        }
    }
}
