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

    /**
     * Opens [fd] (duplicated natively, so the caller may close it). [dop]: output DSD as DoP.
     * Returns a handle, 0 if the file isn't a format we decode, or -1 on error ([lastError]).
     */
    @JvmStatic external fun open(fd: Int, dop: Boolean): Long

    @JvmStatic external fun lastError(): String

    /** [sampleRate, channels, format, totalFrames, isDsd, isDop] */
    @JvmStatic external fun info(handle: Long): LongArray

    @JvmStatic external fun describe(handle: Long): String

    @JvmStatic external fun seek(handle: Long, frame: Long): Int

    /** Whole frames into buf[off, off+len). Bytes written, 0 at end, -1 on error. */
    @JvmStatic external fun read(handle: Long, buf: ByteArray, off: Int, len: Int): Int

    @JvmStatic external fun close(handle: Long)
}
