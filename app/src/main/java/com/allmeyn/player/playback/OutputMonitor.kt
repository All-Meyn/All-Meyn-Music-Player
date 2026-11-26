package com.allmeyn.player.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.allmeyn.player.dsp.DspParams
import com.allmeyn.player.dsp.DspSettings
import com.allmeyn.player.dsp.OutputEq
import com.allmeyn.player.dsp.OutputEqStore
import com.allmeyn.player.dsp.OutputId
import com.allmeyn.player.usb.UsbAudioDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.io.File

/**
 * Which output the music goes to (the USB driver's DAC, else what Android routes media to: a USB DAC,
 * Bluetooth, wired headphones, the speaker), and the headphone EQ that follows it (OutputEqStore,
 * filesDir/output_eqs.txt). App-scoped, main thread.
 *
 * Android doesn't say which output a stream uses before it plays, so this follows Android's usual
 * choice: USB, then Bluetooth, then wired, then the speaker.
 */
class OutputMonitor(private val context: Context, private val usbDriver: UsbAudioDriver) {
    val store = OutputEqStore(File(context.filesDir, "output_eqs.txt"))

    private val _current = MutableStateFlow<OutputId?>(null)
    val current: StateFlow<OutputId?> = _current.asStateFlow()

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    // EQ changes are kept for the output in use.
    private val dspListener: (DspParams, DspParams) -> Unit = { old, new ->
        if (OutputEq.of(old) != OutputEq.of(new)) _current.value?.let { store.remember(it, new) }
    }

    fun start() {
        DspSettings.addListener(dspListener)
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        scope.launch {
            usbDriver.state.distinctUntilChangedBy { it.active to it.deviceName }.collect { refresh() }
        }
        refresh()
    }

    /** Remembering per output switched on/off (on: the output in use keeps the current EQ). */
    fun setEnabled(on: Boolean) {
        store.setEnabled(on)
        _current.value?.let { store.remember(it, DspSettings.live) }
    }

    private fun refresh() {
        val id = detect()
        if (id == _current.value) return
        _current.value = id
        DspSettings.update(context) { store.switchTo(id, it) }
    }

    private fun detect(): OutputId {
        val driver = usbDriver.state.value
        if (driver.active) return usb(driver.deviceName ?: usbDriver.findAudioDevice()?.productName)
        val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }?.let {
            // The USB device's own name, so the driver and Android's mixer share one EQ for a DAC.
            return usb(usbDriver.findAudioDevice()?.productName ?: it.productName?.toString())
        }
        val bt = mutableSetOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) bt += setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
        outs.firstOrNull { it.type in bt }?.let {
            val name = it.productName?.toString()?.takeIf { n -> n.isNotBlank() } ?: "Bluetooth"
            return OutputId("bt:$name", "Bluetooth: $name")
        }
        if (outs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }) {
            return OutputId("wired", "Wired headphones")
        }
        return OutputId("speaker", "Phone speaker")
    }

    private fun usb(name: String?): OutputId {
        val n = name?.takeIf { it.isNotBlank() } ?: "USB DAC"
        return OutputId("usb:$n", "USB: $n")
    }
}
