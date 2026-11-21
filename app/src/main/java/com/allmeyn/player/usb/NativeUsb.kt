package com.allmeyn.player.usb

import java.nio.ByteBuffer

/**
 * JNI entry points into liballmeyn_usb.so (libusb + the USB audio engine).
 *
 * The engine has one *session* (DAC claimed from Android) and, inside it, one *stream* (a sample
 * rate + alt setting). Calls are thread-safe.
 */
object NativeUsb {
    init {
        System.loadLibrary("allmeyn_usb")
    }

    // Input formats for [configure]; must match usb_audio_core.h.
    const val IN_S16 = 1
    const val IN_S24 = 2
    const val IN_S32 = 3
    const val IN_FLOAT = 4
    const val IN_DSD_NATIVE = 5 // raw DSD as 4 x 16-bit per frame, copied into 2 x 32-bit slots

    /** Read-only probe: wraps [fd] with libusb and returns a summary. */
    @JvmStatic external fun probe(fd: Int): String

    /** Claims the DAC (detaching Android's driver). [fd] must stay open until [close]. "OK" or "ERROR …". */
    @JvmStatic external fun open(
        fd: Int, controlIf: Int, clockId: Int, streamIf: Int,
        epOut: Int, epFeedback: Int, feedbackMaxPacket: Int, epInterval: Int,
    ): String

    /**
     * Starts (or reuses) a stream at [rate] on [alt] and resets to paused, empty, position 0.
     * Returns "OK <rate the DAC confirmed>" or "ERROR …".
     */
    @JvmStatic external fun configure(
        alt: Int, maxPacket: Int, subslotBytes: Int, outChannels: Int, rate: Int,
        inFormat: Int, inChannels: Int, ringMs: Int,
    ): String

    /** Converts and queues PCM from a direct buffer. Returns input bytes consumed, or -1 if the stream is gone. */
    @JvmStatic external fun write(buffer: ByteBuffer, offset: Int, length: Int): Int

    @JvmStatic external fun play()
    @JvmStatic external fun pause()

    /** Drops queued audio and resets the played-frame counter to 0 (stays claimed and streaming). */
    @JvmStatic external fun flush()

    /** The player is playing out the end of the queue (the ring running dry isn't a dropout). */
    @JvmStatic external fun endOfStream()

    /** Frames of queued audio that have been sent to the DAC since the last flush/configure. */
    @JvmStatic external fun playedFrames(): Long

    @JvmStatic external fun ringFrames(): Int

    /** False when no session is open or the DAC was unplugged. */
    @JvmStatic external fun isAlive(): Boolean

    /** Test tone through the same session. Opens a stream like [configure]. */
    @JvmStatic external fun startTone(
        alt: Int, maxPacket: Int, subslotBytes: Int, outChannels: Int, rate: Int, toneHz: Double,
    ): String

    /** Stops streaming and hands the DAC back to Android. Safe to call when closed. */
    @JvmStatic external fun close()

    @JvmStatic external fun status(): String
}
