package com.allmeyn.player.plugin

import java.nio.ByteBuffer

/**
 * Describes the PCM stream a plugin is about to receive.
 *
 * @property encoding Media3 PCM encoding constant: C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
 *   C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT, etc. This is the decoder's native output, i.e. the
 *   audio exactly as decoded from the file, before any resampling or bit-depth conversion.
 * @property sampleRate Frames per second.
 * @property channelCount Interleaved channel count.
 * @property bytesPerFrame Bytes in one frame (all channels).
 * @property isDop The stream is DSD packed as DoP (24-bit frames carrying DSD bits for the DAC),
 *   not real PCM. Don't process it as audio; it must reach the DAC unmodified.
 * @property isNativeDsd The stream is raw DSD for the DAC's native DSD mode, carried as 16-bit
 *   "PCM" with twice the channels (each pair of 16-bit samples is one 32-bit DSD word). Not audio;
 *   it must reach the DAC unmodified. [sampleRate] is the DSD rate / 32.
 */
data class PcmFormat(
    val encoding: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val bytesPerFrame: Int,
    val isDop: Boolean = false,
    val isNativeDsd: Boolean = false,
)

/**
 * Receives decoded audio just before it is handed to the output (speaker, headphones or USB DAC).
 *
 * THREADING: every callback runs on the player's playback thread. Do not block, allocate,
 * log per buffer, or do I/O here. Copy what you need into a preallocated ring buffer and process
 * it on your own thread.
 *
 * The buffer is read-only by contract: you may move its position/limit while reading, the host
 * restores them after your call returns. Do not keep a reference to it after returning; the
 * decoder reuses it.
 */
interface AudioCapturePlugin {
    /** Short name shown in the UI. */
    val name: String

    /** Called when a new stream starts or the format changes. */
    fun onConfigure(format: PcmFormat) {}

    /**
     * Called once per decoded buffer.
     * @param buffer interleaved little-endian PCM in [PcmFormat.encoding], from position to limit.
     *   Call `buffer.order(ByteOrder.LITTLE_ENDIAN)` before typed reads (getShort/getFloat);
     *   the host restores the original order afterwards.
     * @param presentationTimeUs media time of the first frame in the buffer.
     */
    fun onBuffer(buffer: ByteBuffer, presentationTimeUs: Long)

    /** Called on seek or track change: drop any buffered audio. */
    fun onFlush() {}

    /** Called when the player is released. */
    fun onRelease() {}
}
