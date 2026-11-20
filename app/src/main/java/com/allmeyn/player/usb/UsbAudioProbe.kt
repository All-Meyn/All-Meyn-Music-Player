package com.allmeyn.player.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Entry point for the custom USB audio driver.
 *
 * - [probe]: read-only descriptor report (stage 1). Android keeps the DAC.
 * - [startTone]/[stop]: stage 2 test. Takes the DAC from Android, streams a tone, hands it back.
 *
 * All USB work runs on one background thread; callbacks are posted to the main thread.
 */
class UsbAudioProbe(private val context: Context) {

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "usb-driver") }

    /** Kept open while the driver streams: libusb uses its file descriptor. Worker thread only. */
    private var streamingConnection: UsbDeviceConnection? = null

    fun findAudioDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
    }

    fun probe(onResult: (String) -> Unit) = withDevice(onResult) { device ->
        val conn = usbManager.openDevice(device) ?: return@withDevice "Could not open ${device.productName}"
        try {
            val parsed = UacDescriptorParser.describe(UacDescriptorParser.parse(conn.rawDescriptors))
            val native = NativeUsb.probe(conn.fileDescriptor)
            "${device.manufacturerName} ${device.productName}\n$native\n\n$parsed"
        } finally {
            conn.close()
        }
    }

    /**
     * Streams a [toneHz] sine at [rate] Hz using the PCM alt setting with [bits]-bit samples
     * (falls back to the deepest PCM alt if there's no exact match).
     */
    fun startTone(rate: Int, bits: Int, toneHz: Double = 440.0, onResult: (String) -> Unit) =
        withDevice(onResult) { device ->
            stopOnWorker()
            val conn = usbManager.openDevice(device) ?: return@withDevice "Could not open ${device.productName}"
            val desc = UacDescriptorParser.parse(conn.rawDescriptors)
            val pcmAlts = desc.playbackAlts.filter { it.isPcm && it.dataEndpoint != null }
            val alt = pcmAlts.firstOrNull { it.bitResolution == bits }
                ?: pcmAlts.maxByOrNull { it.bitResolution }
            val clock = desc.clockSources.firstOrNull()
            when {
                desc.uacVersion != 2 -> { conn.close(); "Only USB Audio Class 2 is supported so far" }
                alt == null -> { conn.close(); "No PCM playback alt setting found" }
                clock == null -> { conn.close(); "No clock source found" }
                else -> {
                    val data = alt.dataEndpoint!!
                    val fb = alt.feedbackEndpoint
                    val result = NativeUsb.startTone(
                        fd = conn.fileDescriptor,
                        controlIf = desc.controlInterface,
                        clockId = clock.id,
                        streamIf = alt.interfaceNumber,
                        alt = alt.altSetting,
                        epOut = data.address,
                        epFeedback = fb?.address ?: 0,
                        maxPacket = data.maxPacketSize,
                        feedbackMaxPacket = fb?.maxPacketSize ?: 0,
                        epInterval = data.interval,
                        subslotBytes = alt.subslotBytes,
                        channels = alt.channels,
                        rate = rate,
                        toneHz = toneHz,
                    )
                    if (result.startsWith("ERROR")) conn.close() else streamingConnection = conn
                    "$result\nalt ${alt.altSetting} · ${alt.bitResolution}-bit"
                }
            }
        }

    fun stop(onResult: (String) -> Unit) {
        worker.execute {
            stopOnWorker()
            main.post { onResult("Driver stopped · DAC handed back to Android") }
        }
    }

    fun status(onResult: (String) -> Unit) {
        worker.execute {
            val s = if (streamingConnection == null) "Driver stopped" else NativeUsb.status()
            main.post { onResult(s) }
        }
    }

    private fun stopOnWorker() {
        val conn = streamingConnection ?: return
        NativeUsb.stop() // releases interfaces (Android's driver re-attaches) before we close the fd
        conn.close()
        streamingConnection = null
    }

    /** Finds the DAC, gets permission if needed, then runs [block] on the worker thread. */
    private fun withDevice(onResult: (String) -> Unit, block: (UsbDevice) -> String) {
        val device = findAudioDevice() ?: return onResult("No USB audio device attached")
        val run = {
            worker.execute {
                val text = try {
                    block(device)
                } catch (t: Throwable) {
                    "Failed: ${t.javaClass.simpleName}: ${t.message}"
                }
                main.post { onResult(text) }
            }
        }
        if (usbManager.hasPermission(device)) {
            run()
            return
        }
        val action = "${context.packageName}.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                ctx.unregisterReceiver(this)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) run()
                else onResult("USB permission denied")
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
}
