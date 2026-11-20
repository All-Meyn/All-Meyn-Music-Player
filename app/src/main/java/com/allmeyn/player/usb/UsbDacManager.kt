package com.allmeyn.player.usb

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.allmeyn.player.plugin.PcmFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI shows about the current output. */
data class DacStatus(
    val deviceName: String? = null,
    val bitPerfect: Boolean = false,
    val detail: String = "No USB DAC connected",
)

/**
 * Detects USB DACs and, on Android 14+ (API 34), asks the system for a bit-perfect mixer that
 * matches each track's sample rate and output encoding. On older Android, or when the DAC doesn't
 * offer a matching bit-perfect mode, audio plays through the normal (resampling) mixer.
 *
 * [onPcmConfigured] is called on the playback thread; device callbacks arrive on the main thread.
 *
 * @param floatOutputEnabled must match the value the AudioSink was built with, because it decides
 *   whether high-resolution PCM reaches the AudioTrack as float or 16-bit.
 */
@UnstableApi
class UsbDacManager(context: Context, private val floatOutputEnabled: Boolean) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _status = MutableStateFlow(DacStatus())
    val status: StateFlow<DacStatus> = _status.asStateFlow()

    @Volatile private var usbDevice: AudioDeviceInfo? = null
    @Volatile private var lastFormat: PcmFormat? = null
    private var appliedKey: String? = null // guarded by this

    private val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshDevice()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshDevice()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        refreshDevice()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        usbDevice?.let { clear(it) }
    }

    /** Called by CaptureAudioSink before the AudioTrack for [format] is created. */
    fun onPcmConfigured(format: PcmFormat) {
        lastFormat = format
        val device = usbDevice ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            applyBitPerfect(device, format)
        }
    }

    private fun refreshDevice() {
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val previous = usbDevice
        usbDevice = device
        when {
            device == null -> {
                synchronized(this) { appliedKey = null }
                _status.value = DacStatus()
            }
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                _status.value = DacStatus(
                    deviceName = device.productName?.toString(),
                    detail = "Bit-perfect needs Android 14+; using system mixer",
                )
            }
            device.id != previous?.id -> {
                _status.value = DacStatus(device.productName?.toString(), false, "Waiting for playback")
                // If a track is already playing, the new mode applies from the next track or seek.
                lastFormat?.let { applyBitPerfect(device, it) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyBitPerfect(device: AudioDeviceInfo, format: PcmFormat) {
        val outputEncoding = trackEncodingFor(format.encoding)
        val key = "${device.id}/${format.sampleRate}/$outputEncoding/${format.channelCount}"
        synchronized(this) {
            if (key == appliedKey) return
            appliedKey = key
        }
        val name = device.productName?.toString()
        val match = try {
            audioManager.getSupportedMixerAttributes(device).firstOrNull {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    it.format.sampleRate == format.sampleRate &&
                    it.format.encoding == outputEncoding &&
                    it.format.channelCount == format.channelCount
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "getSupportedMixerAttributes failed", e)
            null
        }
        val label = "${format.sampleRate} Hz ${encodingLabel(outputEncoding)}"
        if (match != null && audioManager.setPreferredMixerAttributes(mediaAttributes, device, match)) {
            _status.value = DacStatus(name, true, "Bit-perfect · $label")
        } else {
            clear(device)
            _status.value = DacStatus(name, false, "DAC has no bit-perfect mode for $label; using system mixer")
        }
    }

    private fun clear(device: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                audioManager.clearPreferredMixerAttributes(mediaAttributes, device)
            } catch (e: RuntimeException) {
                Log.w(TAG, "clearPreferredMixerAttributes failed", e)
            }
        }
    }

    /** Mirrors DefaultAudioSink: high-res PCM goes out as float only when float output is on. */
    private fun trackEncodingFor(pcmEncoding: Int): Int =
        if (floatOutputEnabled && Util.isEncodingHighResolutionPcm(pcmEncoding)) {
            AudioFormat.ENCODING_PCM_FLOAT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

    private fun encodingLabel(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
        else -> "enc $encoding"
    }

    private companion object {
        const val TAG = "UsbDacManager"
    }
}
