package com.allmeyn.player.decode

/** Decoder output sample formats; must match decoder.h. (No native code: usable in unit tests.) */
object DecodedFormat {
    const val S16 = 1
    const val S24 = 2
    const val S32 = 3
    const val F32 = 4

    fun sampleBytes(format: Int) = when (format) {
        S16 -> 2
        S24 -> 3
        else -> 4
    }
}

/** JNI entry points for the file decoders in liballmeyn_usb.so (DSF, DFF, APE, WavPack). */
object NativeDecoder {
    init {
        System.loadLibrary("allmeyn_usb")
    }

    const val NOT_OURS = 0L
    const val OPEN_ERROR = -1L

    /**
     * Opens [fd] (duplicated natively, so the caller may close it). DSD goes out as native DSD
     * when its rate is at most [nativeMaxDsdRate] (0 = never), else as DoP if [dop], else as PCM.
     * Returns a handle, [NOT_OURS] (0) if the file isn't a format we decode, or [OPEN_ERROR] (-1)
     * on error ([lastError]). A handle is a native pointer and may be NEGATIVE: arm64 Android tags
     * the top byte of heap pointers. Never test it with `< 0` / `> 0`.
     */
    @JvmStatic external fun open(fd: Int, dop: Boolean, nativeMaxDsdRate: Int): Long

    @JvmStatic external fun lastError(): String

    /** [sampleRate, channels, format, totalFrames, isDsd, isDop, isNative] */
    @JvmStatic external fun info(handle: Long): LongArray

    @JvmStatic external fun describe(handle: Long): String

    @JvmStatic external fun seek(handle: Long, frame: Long): Int

    /** Whole frames into buf[off, off+len). Bytes written, 0 at end, -1 on error. */
    @JvmStatic external fun read(handle: Long, buf: ByteArray, off: Int, len: Int): Int

    @JvmStatic external fun close(handle: Long)
}
