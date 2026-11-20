package com.allmeyn.player.usb

/** JNI entry points into liballmeyn_usb.so (libusb + the USB audio driver). */
object NativeUsb {
    init {
        System.loadLibrary("allmeyn_usb")
    }

    /** Wraps [fd] with libusb and returns a human-readable summary. */
    @JvmStatic
    external fun probe(fd: Int): String

    /**
     * Claims the DAC (detaching Android's driver), sets [rate] on the UAC2 clock and streams a
     * sine tone. Returns "Streaming …" on success or "ERROR …".
     * [fd] must stay open until [stop] returns.
     */
    @JvmStatic
    external fun startTone(
        fd: Int, controlIf: Int, clockId: Int, streamIf: Int, alt: Int,
        epOut: Int, epFeedback: Int, maxPacket: Int, feedbackMaxPacket: Int, epInterval: Int,
        subslotBytes: Int, channels: Int, rate: Int, toneHz: Double,
    ): String

    /** Stops streaming and hands the DAC back to Android. Safe to call when stopped. */
    @JvmStatic
    external fun stop()

    @JvmStatic
    external fun status(): String
}
