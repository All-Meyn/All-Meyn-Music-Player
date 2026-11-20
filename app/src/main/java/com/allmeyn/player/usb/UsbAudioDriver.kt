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
import androidx.media3.common.C
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The custom USB audio driver (bypasses Android's mixer for true bit-perfect output).
 *
 * - [enable]/[disable]: open/close a session. While enabled the DAC belongs to this app and
 *   Android can't use it; [UsbAudioOutputProvider] then routes Media3's audio here.
 * - [configureStream]: called by [UsbAudioOutput] on the playback thread for each new output.
 * - [probe], [startTone]: diagnostics.
 *
 * Session open/close runs on one worker thread; results are posted to the main thread.
 */
class UsbAudioDriver(private val context: Context) {

    /** Called on the main thread when the driver turns on (before the DAC is taken) or off. */
    fun interface ModeListener {
        fun onDriverModeChanged(active: Boolean)
    }

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "usb-driver") }
    private val modeListeners = CopyOnWriteArrayList<ModeListener>()

    // Worker thread only.
    private var connection: UsbDeviceConnection? = null

    @Volatile private var descriptor: UacDescriptorParser.Result? = null

    /** True while a session is open and the DAC is still attached. */
    val isActive: Boolean
        get() = descriptor != null && NativeUsb.isAlive()

    /** Bumped whenever the native stream is reconfigured; outputs holding an old token are stale. */
    private val streamToken = AtomicInteger(0)

    fun currentToken(): Int = streamToken.get()

    fun addModeListener(l: ModeListener) = modeListeners.add(l)
    fun removeModeListener(l: ModeListener) = modeListeners.remove(l)

    fun findAudioDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
    }

    // ---- session ------------------------------------------------------------------------------

    /** Takes the DAC from Android. Call on the main thread. */
    fun enable(onResult: (String) -> Unit) {
        // Tell listeners first so e.g. "audio becoming noisy" handling is off before the DAC vanishes.
        modeListeners.forEach { it.onDriverModeChanged(true) }
        withDevice({ msg ->
            if (!isActive) modeListeners.forEach { it.onDriverModeChanged(false) }
            onResult(msg)
        }) { device -> openSession(device) }
    }

    /** Hands the DAC back to Android. */
    fun disable(onResult: (String) -> Unit) {
        worker.execute {
            closeSession()
            main.post {
                modeListeners.forEach { it.onDriverModeChanged(false) }
                onResult("Driver off · DAC handed back to Android")
            }
        }
    }

    private fun openSession(device: UsbDevice): String {
        closeSession()
        val conn = usbManager.openDevice(device) ?: return "Could not open ${device.productName}"
        val desc = UacDescriptorParser.parse(conn.rawDescriptors)
        val alt = desc.playbackAlts.firstOrNull { it.isPcm && it.dataEndpoint != null }
        val clock = desc.clockSources.firstOrNull()
        val problem = when {
            desc.uacVersion != 2 -> "Only USB Audio Class 2 DACs are supported so far"
            alt == null -> "No PCM playback mode found"
            clock == null -> "No clock source found"
            else -> null
        }
        if (problem != null) {
            conn.close()
            return problem
        }
        val data = alt!!.dataEndpoint!!
        val fb = alt.feedbackEndpoint
        val result = NativeUsb.open(
            fd = conn.fileDescriptor,
            controlIf = desc.controlInterface,
            clockId = clock!!.id,
            streamIf = alt.interfaceNumber,
            epOut = data.address,
            epFeedback = fb?.address ?: 0,
            feedbackMaxPacket = fb?.maxPacketSize ?: 0,
            epInterval = data.interval,
        )
        if (result != "OK") {
            conn.close()
            return result
        }
        connection = conn
        descriptor = desc
        streamToken.incrementAndGet()
        return "Driver on · ${device.productName} belongs to All-Meyn"
    }

    private fun closeSession() {
        descriptor = null
        streamToken.incrementAndGet()
        NativeUsb.close() // releases interfaces (Android's driver re-attaches) before the fd closes
        connection?.close()
        connection = null
    }

    // ---- streams (playback thread) ------------------------------------------------------------

    /** Result of [configureStream]: the token for the new stream, or null with [error] set. */
    data class StreamResult(val token: Int?, val error: String?)

    /**
     * Configures the DAC for Media3 PCM in [pcmEncoding] at [sampleRate] with [channels] channels.
     * 16-bit audio uses the DAC's 16-bit mode (bit-perfect copy); everything else uses its deepest
     * PCM mode with exact conversion.
     */
    fun configureStream(pcmEncoding: Int, sampleRate: Int, channels: Int): StreamResult {
        val desc = descriptor ?: return StreamResult(null, "driver off")
        val inFormat = inFormatFor(pcmEncoding) ?: return StreamResult(null, "encoding $pcmEncoding")
        val pcm = desc.playbackAlts.filter { it.isPcm && it.dataEndpoint != null && it.channels >= 2 }
        val alt = (if (pcmEncoding == C.ENCODING_PCM_16BIT) pcm.firstOrNull { it.bitResolution == 16 } else null)
            ?: pcm.maxByOrNull { it.bitResolution }
            ?: return StreamResult(null, "no stereo PCM mode")
        val r = NativeUsb.configure(
            alt = alt.altSetting,
            maxPacket = alt.dataEndpoint!!.maxPacketSize,
            subslotBytes = alt.subslotBytes,
            outChannels = alt.channels,
            rate = sampleRate,
            inFormat = inFormat,
            inChannels = channels,
            ringMs = RING_MS,
        )
        return if (r.startsWith("OK")) StreamResult(streamToken.incrementAndGet(), null) else StreamResult(null, r)
    }

    fun supportsPcm(pcmEncoding: Int, channels: Int) = inFormatFor(pcmEncoding) != null && channels in 1..2

    private fun inFormatFor(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> NativeUsb.IN_S16
        C.ENCODING_PCM_24BIT -> NativeUsb.IN_S24
        C.ENCODING_PCM_32BIT -> NativeUsb.IN_S32
        C.ENCODING_PCM_FLOAT -> NativeUsb.IN_FLOAT
        else -> null
    }

    // ---- diagnostics --------------------------------------------------------------------------

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

    /** Plays a 440 Hz tone through the open session (pause music first). */
    fun startTone(rate: Int, bits: Int, onResult: (String) -> Unit) {
        worker.execute {
            val desc = descriptor
            val msg = if (desc == null) {
                "Turn the USB driver on first"
            } else {
                val pcm = desc.playbackAlts.filter { it.isPcm && it.dataEndpoint != null }
                val alt = pcm.firstOrNull { it.bitResolution == bits } ?: pcm.maxByOrNull { it.bitResolution }
                if (alt == null) {
                    "No PCM mode"
                } else {
                    streamToken.incrementAndGet() // any Media3 output must reconfigure afterwards
                    NativeUsb.startTone(alt.altSetting, alt.dataEndpoint!!.maxPacketSize, alt.subslotBytes, alt.channels, rate, 440.0)
                }
            }
            main.post { onResult(msg) }
        }
    }

    fun status(onResult: (String) -> Unit) {
        worker.execute {
            val s = NativeUsb.status()
            main.post { onResult(s) }
        }
    }

    // ---- permission ---------------------------------------------------------------------------

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

    companion object {
        /** Queue depth between Media3 and the DAC. */
        const val RING_MS = 250
    }
}
