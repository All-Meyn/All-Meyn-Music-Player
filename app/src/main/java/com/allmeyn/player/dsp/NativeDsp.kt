package com.allmeyn.player.dsp

import java.nio.ByteBuffer

/** JNI to the native DSP chain (cpp/dsp). Handles are opaque; only 0 means "none". */
object NativeDsp {
    init {
        System.loadLibrary("allmeyn_usb")
    }

    /** Number of floats [process] writes into its meters array. */
    const val METER_COUNT = 12

    @JvmStatic external fun create(sampleRate: Int, channels: Int): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun setParams(handle: Long, values: FloatArray, order: IntArray)

    /** The current song's source for Air (see [AirSource]). */
    @JvmStatic external fun setSource(handle: Long, lossy: Boolean, priorCutoffHz: Float)

    /** Interleaved float frames at byte [offset] of a direct [buffer], in place. False if not direct. */
    @JvmStatic external fun process(handle: Long, buffer: ByteBuffer, offset: Int, frames: Int, meters: FloatArray?): Boolean
}
