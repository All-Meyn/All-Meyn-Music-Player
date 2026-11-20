package com.allmeyn.player.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log

/**
 * DAC hardware volume and mute through a UAC2 Feature Unit, using control transfers on the
 * driver's open connection (the control interface is already claimed by the session).
 * The audio samples are never touched, so playback stays bit-perfect.
 *
 * Call from the driver's worker thread only.
 */
class UsbDacVolume private constructor(
    private val conn: UsbDeviceConnection,
    private val controlIf: Int,
    private val target: UacDescriptorParser.VolumeTarget,
    val scale: VolumeScale,
) {
    val hasMute get() = target.muteChannels.isNotEmpty()

    /** Current step, read from the DAC (first volume channel). */
    fun readStep(): Int? = getCur(CS_VOLUME, target.channels.first())?.let(scale::stepForRaw)

    fun readMuted(): Boolean = hasMute && (getCur1(CS_MUTE, target.muteChannels.first()) ?: 0) != 0

    /** Sets every volume channel to [step]. Returns false if the DAC rejected it. */
    fun setStep(step: Int): Boolean {
        val raw = scale.rawForStep(step)
        return target.channels.all { ch -> setCur(CS_VOLUME, ch, raw) }
    }

    fun setMuted(muted: Boolean): Boolean =
        hasMute && target.muteChannels.all { ch -> setCur1(CS_MUTE, ch, if (muted) 1 else 0) }

    // ---- UAC2 class requests to the feature unit -----------------------------------------------

    private fun wIndex() = (target.unitId shl 8) or controlIf

    private fun getCur(cs: Int, ch: Int): Int? {
        val b = ByteArray(2)
        val n = conn.controlTransfer(IN, REQ_CUR, (cs shl 8) or ch, wIndex(), b, 2, TIMEOUT)
        return if (n == 2) s16(b, 0) else null
    }

    private fun getCur1(cs: Int, ch: Int): Int? {
        val b = ByteArray(1)
        val n = conn.controlTransfer(IN, REQ_CUR, (cs shl 8) or ch, wIndex(), b, 1, TIMEOUT)
        return if (n == 1) b[0].toInt() and 0xFF else null
    }

    private fun setCur(cs: Int, ch: Int, value: Int): Boolean {
        val b = byteArrayOf(value.toByte(), (value shr 8).toByte())
        val n = conn.controlTransfer(OUT, REQ_CUR, (cs shl 8) or ch, wIndex(), b, 2, TIMEOUT)
        if (n != 2) Log.w(TAG, "SET CUR volume ch $ch failed ($n)")
        return n == 2
    }

    private fun setCur1(cs: Int, ch: Int, value: Int): Boolean {
        val b = byteArrayOf(value.toByte())
        val n = conn.controlTransfer(OUT, REQ_CUR, (cs shl 8) or ch, wIndex(), b, 1, TIMEOUT)
        return n == 1
    }

    companion object {
        private const val TAG = "UsbDacVolume"
        private const val IN = 0xA1  // class, interface, device-to-host
        private const val OUT = 0x21 // class, interface, host-to-device
        private const val REQ_CUR = 0x01
        private const val REQ_RANGE = 0x02
        private const val CS_MUTE = 0x01
        private const val CS_VOLUME = 0x02
        private const val TIMEOUT = 1000

        private fun s16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toInt()

        /** Reads the volume range from the DAC; null if it has no usable hardware volume. */
        fun open(conn: UsbDeviceConnection, desc: UacDescriptorParser.Result): UsbDacVolume? {
            val target = desc.playbackVolume() ?: return null
            val ch = target.channels.first()
            // GET RANGE: wNumSubRanges (2) then MIN, MAX, RES (2 bytes each) per subrange.
            val b = ByteArray(2 + 6 * 4)
            val n = conn.controlTransfer(
                IN, REQ_RANGE, (CS_VOLUME shl 8) or ch, (target.unitId shl 8) or desc.controlInterface, b, b.size, TIMEOUT,
            )
            if (n < 8) {
                Log.w(TAG, "GET RANGE volume failed ($n)")
                return null
            }
            val subRanges = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
            if (subRanges < 1) return null
            // Use the overall span across sub-ranges; the finest resolution among them.
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            var res = Int.MAX_VALUE
            for (k in 0 until minOf(subRanges, (n - 2) / 6)) {
                val o = 2 + k * 6
                min = minOf(min, s16(b, o))
                max = maxOf(max, s16(b, o + 2))
                res = minOf(res, s16(b, o + 4).coerceAtLeast(1))
            }
            if (max <= min) return null
            return UsbDacVolume(conn, desc.controlInterface, target, VolumeScale(min, max, res))
        }
    }
}
