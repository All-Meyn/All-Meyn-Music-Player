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
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The custom USB audio driver (bypasses Android's mixer for true bit-perfect output).
 *
 * - [enable]/[disable]: open/close a session. While enabled the DAC belongs to this app and
 *   Android can't use it; [UsbAudioOutputProvider] then routes Media3's audio here. The choice is
 *   remembered, and the driver turns itself back on when the DAC is plugged in again.
 * - Unplugging the DAC closes the session and tells listeners ([Reason.DISCONNECTED]).
 * - DAC hardware volume through the UAC2 feature unit: [setVolumeStep], [adjustVolume], [setMuted].
 * - [configureStream]: called by [UsbAudioOutput] on the playback thread for each new output.
 * - [probe], [startTone]: diagnostics.
 *
 * Session work runs on one worker thread; listeners and [state] update on the main thread.
 */
class UsbAudioDriver(private val context: Context) {

    enum class Reason { ENABLED, DISABLED, DISCONNECTED, FAILED }

    /** DAC hardware volume, in steps 0..[maxStep] (see [VolumeScale]). */
    data class VolumeState(val step: Int, val maxStep: Int, val db: Float, val muted: Boolean, val hasMute: Boolean)

    data class State(
        val active: Boolean = false,
        val busy: Boolean = false,
        val deviceName: String? = null,
        val message: String = "",
        val volume: VolumeState? = null,
    )

    /** Main-thread callbacks. */
    interface Listener {
        /** Right before the DAC is taken from Android (so "audio becoming noisy" can be ignored). */
        fun beforeTakeover() {}
        fun onDriverStateChanged(active: Boolean, reason: Reason) {}
        fun onVolumeChanged(volume: VolumeState?) {}
    }

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val prefs = context.getSharedPreferences("usb_driver", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "usb-driver") }
    private val listeners = CopyOnWriteArrayList<Listener>()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Worker thread only.
    private var connection: UsbDeviceConnection? = null
    private var sessionDeviceName: String? = null
    private var volume: UsbDacVolume? = null

    @Volatile private var descriptor: UacDescriptorParser.Result? = null

    /** True while a session is open and the DAC is still attached. */
    val isActive: Boolean
        get() = descriptor != null && NativeUsb.isAlive()

    /** Whether the user wants the driver on whenever the DAC is connected. */
    var enabledByUser: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        private set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Bumped whenever the native stream is reconfigured; outputs holding an old token are stale. */
    private val streamToken = AtomicInteger(0)

    fun currentToken(): Int = streamToken.get()

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> worker.execute {
                    if (device != null && device.deviceName == sessionDeviceName) onDisconnected()
                }
                // Permission may only be granted once the "open with" dialog is answered: retry.
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    main.postDelayed({ maybeAutoEnable() }, 800)
                    main.postDelayed({ maybeAutoEnable() }, 3000)
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // System broadcasts still reach a non-exported receiver.
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        main.post { maybeAutoEnable() }
    }

    fun findAudioDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
    }

    // ---- session ------------------------------------------------------------------------------

    /**
     * Turns the driver on if the user left it on, the DAC is attached and we already have USB
     * permission (never prompts). Safe to call any time on the main thread.
     */
    fun maybeAutoEnable() {
        if (!enabledByUser || isActive || _state.value.busy) return
        val device = findAudioDevice() ?: return
        if (!usbManager.hasPermission(device)) return
        enable { Log.i(TAG, "Auto-enable: $it") }
    }

    /** Takes the DAC from Android. Call on the main thread. */
    fun enable(onResult: (String) -> Unit) {
        _state.value = _state.value.copy(busy = true, message = "Taking over the DAC…")
        listeners.forEach { it.beforeTakeover() }
        withDevice({ msg ->
            val active = isActive
            if (active) enabledByUser = true
            _state.value = _state.value.copy(
                active = active, busy = false, message = msg,
                deviceName = if (active) _state.value.deviceName else null,
                volume = if (active) _state.value.volume else null,
            )
            listeners.forEach { it.onDriverStateChanged(active, if (active) Reason.ENABLED else Reason.FAILED) }
            onResult(msg)
        }) { device -> openSession(device) }
    }

    /** Hands the DAC back to Android and stops auto-enabling. */
    fun disable(onResult: (String) -> Unit) {
        enabledByUser = false
        _state.value = _state.value.copy(busy = true, message = "Handing the DAC back…")
        worker.execute {
            closeSession()
            val msg = "Driver off · DAC handed back to Android"
            main.post {
                _state.value = State(message = msg)
                listeners.forEach { it.onVolumeChanged(null) }
                listeners.forEach { it.onDriverStateChanged(false, Reason.DISABLED) }
                onResult(msg)
            }
        }
    }

    /** Worker thread: the DAC was unplugged. */
    private fun onDisconnected() {
        if (descriptor == null) return
        closeSession()
        main.post {
            _state.value = State(message = "DAC disconnected · playback paused")
            listeners.forEach { it.onVolumeChanged(null) }
            listeners.forEach { it.onDriverStateChanged(false, Reason.DISCONNECTED) }
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
        sessionDeviceName = device.deviceName
        descriptor = desc
        streamToken.incrementAndGet()

        // Hardware volume: start from whatever the DAC is set to now (never jump louder).
        volume = UsbDacVolume.open(conn, desc)
        val vs = readVolumeState()
        val name = device.productName ?: "USB DAC"
        main.post {
            _state.value = _state.value.copy(deviceName = name, volume = vs)
            listeners.forEach { it.onVolumeChanged(vs) }
        }
        return "Driver on · $name belongs to All-Meyn" +
            if (vs == null) " · no hardware volume, use the DAC's buttons" else ""
    }

    private fun closeSession() {
        descriptor = null
        volume = null
        sessionDeviceName = null
        streamToken.incrementAndGet()
        NativeUsb.close() // releases interfaces (Android's driver re-attaches) before the fd closes
        connection?.close()
        connection = null
    }

    // ---- hardware volume ----------------------------------------------------------------------

    /** Worker thread. */
    private fun readVolumeState(): VolumeState? {
        val v = volume ?: return null
        val step = v.readStep() ?: return null
        return VolumeState(step, v.scale.maxStep, VolumeScale.rawToDb(v.scale.rawForStep(step)), v.readMuted(), v.hasMute)
    }

    fun setVolumeStep(step: Int) = volumeOp { v ->
        v.setStep(step.coerceIn(0, v.scale.maxStep))
        // Step 0 is "off": also mute when the DAC can.
        if (v.hasMute) v.setMuted(step <= 0)
    }

    fun adjustVolume(delta: Int) = volumeOp { v ->
        val cur = v.readStep() ?: return@volumeOp
        val next = (cur + delta).coerceIn(0, v.scale.maxStep)
        v.setStep(next)
        if (v.hasMute) v.setMuted(next <= 0)
    }

    fun setMuted(muted: Boolean) = volumeOp { v -> v.setMuted(muted) }

    private fun volumeOp(op: (UsbDacVolume) -> Unit) {
        worker.execute {
            val v = volume ?: return@execute
            op(v)
            val vs = readVolumeState()
            main.post {
                _state.value = _state.value.copy(volume = vs)
                listeners.forEach { it.onVolumeChanged(vs) }
            }
        }
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
        private const val TAG = "UsbAudioDriver"
        private const val KEY_ENABLED = "enabled"

        /** Queue depth between Media3 and the DAC. */
        const val RING_MS = 250
    }
}
