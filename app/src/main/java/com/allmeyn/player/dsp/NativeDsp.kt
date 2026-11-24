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
    /**
     * The convolution EQ's filter, built for the chain's rate and handed over (all null = none):
     * an impulse response (left, optional right, at irRate) or GraphicEQ points, plus a gain.
     * Takes a few ms: call between buffers, not inside one.
     */
    @JvmStatic external fun setConvolution(
        handle: Long, irLeft: FloatArray?, irRight: FloatArray?, irRate: Float,
        graphicHz: FloatArray?, graphicDb: FloatArray?, gainDb: Float,
    )

    @JvmStatic external fun setSource(handle: Long, lossy: Boolean, priorCutoffHz: Float)

    /** Interleaved float frames at byte [offset] of a direct [buffer], in place. False if not direct. */
    @JvmStatic external fun process(handle: Long, buffer: ByteBuffer, offset: Int, frames: Int, meters: FloatArray?): Boolean
}
