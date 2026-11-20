package com.allmeyn.player.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Stage 1 of the custom USB audio driver: finds the USB audio device, asks the user for access,
 * then reads its descriptors with both Android's API and libusb. Read-only: it doesn't claim any
 * interface, so Android's own USB audio keeps working.
 */
class UsbAudioProbe(private val context: Context) {

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    fun findAudioDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
    }

    /** [onResult] is called on the main thread. */
    fun probe(onResult: (String) -> Unit) {
        val device = findAudioDevice() ?: return onResult("No USB audio device attached")
        if (usbManager.hasPermission(device)) {
            runProbe(device, onResult)
            return
        }
        val action = "${context.packageName}.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                ctx.unregisterReceiver(this)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    runProbe(device, onResult)
                } else {
                    onResult("USB permission denied")
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Mutable so the system can add EXTRA_PERMISSION_GRANTED; explicit package keeps it safe.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
        usbManager.requestPermission(device, pi)
    }

    private fun runProbe(device: UsbDevice, onResult: (String) -> Unit) {
        Thread({
            val text = try {
                val conn = usbManager.openDevice(device)
                if (conn == null) {
                    "Could not open ${device.productName}"
                } else {
                    try {
                        val raw = conn.rawDescriptors
                        val parsed = UacDescriptorParser.describe(UacDescriptorParser.parse(raw))
                        val native = NativeUsb.probe(conn.fileDescriptor)
                        "${device.manufacturerName} ${device.productName}\n$native\n\n$parsed"
                    } finally {
                        conn.close()
                    }
                }
            } catch (t: Throwable) {
                "Probe failed: ${t.javaClass.simpleName}: ${t.message}"
            }
            main.post { onResult(text) }
        }, "usb-probe").start()
    }
}
