package com.allmeyn.player.usb

/** JNI entry points into liballmeyn_usb.so (libusb + the USB audio driver). */
object NativeUsb {
    init {
        System.loadLibrary("allmeyn_usb")
    }

    /** Wraps [fd] with libusb and returns a human-readable summary. */
    @JvmStatic
    external fun probe(fd: Int): String
}
